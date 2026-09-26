package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.profile.ShareCodeException.Reason;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

// Profile share codes (docs/v0.4/SPEC.md 4, docs/research/v0.4/profiles.md §5):
//   code := "RT1-" base64url_nopad(body || crc32_be(body))
//   body := fmt:u8(=1) nameLen:u8(0..64) name:utf8[nameLen] count:u8(1..64) (key:uvarint value:uvarint){count}
// keys index ShareKeys.V1; values are that key's wire values. No compression, no strings besides the name (sanitised,
// shown only as a literal), no floats. A code is untrusted input: decode() checks everything in a fixed order and throws
// ShareCodeException, never anything else; the CRC only catches copy/paste damage, the checks are the defence.
public final class ShareCode {
	public static final String PREFIX = "RT1-";
	public static final int VERSION = 1;
	public static final int FORMAT = 1;
	public static final int MAX_RAW_CHARS = 4096;
	// 1 + 1 + 64 + 1 + 64 x (2 + 5) + 4 = 519 bytes = 692 base64 characters, plus the prefix.
	public static final int MAX_CODE_CHARS = 700;
	public static final int MAX_PAIRS = 64;
	private static final Pattern HEAD = Pattern.compile("RT([0-9]+)-");
	private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

	private ShareCode() {
	}

	public record Entry(ShareKeys.Key key, int wire) {
	}

	// name: sanitised (null when the code's name sanitises to nothing). entries: the known shareable keys, in code order.
	// unknownKeys: key indices a newer table has (skipped). localOnlyKeys: machine-specific keys (thread counts) dropped.
	public record Decoded(@Nullable String name, List<Entry> entries, int unknownKeys, int localOnlyKeys) {
		public Decoded {
			entries = List.copyOf(entries);
		}

		// key -> value, with maxFps's "match the display" resolved for a display of refreshRate Hz (-1 unknown -> 60).
		public Map<String, String> values(int refreshRate) {
			int cap = SettingValues.refreshRateCap(refreshRate);
			Map<String, String> out = new LinkedHashMap<>();
			for (Entry entry : entries) {
				out.put(entry.key().key(), entry.key().decode(entry.wire(), cap));
			}
			return out;
		}
	}

	// The code for a profile's values, or null when none of them can be shared. Keys go in table order; a value a key can't
	// carry is left out. maxFps equal to this display's $refreshRateCap (refreshRate known) is sent as "match the display".
	public static @Nullable String encode(@Nullable String name, Map<String, String> values, int refreshRate) {
		List<int[]> pairs = new ArrayList<>();
		for (ShareKeys.Key key : ShareKeys.V1) {
			if (!key.shareable() || !values.containsKey(key.key())) {
				continue;
			}
			Integer wire = key.encode(values.get(key.key()));
			if (wire == null) {
				continue;
			}
			if (key.kind() == ShareKeys.Kind.INT10 && refreshRate > 0
					&& wire.equals(key.encode(Integer.toString(SettingValues.refreshRateCap(refreshRate))))) {
				wire = ShareKeys.MATCH_DISPLAY;
			}
			pairs.add(new int[] {key.index(), wire});
		}
		if (pairs.isEmpty()) {
			return null;
		}
		String clean = ProfileNames.sanitise(name);
		byte[] nameBytes = clean == null ? new byte[0] : ProfileNames.fitUtf8(clean).getBytes(StandardCharsets.UTF_8);
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(FORMAT);
		body.write(nameBytes.length);
		body.writeBytes(nameBytes);
		body.write(pairs.size());
		for (int[] pair : pairs) {
			writeVarint(body, pair[0]);
			writeVarint(body, pair[1]);
		}
		return PREFIX + base64(withCrc(body.toByteArray()));
	}

	public static Decoded decode(@Nullable String input) throws ShareCodeException {
		if (input == null) {
			throw new ShareCodeException(Reason.NOT_A_CODE, "no input");
		}
		if (input.length() > MAX_RAW_CHARS) {
			throw new ShareCodeException(Reason.TOO_LONG, input.length() + " characters");
		}
		String code = stripAsciiWhitespace(input);
		if (code.length() > MAX_CODE_CHARS) {
			throw new ShareCodeException(Reason.TOO_LONG, code.length() + " characters without whitespace");
		}
		Matcher head = HEAD.matcher(code);
		if (!head.lookingAt()) {
			throw new ShareCodeException(Reason.NOT_A_CODE, "no RT<version>- prefix");
		}
		// The whole ^RT[0-9]+-[A-Za-z0-9_-]{8,}$ shape first, then the version (SPEC 4's order).
		String payload = code.substring(head.end());
		for (int i = 0; i < payload.length(); i++) {
			if (ALPHABET.indexOf(payload.charAt(i)) < 0) {
				throw new ShareCodeException(Reason.BAD_CHARACTERS, "character " + (int) payload.charAt(i) + " at " + i);
			}
		}
		if (payload.length() < 8) {
			throw new ShareCodeException(Reason.TRUNCATED, payload.length() + " payload characters");
		}
		String version = head.group(1);
		if (!version.equals(Integer.toString(VERSION))) {
			boolean newer = version.charAt(0) != '0' && (version.length() > 1 || version.charAt(0) > '1');
			throw new ShareCodeException(newer ? Reason.NEWER : Reason.NOT_A_CODE, "version " + version);
		}
		byte[] bytes = unbase64(payload);
		if (bytes.length < 5) {
			throw new ShareCodeException(Reason.TRUNCATED, bytes.length + " bytes");
		}
		int bodyLength = bytes.length - 4;
		CRC32 crc = new CRC32();
		crc.update(bytes, 0, bodyLength);
		long expected = ((bytes[bodyLength] & 0xFFL) << 24) | ((bytes[bodyLength + 1] & 0xFFL) << 16) | ((bytes[bodyLength + 2] & 0xFFL) << 8)
				| (bytes[bodyLength + 3] & 0xFFL);
		if (crc.getValue() != expected) {
			throw new ShareCodeException(Reason.DAMAGED, "CRC mismatch");
		}
		return parse(new Reader(bytes, bodyLength));
	}

	private static Decoded parse(Reader in) throws ShareCodeException {
		int fmt = in.u8();
		if (fmt != FORMAT) {
			throw new ShareCodeException(Reason.BAD_FORMAT, "format " + fmt);
		}
		int nameLength = in.u8();
		if (nameLength > ProfileNames.MAX_UTF8_BYTES) {
			throw new ShareCodeException(Reason.BAD_NAME, "name of " + nameLength + " bytes");
		}
		String name = strictUtf8(in.bytes(nameLength));
		int count = in.u8();
		if (count < 1 || count > MAX_PAIRS) {
			throw new ShareCodeException(Reason.BAD_COUNT, "count " + count);
		}
		List<Entry> entries = new ArrayList<>();
		Set<Integer> seen = new HashSet<>();
		int unknown = 0;
		int localOnly = 0;
		for (int i = 0; i < count; i++) {
			int index = in.varint();
			int wire = in.varint();
			if (!seen.add(index)) {
				throw new ShareCodeException(Reason.DUPLICATE_KEY, "key " + index + " twice");
			}
			ShareKeys.Key key = ShareKeys.byIndex(index);
			if (key == null) {
				unknown++;
				continue;
			}
			if (!key.validWire(wire)) {
				throw new ShareCodeException(Reason.OUT_OF_RANGE, key.key() + " = " + wire);
			}
			if (!key.shareable()) {
				localOnly++;
				continue;
			}
			entries.add(new Entry(key, wire));
		}
		if (in.remaining() != 0) {
			throw new ShareCodeException(Reason.TRAILING_BYTES, in.remaining() + " bytes after the settings");
		}
		return new Decoded(ProfileNames.sanitise(name), entries, unknown, localOnly);
	}

	private static String strictUtf8(byte[] bytes) throws ShareCodeException {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
			return chars.toString();
		} catch (CharacterCodingException e) {
			throw new ShareCodeException(Reason.BAD_NAME, "not UTF-8");
		}
	}

	// Only the ASCII whitespace chat and e-mail wrapping insert (tab, line feed, vertical tab, form feed, carriage return,
	// space); anything else stays and fails the character check.
	static String stripAsciiWhitespace(String input) {
		StringBuilder out = new StringBuilder(Math.min(input.length(), MAX_CODE_CHARS + 1));
		for (int i = 0; i < input.length(); i++) {
			char c = input.charAt(i);
			if (c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r') {
				continue;
			}
			out.append(c);
			if (out.length() > MAX_CODE_CHARS) {
				break;
			}
		}
		return out.toString();
	}

	private static byte[] withCrc(byte[] body) {
		CRC32 crc = new CRC32();
		crc.update(body);
		long value = crc.getValue();
		byte[] out = new byte[body.length + 4];
		System.arraycopy(body, 0, out, 0, body.length);
		out[body.length] = (byte) (value >>> 24);
		out[body.length + 1] = (byte) (value >>> 16);
		out[body.length + 2] = (byte) (value >>> 8);
		out[body.length + 3] = (byte) value;
		return out;
	}

	static void writeVarint(ByteArrayOutputStream out, int value) {
		if (value < 0) {
			throw new IllegalArgumentException("negative varint " + value);
		}
		int v = value;
		while (true) {
			int b = v & 0x7F;
			v >>>= 7;
			if (v == 0) {
				out.write(b);
				return;
			}
			out.write(b | 0x80);
		}
	}

	// RFC 4648 §5 without padding.
	static String base64(byte[] bytes) {
		StringBuilder out = new StringBuilder((bytes.length * 4 + 2) / 3);
		int i = 0;
		for (; i + 2 < bytes.length; i += 3) {
			int n = (bytes[i] & 0xFF) << 16 | (bytes[i + 1] & 0xFF) << 8 | (bytes[i + 2] & 0xFF);
			out.append(ALPHABET.charAt(n >>> 18)).append(ALPHABET.charAt(n >>> 12 & 63)).append(ALPHABET.charAt(n >>> 6 & 63))
					.append(ALPHABET.charAt(n & 63));
		}
		int rest = bytes.length - i;
		if (rest == 1) {
			int n = (bytes[i] & 0xFF) << 16;
			out.append(ALPHABET.charAt(n >>> 18)).append(ALPHABET.charAt(n >>> 12 & 63));
		} else if (rest == 2) {
			int n = (bytes[i] & 0xFF) << 16 | (bytes[i + 1] & 0xFF) << 8;
			out.append(ALPHABET.charAt(n >>> 18)).append(ALPHABET.charAt(n >>> 12 & 63)).append(ALPHABET.charAt(n >>> 6 & 63));
		}
		return out.toString();
	}

	// Strict: only the alphabet (checked by the caller), no padding, no impossible length, and the spare bits of a final
	// partial quantum must be zero (so every byte string has exactly one code).
	static byte[] unbase64(String text) throws ShareCodeException {
		int length = text.length();
		if (length % 4 == 1) {
			throw new ShareCodeException(Reason.BAD_BASE64, "length " + length);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(length * 3 / 4);
		int i = 0;
		for (; i + 3 < length; i += 4) {
			int n = sextet(text, i) << 18 | sextet(text, i + 1) << 12 | sextet(text, i + 2) << 6 | sextet(text, i + 3);
			out.write(n >>> 16);
			out.write(n >>> 8 & 0xFF);
			out.write(n & 0xFF);
		}
		int rest = length - i;
		if (rest == 2) {
			int n = sextet(text, i) << 18 | sextet(text, i + 1) << 12;
			if ((n & 0xFFFF) != 0) {
				throw new ShareCodeException(Reason.BAD_BASE64, "spare bits set");
			}
			out.write(n >>> 16);
		} else if (rest == 3) {
			int n = sextet(text, i) << 18 | sextet(text, i + 1) << 12 | sextet(text, i + 2) << 6;
			if ((n & 0xFF) != 0) {
				throw new ShareCodeException(Reason.BAD_BASE64, "spare bits set");
			}
			out.write(n >>> 16);
			out.write(n >>> 8 & 0xFF);
		}
		return out.toByteArray();
	}

	private static int sextet(String text, int at) throws ShareCodeException {
		int value = ALPHABET.indexOf(text.charAt(at));
		if (value < 0) {
			throw new ShareCodeException(Reason.BAD_CHARACTERS, "character at " + at);
		}
		return value;
	}

	private static final class Reader {
		private final byte[] bytes;
		private final int end;
		private int at;

		Reader(byte[] bytes, int end) {
			this.bytes = bytes;
			this.end = end;
		}

		int remaining() {
			return end - at;
		}

		int u8() throws ShareCodeException {
			if (at >= end) {
				throw new ShareCodeException(Reason.TRUNCATED, "ends at byte " + at);
			}
			return bytes[at++] & 0xFF;
		}

		byte[] bytes(int count) throws ShareCodeException {
			if (count > remaining()) {
				throw new ShareCodeException(Reason.TRUNCATED, "name runs past the end");
			}
			byte[] out = new byte[count];
			System.arraycopy(bytes, at, out, 0, count);
			at += count;
			return out;
		}

		// Canonical unsigned LEB128: at most 5 bytes, below 2^31, no redundant trailing zero byte.
		int varint() throws ShareCodeException {
			long value = 0;
			for (int i = 0; i < 5; i++) {
				int b = u8();
				value |= (long) (b & 0x7F) << (7 * i);
				if ((b & 0x80) == 0) {
					if (i > 0 && b == 0) {
						throw new ShareCodeException(Reason.VARINT_OVERLONG, "overlong varint at byte " + (at - 1));
					}
					if (value > Integer.MAX_VALUE) {
						throw new ShareCodeException(Reason.VALUE_TOO_LARGE, "varint " + value);
					}
					return (int) value;
				}
			}
			throw new ShareCodeException(Reason.VARINT_TOO_LONG, "varint longer than 5 bytes at byte " + (at - 1));
		}
	}
}
