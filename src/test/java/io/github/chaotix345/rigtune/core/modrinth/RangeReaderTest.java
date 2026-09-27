package io.github.chaotix345.rigtune.core.modrinth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.model.ModFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2H L5 (AC2H.1, AC2H.1c): a download's fabric.mod.json read in memory through single HTTP Range
// requests, from a loopback server that serves ranges the way cdn.modrinth.com does (ws-h.md's live check) or misbehaves.
class RangeReaderTest {
	private static final Pattern RANGE = Pattern.compile("bytes=(\\d*)-(\\d*)");
	private static final byte[] FABRIC_MOD_JSON = """
			{"schemaVersion":1,"id":"examplemod","version":"1.2.3","name":"Example Mod","depends":{"sodium":"0.9.x"}}""".getBytes(StandardCharsets.UTF_8);
	// Small caps, so a fixture of a few hundred KiB has its central directory outside the tail.
	private static final RangeReader.Limits SMALL = new RangeReader.Limits(4096, 256 << 10, ModJars.MAX_FABRIC_MOD_JSON_BYTES, 16L << 20,
			Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(30));

	enum Mode {
		RANGES, WHOLE, STATUS_416, STATUS_500, WRONG_RANGE, STALL
	}

	private HttpServer server;
	private ExecutorService handlers;
	private final Map<String, byte[]> files = new ConcurrentHashMap<>();
	private final List<String> served = Collections.synchronizedList(new ArrayList<>());
	private volatile Mode mode = Mode.RANGES;
	private final AtomicBoolean allowed = new AtomicBoolean(true);

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", this::handle);
		handlers = Executors.newCachedThreadPool();
		server.setExecutor(handlers);
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
		handlers.shutdownNow();
	}

	private String origin() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private RangeReader reader(RangeReader.Limits limits) {
		return new RangeReader("chaotix345/rigtune/test", origin() + "/", allowed::get, limits);
	}

	private ModFile file(String name, byte[] bytes) {
		files.put("/data/p/versions/v/" + name, bytes);
		return new ModFile(origin() + "/data/p/versions/v/" + name, name, "unused", bytes.length);
	}

	// Single ranges as the CDN answers them; a range past the end is a 500 there (416 here in STATUS_416 mode).
	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			byte[] body = files.get(exchange.getRequestURI().getPath());
			String range = exchange.getRequestHeaders().getFirst("Range");
			served.add(range == null ? "none" : range);
			if (body == null) {
				exchange.sendResponseHeaders(404, -1);
				return;
			}
			if (mode == Mode.WHOLE || range == null) {
				send(exchange, 200, body, null);
				return;
			}
			if (mode == Mode.STATUS_416 || mode == Mode.STATUS_500) {
				exchange.sendResponseHeaders(mode == Mode.STATUS_416 ? 416 : 500, -1);
				return;
			}
			Matcher m = RANGE.matcher(range);
			if (!m.matches()) {
				send(exchange, 200, body, null);
				return;
			}
			long size = body.length;
			long from;
			long to;
			if (m.group(1).isEmpty()) {
				from = Math.max(0, size - Long.parseLong(m.group(2)));
				to = size - 1;
			} else {
				from = Long.parseLong(m.group(1));
				to = m.group(2).isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(m.group(2)));
			}
			if (from >= size) {
				exchange.sendResponseHeaders(500, -1);
				return;
			}
			if (mode == Mode.WRONG_RANGE) {
				from = Math.max(0, from - 1);
			}
			byte[] part = new byte[(int) (to - from + 1)];
			System.arraycopy(body, (int) from, part, 0, part.length);
			if (mode == Mode.STALL) {
				exchange.getResponseHeaders().set("Content-Range", "bytes " + from + "-" + to + "/" + size);
				exchange.sendResponseHeaders(206, part.length);
				exchange.getResponseBody().write(part, 0, Math.min(10, part.length));
				exchange.getResponseBody().flush();
				try {
					Thread.sleep(5_000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return;
			}
			send(exchange, 206, part, "bytes " + from + "-" + to + "/" + size);
		}
	}

	private static void send(HttpExchange exchange, int status, byte[] body, String contentRange) throws IOException {
		if (contentRange != null) {
			exchange.getResponseHeaders().set("Content-Range", contentRange);
		}
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	// A jar of `fillers` incompressible class files (1 KiB each) and fabric.mod.json at `at` (-1: none).
	private static byte[] jar(int fillers, int at, boolean stored, byte[] fabricModJson) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		Random random = new Random(7);
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (int i = 0; i <= fillers; i++) {
				if (i == at) {
					ZipEntry entry = new ZipEntry("fabric.mod.json");
					if (stored) {
						CRC32 crc = new CRC32();
						crc.update(fabricModJson);
						entry.setMethod(ZipEntry.STORED);
						entry.setSize(fabricModJson.length);
						entry.setCompressedSize(fabricModJson.length);
						entry.setCrc(crc.getValue());
					}
					zip.putNextEntry(entry);
					zip.write(fabricModJson);
					zip.closeEntry();
				}
				if (i < fillers) {
					zip.putNextEntry(new ZipEntry("io/example/some/rather/long/package/name/Filler" + i + ".class"));
					byte[] filler = new byte[1024];
					random.nextBytes(filler);
					zip.write(filler);
					zip.closeEntry();
				}
			}
		}
		return bytes.toByteArray();
	}

	@Test
	void aDeflatedEntryIsReadThroughTheTailTheCentralDirectoryAndTheEntry() throws IOException {
		byte[] jar = jar(300, 5, false, FABRIC_MOD_JSON);
		try (RangeReader reader = reader(SMALL)) {
			RangeReader.Read read = reader.read(file("example-1.2.3.jar", jar));

			assertTrue(read.ok(), read.toString());
			assertArrayEquals(FABRIC_MOD_JSON, read.fabricModJson());
			assertEquals(3, read.requests(), served.toString());
			assertEquals("bytes=-4096", served.getFirst());
			// Far less than the file, and nothing past the caps.
			assertTrue(read.bytesRead() < jar.length / 4, read.bytesRead() + " of " + jar.length);
			assertTrue(read.bytesRead() <= SMALL.tailBytes() + SMALL.maxCentralDirectoryBytes() + SMALL.maxEntryBytes() + RangeReader.LOCAL_HEADER_SLACK);
		}
	}

	@Test
	void aStoredEntryIsReadAsIs() throws IOException {
		byte[] jar = jar(300, 120, true, FABRIC_MOD_JSON);
		try (RangeReader reader = reader(SMALL)) {
			RangeReader.Read read = reader.read(file("stored.jar", jar));

			assertTrue(read.ok(), read.toString());
			assertArrayEquals(FABRIC_MOD_JSON, read.fabricModJson());
		}
	}

	// A small jar (or fabric.mod.json near the end with a small central directory): one request.
	@Test
	void whatTheTailHoldsIsNotAskedForAgain() throws IOException {
		byte[] jar = jar(2, 2, false, FABRIC_MOD_JSON);
		try (RangeReader reader = reader(SMALL)) {
			RangeReader.Read read = reader.read(file("small.jar", jar));

			assertTrue(read.ok(), read.toString());
			assertArrayEquals(FABRIC_MOD_JSON, read.fabricModJson());
			assertEquals(1, read.requests(), served.toString());
			assertEquals(jar.length, read.bytesRead());
		}
	}

	@Test
	void aJarWithoutFabricModJsonIsMissingNotFailed() throws IOException {
		byte[] jar = jar(300, -1, false, FABRIC_MOD_JSON);
		try (RangeReader reader = reader(SMALL)) {
			RangeReader.Read read = reader.read(file("plain.jar", jar));

			assertFalse(read.ok());
			assertTrue(read.missing(), read.toString());
			assertNull(read.failure());
			assertNull(read.fabricModJson());
			assertEquals(2, read.requests(), served.toString());
		}
	}

	@Test
	void aTruncatedOrMalformedFileFailsAndFallsBack() throws IOException {
		byte[] jar = jar(300, 5, false, FABRIC_MOD_JSON);
		byte[] truncated = java.util.Arrays.copyOf(jar, jar.length - 100);
		byte[] text = "RigTune game-test fixture: lithium for Minecraft 26.2\n".getBytes(StandardCharsets.UTF_8);
		byte[] corrupt = jar.clone();
		// A byte of fabric.mod.json's deflated data: the CRC no longer matches.
		int local = indexOf(corrupt, "fabric.mod.json".getBytes(StandardCharsets.UTF_8));
		corrupt[local + "fabric.mod.json".length() + 3] ^= 0x55;
		try (RangeReader reader = reader(SMALL)) {
			for (Map.Entry<String, byte[]> bad : Map.of("truncated.jar", truncated, "text.jar", text, "corrupt.jar", corrupt).entrySet()) {
				RangeReader.Read read = reader.read(file(bad.getKey(), bad.getValue()));
				assertFalse(read.ok(), bad.getKey());
				assertFalse(read.missing(), bad.getKey());
				assertNotNull(read.failure(), bad.getKey());
			}
		}
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	// A server that ignores Range: the answer's body is dropped at once, never read in full.
	@Test
	void aWholeBodyAnswerFailsWithoutReadingIt() throws IOException {
		mode = Mode.WHOLE;
		byte[] jar = jar(300, 5, false, FABRIC_MOD_JSON);
		try (RangeReader reader = reader(SMALL)) {
			RangeReader.Read read = reader.read(file("whole.jar", jar));

			assertFalse(read.ok());
			assertTrue(read.failure().contains("200"), read.failure());
			assertEquals(0, read.bytesRead());
			assertEquals(1, read.requests());
		}
	}

	@Test
	void a416Or500OrAWrongContentRangeFails() throws IOException {
		byte[] jar = jar(300, 5, false, FABRIC_MOD_JSON);
		for (Mode bad : List.of(Mode.STATUS_416, Mode.STATUS_500, Mode.WRONG_RANGE)) {
			mode = bad;
			try (RangeReader reader = reader(SMALL)) {
				RangeReader.Read read = reader.read(file("bad-" + bad + ".jar", jar));
				assertFalse(read.ok(), bad.toString());
				assertNotNull(read.failure(), bad.toString());
				assertEquals(1, read.requests(), bad.toString());
			}
		}
	}

	@Test
	void aStalledAnswerFailsAtTheStallLimit() throws IOException {
		mode = Mode.STALL;
		RangeReader.Limits impatient = new RangeReader.Limits(4096, 256 << 10, ModJars.MAX_FABRIC_MOD_JSON_BYTES, 16L << 20, Duration.ofMillis(300),
				Duration.ofSeconds(10), Duration.ofSeconds(30));
		try (RangeReader reader = reader(impatient)) {
			long start = System.nanoTime();
			RangeReader.Read read = reader.read(file("stall.jar", jar(300, 5, false, FABRIC_MOD_JSON)));

			assertFalse(read.ok());
			assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(4)) < 0, "gave up at the stall limit");
		}
	}

	// AC2H.1c: only the allowed download origin (HttpModrinthClient.allowedDownload's rule) is ever asked.
	@Test
	void anotherOriginIsNeverAsked() throws IOException {
		try (RangeReader reader = reader(SMALL)) {
			RangeReader.Read other = reader.read(new ModFile("http://127.0.0.2:" + server.getAddress().getPort() + "/data/p/versions/v/x.jar", "x.jar",
					"unused", 100));
			RangeReader.Read plainHttp = reader.read(new ModFile("http://cdn.modrinth.com/data/p/versions/v/x.jar", "x.jar", "unused", 100));
			RangeReader.Read notAUrl = reader.read(new ModFile("not a url", "x.jar", "unused", 100));

			assertFalse(other.ok());
			assertFalse(plainHttp.ok());
			assertFalse(notAUrl.ok());
			assertEquals(List.of(), served);
		}
		assertTrue(HttpModrinthClient.allowedDownload(java.net.URI.create("https://cdn.modrinth.com/data/x.jar"), HttpModrinthClient.DEFAULT_BASE_URL));
	}

	// Modrinth on only: switched off, nothing is asked (and a switch mid-preview stops the next read).
	@Test
	void withModrinthOffNothingIsAsked() throws IOException {
		byte[] jar = jar(300, 5, false, FABRIC_MOD_JSON);
		allowed.set(false);
		try (RangeReader reader = reader(SMALL)) {
			RangeReader.Read read = reader.read(file("off.jar", jar));

			assertFalse(read.ok());
			assertEquals(0, read.requests());
			assertEquals(List.of(), served);
		}
	}

	// The caps: a central directory or an entry over them isn't asked for.
	@Test
	void aCentralDirectoryOrEntryOverItsCapFailsBeforeItIsAskedFor() throws IOException {
		byte[] jar = jar(300, 5, false, FABRIC_MOD_JSON);
		RangeReader.Limits tinyDirectory = new RangeReader.Limits(4096, 1024, ModJars.MAX_FABRIC_MOD_JSON_BYTES, 16L << 20, Duration.ofSeconds(5),
				Duration.ofSeconds(10), Duration.ofSeconds(30));
		try (RangeReader reader = reader(tinyDirectory)) {
			RangeReader.Read read = reader.read(file("big-directory.jar", jar));
			assertFalse(read.ok());
			assertEquals(1, read.requests());
			assertEquals(4096, read.bytesRead());
		}
		RangeReader.Limits tinyEntry = new RangeReader.Limits(4096, 256 << 10, 16, 16L << 20, Duration.ofSeconds(5), Duration.ofSeconds(10),
				Duration.ofSeconds(30));
		try (RangeReader reader = reader(tinyEntry)) {
			RangeReader.Read read = reader.read(file("big-entry.jar", jar));
			assertFalse(read.ok());
			assertEquals(2, read.requests());
		}
	}

	// One preview's reads share a byte budget and a deadline: past either, the rest fall back without asking.
	@Test
	void aPreviewsBudgetEndsItsReads() throws IOException {
		byte[] jar = jar(300, 5, false, FABRIC_MOD_JSON);
		RangeReader.Limits oneJar = new RangeReader.Limits(4096, 256 << 10, ModJars.MAX_FABRIC_MOD_JSON_BYTES, 60_000, Duration.ofSeconds(5),
				Duration.ofSeconds(10), Duration.ofSeconds(30));
		try (RangeReader reader = reader(oneJar)) {
			assertTrue(reader.read(file("first.jar", jar)).ok());
			int asked = served.size();
			RangeReader.Read second = reader.read(file("second.jar", jar));
			assertFalse(second.ok());
			assertTrue(served.size() - asked <= 1, served.toString());
		}
	}

	// AC2H.1: nothing is written to disk; the reader has no file API at all.
	@Test
	void theReaderNeverTouchesTheDisk() throws IOException {
		String source = Files.readString(RepoFiles.resolve("src/main/java/io/github/chaotix345/rigtune/core/modrinth/RangeReader.java"));
		for (String api : List.of("java.nio.file", "java.io.File", "FileChannel", "Files.", "RandomAccessFile")) {
			assertFalse(source.contains(api), api);
		}
	}

	// ws-h.md "H1 live run": the one check against cdn.modrinth.com itself, never in CI (it needs the network).
	@Test
	@EnabledIfEnvironmentVariable(named = "RIGTUNE_LIVE_RANGE_CHECK", matches = "1")
	void liveCheckAgainstModrinthsCdn() {
		ModFile sodium = new ModFile("https://cdn.modrinth.com/data/AANobbMI/versions/xJZxADzI/sodium-fabric-0.9.2%2Bmc26.2.jar",
				"sodium-fabric-0.9.2+mc26.2.jar", "unused", 1885572);
		try (RangeReader reader = new RangeReader("0.5.0-live-check", () -> true)) {
			RangeReader.Read read = reader.read(sodium);
			FabricModJson json = read.ok() ? FabricModJson.parse(read.fabricModJson()) : null;
			System.out.println("RangeReader live check: " + read + " -> " + json);
			assertTrue(read.ok(), read.toString());
			assertEquals("sodium", json.id());
		}
	}
}
