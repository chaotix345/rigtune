package io.github.chaotix345.rigtune.core.profile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.3 (the name half) and P-L1.
class ProfileNamesTest {
	private static final List<Integer> FORBIDDEN = List.of((int) '\n', (int) '\r', (int) '=', (int) '#', (int) '"', 0x202E, 0x200B, 0xFEFF,
			(int) '§', (int) '\\', 0x00, 0x07, 0x1B, 0x7F, 0x85, 0x2028, 0x2029, 0x2066, 0x2069, 0xE000, 0x061C);

	@Test
	void hostileNamesComeOutSanitised() {
		List<String> hostile = List.of("a\nb", "a\r\nb", "key=value", "#comment", "say \"hi\"", "‮evil.exe", "zero​width",
				"﻿bom", "§cred", "back\\slash", "nul\u0000bell\u0007esc\u001B", "del\u007F", "line para ",
				"iso⁦late⁩", "privateuse", "arabic؜mark", "x".repeat(200), "\uD800lone", "unassigned￿");
		for (String raw : hostile) {
			String clean = ProfileNames.sanitise(raw);
			if (clean == null) {
				continue;
			}
			assertTrue(clean.codePointCount(0, clean.length()) <= ProfileNames.MAX_CODE_POINTS, raw);
			clean.codePoints().forEach(cp -> assertFalse(FORBIDDEN.contains(cp), raw + " kept U+" + Integer.toHexString(cp)));
			clean.codePoints().forEach(cp -> assertFalse(Character.isISOControl(cp), raw));
			assertFalse(Character.isSurrogate(clean.charAt(0)) && clean.length() == 1, raw);
		}
		assertEquals("ab", ProfileNames.sanitise("a\nb"));
		assertEquals("keyvalue", ProfileNames.sanitise("key=value"));
		assertEquals("evil.exe", ProfileNames.sanitise("‮evil.exe"));
		assertEquals("x".repeat(32), ProfileNames.sanitise("x".repeat(200)));
		assertEquals("cred", ProfileNames.sanitise("§cred"));
	}

	@Test
	void whitespaceCollapsesAndEmptyIsNull() {
		assertEquals("My Battery", ProfileNames.sanitise("  My \t  Battery 　"));
		assertNull(ProfileNames.sanitise(" ​\n\t "));
		assertNull(ProfileNames.sanitise(""));
		assertNull(ProfileNames.sanitise(null));
		assertEquals("Charlie's Balanced", ProfileNames.sanitise("Charlie's Balanced"));
	}

	@Test
	void namesAreNfc() {
		assertEquals("été", ProfileNames.sanitise("été"));
	}

	@Test
	void utf8FitKeepsWholeCodePoints() {
		assertEquals("é".repeat(32), ProfileNames.fitUtf8("é".repeat(32)));
		String emoji = "😀".repeat(20);
		assertEquals("😀".repeat(16), ProfileNames.fitUtf8(emoji));
	}
}
