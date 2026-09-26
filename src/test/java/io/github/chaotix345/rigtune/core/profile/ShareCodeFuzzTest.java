package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.profile.ShareCodeException.Reason;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// docs/v0.4/SPEC.md AC4.2 (security): share codes are untrusted input.
class ShareCodeFuzzTest {
	private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

	@Test
	void everySingleByteFlipOfTheDecodedBytesFailsTheCrc() throws ShareCodeException {
		for (String golden : ShareCodeTest.GOLDEN) {
			byte[] bytes = ShareCode.unbase64(golden.substring(4));
			for (int i = 0; i < bytes.length; i++) {
				for (int mask : new int[] {0x01, 0x80, 0xFF, 0x55}) {
					byte[] flipped = bytes.clone();
					flipped[i] ^= (byte) mask;
					assertReason(Reason.DAMAGED, "RT1-" + ShareCode.base64(flipped));
				}
			}
		}
	}

	@Test
	void everySingleCharacterChangeOfTheCodeIsRejected() {
		for (String golden : ShareCodeTest.GOLDEN) {
			for (int i = 0; i < golden.length(); i++) {
				for (char c : new char[] {'A', 'z', '0', '-', '_', '=', '+', '/', '.', 'é'}) {
					if (golden.charAt(i) == c) {
						continue;
					}
					String changed = golden.substring(0, i) + c + golden.substring(i + 1);
					assertThrows(ShareCodeException.class, () -> ShareCode.decode(changed), changed);
				}
			}
		}
	}

	@Test
	void everyTruncationIsRejected() {
		for (String golden : ShareCodeTest.GOLDEN) {
			for (int length = 0; length < golden.length(); length++) {
				String cut = golden.substring(0, length);
				assertThrows(ShareCodeException.class, () -> ShareCode.decode(cut), cut);
			}
		}
	}

	@Test
	void randomBodiesWithAValidCrcOnlyEverThrowShareCodeException() {
		Random random = new Random(0x5EED_4C0DEL);
		int decoded = 0;
		for (int n = 0; n < 100_000; n++) {
			byte[] body;
			if (n % 2 == 0) {
				body = new byte[random.nextInt(80)];
				random.nextBytes(body);
			} else {
				// Plausible bodies: format 1, a short name, a count and random pairs, so the pair parser gets exercised.
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				out.write(random.nextInt(8) == 0 ? random.nextInt(256) : 1);
				int nameLength = random.nextInt(8);
				out.write(nameLength);
				for (int i = 0; i < nameLength; i++) {
					out.write(random.nextInt(256));
				}
				int count = random.nextInt(8) == 0 ? random.nextInt(256) : 1 + random.nextInt(6);
				out.write(count);
				int pairs = count + random.nextInt(3) - 1;
				for (int i = 0; i < pairs; i++) {
					out.write(random.nextInt(4) == 0 ? random.nextInt(256) : random.nextInt(34));
					int valueBytes = 1 + (random.nextInt(6) == 0 ? random.nextInt(6) : 0);
					for (int b = 0; b < valueBytes; b++) {
						out.write(random.nextInt(256) & (b + 1 < valueBytes ? 0xFF : 0x7F) | (b + 1 < valueBytes ? 0x80 : 0));
					}
				}
				body = out.toByteArray();
			}
			String code = "RT1-" + ShareCode.base64(withCrc(body));
			try {
				ShareCode.decode(code);
				decoded++;
			} catch (ShareCodeException expected) {
				// fine
			} catch (Throwable other) {
				fail("Decoding " + code + " threw " + other, other);
			}
		}
		assertTrue(decoded > 100, "the plausible bodies should sometimes decode: " + decoded);
	}

	@Test
	void malformedBodiesAreRejectedWithTheRightReason() {
		// fmt 1, name "a", count 1, key 0 (renderDistance), value 10.
		assertReason(null, code(1, 1, 'a', 1, 0, 10));
		assertReason(Reason.VARINT_OVERLONG, code(1, 1, 'a', 1, 0, 0x8A, 0x00));
		assertReason(Reason.VARINT_OVERLONG, code(1, 1, 'a', 1, 0x80, 0x00, 10));
		assertReason(Reason.VARINT_TOO_LONG, code(1, 1, 'a', 1, 0, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01));
		assertReason(Reason.VALUE_TOO_LARGE, code(1, 1, 'a', 1, 0, 0x80, 0x80, 0x80, 0x80, 0x08));
		assertReason(Reason.VALUE_TOO_LARGE, code(1, 1, 'a', 1, 0x80, 0x80, 0x80, 0x80, 0x0F, 1));
		assertReason(Reason.OUT_OF_RANGE, code(1, 1, 'a', 1, 0, 31));
		assertReason(Reason.OUT_OF_RANGE, code(1, 1, 'a', 1, 4, 2));
		assertReason(Reason.OUT_OF_RANGE, code(1, 1, 'a', 1, 27, 2));
		assertReason(Reason.OUT_OF_RANGE, code(1, 1, 'a', 1, 3, 27));
		assertReason(null, code(1, 1, 'a', 1, 3, 26));
		assertReason(Reason.OUT_OF_RANGE, code(1, 1, 'a', 1, 19, 33));
		assertReason(Reason.DUPLICATE_KEY, code(1, 1, 'a', 2, 0, 10, 0, 11));
		assertReason(Reason.DUPLICATE_KEY, code(1, 1, 'a', 2, 0xC8, 0x01, 1, 0xC8, 0x01, 1));
		assertReason(Reason.TRAILING_BYTES, code(1, 1, 'a', 1, 0, 10, 0));
		assertReason(Reason.TRUNCATED, code(1, 1, 'a', 2, 0, 10));
		assertReason(Reason.TRAILING_BYTES, code(1, 1, 'a', 1, 0, 10, 1, 1));
		assertReason(Reason.BAD_COUNT, code(1, 1, 'a', 0));
		assertReason(Reason.BAD_COUNT, code(1, 1, 'a', 65));
		assertReason(Reason.BAD_FORMAT, code(2, 1, 'a', 1, 0, 10));
		assertReason(Reason.BAD_NAME, code(1, 65));
		assertReason(Reason.BAD_NAME, code(1, 1, 0xFF, 1, 0, 10));
		assertReason(Reason.BAD_NAME, code(1, 2, 0xC0, 0xAF, 1, 0, 10));
		assertReason(Reason.BAD_NAME, code(1, 3, 0xED, 0xA0, 0x80, 1, 0, 10));
		assertReason(Reason.TRUNCATED, code(1, 5, 'a', 'b'));
	}

	@Test
	void unknownKeysAreSkippedAndCounted() throws ShareCodeException {
		ShareCode.Decoded decoded = ShareCode.decode(code(1, 1, 'a', 3, 0, 10, 30, 5, 0x96, 0x01, 1));
		assertEquals(1, decoded.entries().size());
		assertEquals(2, decoded.unknownKeys());
		assertEquals("12", decoded.values(-1).get("vanilla.renderDistance"));
	}

	@Test
	void malformedTextIsRejectedWithTheRightReason() {
		String golden = ShareCodeTest.GOLDEN_VANILLA;
		assertReason(Reason.BAD_CHARACTERS, golden + "=");
		assertReason(Reason.BAD_CHARACTERS, golden.substring(0, 10) + "+" + golden.substring(11));
		assertReason(Reason.BAD_CHARACTERS, golden.substring(0, 10) + "/" + golden.substring(11));
		assertReason(Reason.NEWER, "RT2-" + golden.substring(4));
		assertReason(Reason.NEWER, "RT10-" + golden.substring(4));
		assertReason(Reason.NOT_A_CODE, "RT01-" + golden.substring(4));
		assertReason(Reason.NOT_A_CODE, "RTX-" + golden.substring(4));
		assertReason(Reason.NOT_A_CODE, "hello");
		assertReason(Reason.NOT_A_CODE, "");
		assertReason(Reason.TRUNCATED, "RT1-AAAAAAA");
		assertReason(Reason.BAD_BASE64, "RT1-AAAAAAAAA");
		// Non-zero spare bits in the final quantum: the same bytes have exactly one code.
		String payload = golden.substring(4);
		char last = payload.charAt(payload.length() - 1);
		int value = B64.indexOf(last);
		int spareMask = payload.length() % 4 == 2 ? 0x0F : payload.length() % 4 == 3 ? 0x03 : 0;
		assertTrue(spareMask != 0, "the golden code ends in a partial quantum");
		String spare = golden.substring(0, golden.length() - 1) + B64.charAt(value | 1);
		assertReason(Reason.BAD_BASE64, spare);
	}

	@Test
	void oversizedInputIsRejectedBeforeDecodingAndFast() {
		String rawTooLong = "RT1-" + "A".repeat(ShareCode.MAX_RAW_CHARS);
		String strippedTooLong = "RT1-" + "A".repeat(ShareCode.MAX_CODE_CHARS);
		String paddedButShort = " ".repeat(ShareCode.MAX_RAW_CHARS - ShareCodeTest.GOLDEN_VANILLA.length()) + ShareCodeTest.GOLDEN_VANILLA;
		assertReason(Reason.TOO_LONG, rawTooLong);
		assertReason(Reason.TOO_LONG, strippedTooLong);
		assertReason(null, paddedButShort);
		for (int warmup = 0; warmup < 200; warmup++) {
			rejects(rawTooLong);
			rejects(strippedTooLong);
		}
		long slowest = 0;
		for (int run = 0; run < 1000; run++) {
			for (String input : new String[] {rawTooLong, strippedTooLong}) {
				long start = System.nanoTime();
				rejects(input);
				slowest = Math.max(slowest, System.nanoTime() - start);
			}
		}
		assertTrue(slowest < 5_000_000L, "slowest reject took " + slowest / 1000 + " us");
	}

	private static void rejects(String input) {
		try {
			ShareCode.decode(input);
			fail("accepted");
		} catch (ShareCodeException expected) {
			if (expected.reason() != Reason.TOO_LONG) {
				fail(expected);
			}
		}
	}

	// reason null: decodes.
	private static void assertReason(Reason reason, String code) {
		try {
			ShareCode.decode(code);
			if (reason != null) {
				fail("accepted " + code + ", expected " + reason);
			}
		} catch (ShareCodeException e) {
			if (reason == null) {
				fail("rejected " + code + ": " + e.getMessage());
			}
			assertEquals(reason, e.reason(), code + ": " + e.getMessage());
		}
	}

	private static String code(int... body) {
		byte[] bytes = new byte[body.length];
		for (int i = 0; i < body.length; i++) {
			bytes[i] = (byte) body[i];
		}
		return "RT1-" + ShareCode.base64(withCrc(bytes));
	}

	private static byte[] withCrc(byte[] body) {
		CRC32 crc = new CRC32();
		crc.update(body);
		long v = crc.getValue();
		byte[] out = Arrays.copyOf(body, body.length + 4);
		out[body.length] = (byte) (v >>> 24);
		out[body.length + 1] = (byte) (v >>> 16);
		out[body.length + 2] = (byte) (v >>> 8);
		out[body.length + 3] = (byte) v;
		return out;
	}
}
