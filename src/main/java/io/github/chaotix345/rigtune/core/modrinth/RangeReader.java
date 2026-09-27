package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.net.BoundedHttp;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

// docs/v0.5/SPEC.md 2H L5: a download's fabric.mod.json, read in memory through single HTTP Range requests, so Preview can
// run Apply's fabric.mod.json checks before anything is downloaded. Three requests at most: the file's last tailBytes
// (the end of central directory record), the central directory if the tail doesn't hold it, then the one entry, inflated
// here. Only from the allowed download origin (HttpModrinthClient.allowedDownload), only while Modrinth is allowed, each
// request with BoundedHttp's stall and deadline and a byte cap, one reader's reads within a byte budget and a deadline.
// A 206 counts only when its Content-Range is the range asked for (cdn.modrinth.com answers several ranges, or a suffix
// longer than the file, with the whole file; a range past the end with a 500: ws-h.md); any other answer fails the read
// and its body is dropped at once. ZIP64, split and encrypted archives fail too. A failed read is the caller's fallback
// (Preview's disclosure); nothing here touches the disk.
public final class RangeReader implements AutoCloseable {
	// What a local file header's extra field may add over the central directory's (read in one go with the entry).
	static final int LOCAL_HEADER_SLACK = 1024;
	private static final int EOCD = 0x06054b50;
	private static final int CENTRAL = 0x02014b50;
	private static final int LOCAL = 0x04034b50;
	private static final int EOCD_SIZE = 22;
	private static final int CENTRAL_SIZE = 46;
	private static final int LOCAL_SIZE = 30;
	private static final byte[] NAME = "fabric.mod.json".getBytes(StandardCharsets.UTF_8);
	private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	// tailBytes: the first request (the end record is at most 22 + 65535 bytes from the end); maxCentralDirectoryBytes,
	// maxEntryBytes (compressed and inflated): past them a read fails before asking; budgetBytes, total: all of one reader's
	// reads (one preview); stall, requestDeadline: each request, as BoundedHttp.
	public record Limits(int tailBytes, long maxCentralDirectoryBytes, long maxEntryBytes, long budgetBytes, Duration stall, Duration requestDeadline,
			Duration total) {
		public static final Limits DEFAULT = new Limits(64 << 10, 2L << 20, ModJars.MAX_FABRIC_MOD_JSON_BYTES, 16L << 20, Duration.ofSeconds(10),
				Duration.ofSeconds(20), Duration.ofSeconds(30));
	}

	// One read: fabricModJson when the entry was read; missing when the central directory, read in full, has no
	// fabric.mod.json (Apply finds no mod id in that jar either); else failure says why. bytesRead: body bytes taken in.
	public record Read(byte @Nullable [] fabricModJson, boolean missing, @Nullable String failure, long bytesRead, int requests) {
		public boolean ok() {
			return fabricModJson != null;
		}

		@Override
		public String toString() {
			return (ok() ? "read " + fabricModJson.length + " bytes of fabric.mod.json" : missing ? "no fabric.mod.json" : "failed: " + failure)
					+ " (" + requests + " request(s), " + bytesRead + " bytes)";
		}
	}

	private final String userAgent;
	private final String baseUrl;
	private final BooleanSupplier allowed;
	private final Limits limits;
	private @Nullable HttpClient http;
	private long started;
	private long bytesLeft;

	// allowed: whether Modrinth may be asked now (RigTune's settings), checked before every request.
	public RangeReader(String modVersion, BooleanSupplier allowed) {
		this(HttpModrinthClient.userAgent(modVersion), baseUrl(System.getProperty(HttpModrinthClient.BASE_URL_PROPERTY)), allowed, Limits.DEFAULT);
	}

	// baseUrl: the Modrinth base URL whose origin downloads may also come from (HttpModrinthClient.allowedDownload).
	RangeReader(String userAgent, String baseUrl, BooleanSupplier allowed, Limits limits) {
		this.userAgent = userAgent;
		this.baseUrl = baseUrl;
		this.allowed = allowed;
		this.limits = limits;
		this.bytesLeft = limits.budgetBytes();
	}

	// As HttpModrinthClient takes -Drigtune.modrinth.baseUrl: an override it ignores allows no extra origin here either.
	private static String baseUrl(@Nullable String configured) {
		return configured == null || configured.isBlank() ? HttpModrinthClient.DEFAULT_BASE_URL : configured.trim();
	}

	public synchronized Read read(ModFile file) {
		Exchange exchange = new Exchange();
		try {
			byte[] json = fabricModJson(file, exchange);
			return new Read(json, json == null, null, exchange.bytes, exchange.requests);
		} catch (IOException | RuntimeException e) {
			return new Read(null, false, String.valueOf(e.getMessage()), exchange.bytes, exchange.requests);
		}
	}

	@Override
	public synchronized void close() {
		if (http != null) {
			http.shutdownNow();
			http = null;
		}
	}

	private static final class Exchange {
		long bytes;
		int requests;
		long total = -1;
		// The file's last bytes, from the first request.
		long tailStart;
		byte[] tail = new byte[0];
	}

	// Null: no fabric.mod.json in the archive.
	private byte @Nullable [] fabricModJson(ModFile file, Exchange x) throws IOException {
		URI uri;
		try {
			uri = URI.create(file.url());
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new IOException("not a URL");
		}
		if (!HttpModrinthClient.allowedDownload(uri, baseUrl)) {
			throw new IOException("not an allowed download origin: " + uri.getScheme() + "://" + uri.getRawAuthority());
		}
		x.tail = get(uri, -1, limits.tailBytes(), x);
		x.tailStart = x.total - x.tail.length;
		ByteBuffer tail = ByteBuffer.wrap(x.tail).order(ByteOrder.LITTLE_ENDIAN);
		int end = endRecord(tail);
		if (end < 0) {
			throw new IOException("no zip end record");
		}
		int disk = u16(tail, end + 4);
		int directoryDisk = u16(tail, end + 6);
		int entriesHere = u16(tail, end + 8);
		int entries = u16(tail, end + 10);
		long directorySize = u32(tail, end + 12);
		long directoryAt = u32(tail, end + 16);
		if (disk != 0 || directoryDisk != 0 || entriesHere != entries) {
			throw new IOException("a split archive");
		}
		if (entries == 0xFFFF || directorySize == 0xFFFFFFFFL || directoryAt == 0xFFFFFFFFL) {
			throw new IOException("a ZIP64 archive");
		}
		if (directoryAt + directorySize > x.tailStart + end) {
			throw new IOException("the central directory runs past the end record");
		}
		if (directorySize > limits.maxCentralDirectoryBytes()) {
			throw new IOException("central directory of " + directorySize + " bytes is over the cap");
		}
		ByteBuffer directory = ByteBuffer.wrap(bytes(uri, directoryAt, directorySize, x)).order(ByteOrder.LITTLE_ENDIAN);
		Entry entry = find(directory, entries);
		if (entry == null) {
			return null;
		}
		if ((entry.flags & 1) != 0) {
			throw new IOException("fabric.mod.json is encrypted");
		}
		if (entry.method != 0 && entry.method != 8) {
			throw new IOException("fabric.mod.json uses compression method " + entry.method);
		}
		if (entry.size > limits.maxEntryBytes() || entry.compressedSize > limits.maxEntryBytes() || entry.method == 0 && entry.size != entry.compressedSize) {
			throw new IOException("fabric.mod.json of " + entry.size + " bytes is over the cap");
		}
		if (entry.localAt + LOCAL_SIZE > directoryAt) {
			throw new IOException("fabric.mod.json's local header is past the central directory");
		}
		long ask = Math.min(directoryAt - entry.localAt, LOCAL_SIZE + entry.nameLength + entry.extraLength + entry.compressedSize + LOCAL_HEADER_SLACK);
		ByteBuffer local = ByteBuffer.wrap(bytes(uri, entry.localAt, ask, x)).order(ByteOrder.LITTLE_ENDIAN);
		if (local.capacity() < LOCAL_SIZE + NAME.length || local.getInt(0) != LOCAL || u16(local, 26) != NAME.length || !Arrays.equals(Arrays.copyOfRange(local.array(), LOCAL_SIZE, LOCAL_SIZE + NAME.length), NAME)) {
			throw new IOException("fabric.mod.json's local header doesn't match the central directory");
		}
		long dataAt = LOCAL_SIZE + (long) u16(local, 26) + u16(local, 28);
		long dataEnd = dataAt + entry.compressedSize;
		if (entry.localAt + dataEnd > directoryAt) {
			throw new IOException("fabric.mod.json's data runs into the central directory");
		}
		byte[] data;
		if (dataEnd <= local.capacity()) {
			data = Arrays.copyOfRange(local.array(), (int) dataAt, (int) dataEnd);
		} else {
			// A local extra field longer than the slack: the rest of the entry.
			byte[] rest = bytes(uri, entry.localAt + local.capacity(), dataEnd - local.capacity(), x);
			byte[] joined = Arrays.copyOf(local.array(), (int) dataEnd);
			System.arraycopy(rest, 0, joined, local.capacity(), rest.length);
			data = Arrays.copyOfRange(joined, (int) dataAt, (int) dataEnd);
		}
		byte[] json = entry.method == 0 ? data : inflate(data, entry.size);
		CRC32 crc = new CRC32();
		crc.update(json);
		if (json.length != entry.size || crc.getValue() != entry.crc) {
			throw new IOException("fabric.mod.json doesn't match its size or CRC");
		}
		return json;
	}

	// The end of central directory record: the last signature whose comment length reaches exactly to the end.
	private static int endRecord(ByteBuffer tail) {
		for (int at = tail.capacity() - EOCD_SIZE; at >= 0 && at >= tail.capacity() - EOCD_SIZE - 0xFFFF; at--) {
			if (tail.getInt(at) == EOCD && u16(tail, at + 20) == tail.capacity() - at - EOCD_SIZE) {
				return at;
			}
		}
		return -1;
	}

	private record Entry(int flags, int method, long crc, long compressedSize, long size, int nameLength, int extraLength, long localAt) {
	}

	// fabric.mod.json's central directory entry; null when there's none. Two are a malformed archive.
	private static @Nullable Entry find(ByteBuffer directory, int entries) throws IOException {
		Entry found = null;
		int at = 0;
		for (int i = 0; i < entries; i++) {
			if (at + CENTRAL_SIZE > directory.capacity() || directory.getInt(at) != CENTRAL) {
				throw new IOException("a malformed central directory");
			}
			int nameLength = u16(directory, at + 28);
			int extraLength = u16(directory, at + 30);
			int commentLength = u16(directory, at + 32);
			int next = at + CENTRAL_SIZE + nameLength + extraLength + commentLength;
			if (next > directory.capacity()) {
				throw new IOException("a malformed central directory");
			}
			if (nameLength == NAME.length && Arrays.equals(Arrays.copyOfRange(directory.array(), at + CENTRAL_SIZE, at + CENTRAL_SIZE + nameLength), NAME)) {
				if (found != null) {
					throw new IOException("two fabric.mod.json entries");
				}
				found = new Entry(u16(directory, at + 8), u16(directory, at + 10), u32(directory, at + 16), u32(directory, at + 20),
						u32(directory, at + 24), nameLength, extraLength, u32(directory, at + 42));
			}
			at = next;
		}
		return found;
	}

	private static byte[] inflate(byte[] data, long size) throws IOException {
		Inflater inflater = new Inflater(true);
		try {
			inflater.setInput(data);
			// One byte more than the entry says, so an entry that inflates to more is caught.
			byte[] out = new byte[(int) size + 1];
			int n = 0;
			while (n < out.length && !inflater.finished()) {
				int got = inflater.inflate(out, n, out.length - n);
				if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					break;
				}
				n += got;
			}
			return Arrays.copyOf(out, n);
		} catch (DataFormatException e) {
			throw new IOException("fabric.mod.json doesn't inflate: " + e.getMessage());
		} finally {
			inflater.end();
		}
	}

	// Bytes [from, from + length) of the file, from the tail where it holds them (asking only for what's before it).
	private byte[] bytes(URI uri, long from, long length, Exchange x) throws IOException {
		long end = from + length;
		if (from >= x.tailStart) {
			return Arrays.copyOfRange(x.tail, (int) (from - x.tailStart), (int) (end - x.tailStart));
		}
		if (end <= x.tailStart) {
			return get(uri, from, length, x);
		}
		byte[] before = get(uri, from, x.tailStart - from, x);
		byte[] out = Arrays.copyOf(before, (int) length);
		System.arraycopy(x.tail, 0, out, before.length, (int) (end - x.tailStart));
		return out;
	}

	// One range: from < 0 asks for the file's last `length` bytes. The answer must be a 206 for exactly that range.
	private byte[] get(URI uri, long from, long length, Exchange x) throws IOException {
		if (!allowed.getAsBoolean()) {
			throw new IOException("Modrinth is off in RigTune's settings");
		}
		if (length > bytesLeft) {
			throw new IOException("this preview's byte budget is used up");
		}
		long now = System.nanoTime();
		if (started == 0) {
			started = now;
		}
		Duration left = limits.total().minusNanos(now - started);
		if (left.isNegative() || left.isZero()) {
			throw new IOException("this preview's time budget is used up");
		}
		String range = from < 0 ? "bytes=-" + length : "bytes=" + from + "-" + (from + length - 1);
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(limits.requestDeadline()).header("User-Agent", userAgent).header("Range", range)
				.GET().build();
		BoundedHttp.Progress progress = new BoundedHttp.Progress();
		x.requests++;
		HttpResponse<byte[]> response = BoundedHttp.send(http(), request,
				info -> info.statusCode() == 206 ? BoundedHttp.bytes(length, false, progress) : BoundedHttp.bytes(0, true, progress), progress,
				limits.stall(), left.compareTo(limits.requestDeadline()) < 0 ? left : limits.requestDeadline());
		byte[] body = response.body();
		x.bytes += body.length;
		bytesLeft -= body.length;
		if (response.statusCode() != 206) {
			throw new IOException("HTTP " + response.statusCode() + " to " + range);
		}
		if (response.headers().firstValue("Content-Encoding").filter(e -> !e.equalsIgnoreCase("identity")).isPresent()) {
			throw new IOException("an encoded range");
		}
		Matcher m = CONTENT_RANGE.matcher(response.headers().firstValue("Content-Range").orElse("").trim());
		if (!m.matches()) {
			throw new IOException("no usable Content-Range for " + range);
		}
		long first = Long.parseLong(m.group(1));
		long last = Long.parseLong(m.group(2));
		long total = Long.parseLong(m.group(3));
		boolean asked = from < 0 ? first == Math.max(0, total - length) && last == total - 1 : first == from && last == from + length - 1;
		if (!asked || x.total >= 0 && total != x.total || body.length != last - first + 1) {
			throw new IOException("Content-Range " + m.group() + " isn't the " + range + " asked for");
		}
		x.total = total;
		return body;
	}

	private synchronized HttpClient http() {
		if (http == null) {
			http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER)
					.build();
		}
		return http;
	}

	private static int u16(ByteBuffer buffer, int at) {
		return Short.toUnsignedInt(buffer.getShort(at));
	}

	private static long u32(ByteBuffer buffer, int at) {
		return Integer.toUnsignedLong(buffer.getInt(at));
	}
}
