package io.github.chaotix345.rigtune.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

// review-8 SE-2: rule-sourced and other outside text is made inert before Minecraft's font draws it.
class SafeTextTest {
	@Test
	void formattingCodesGoWithTheirCode() {
		assertEquals("Free FPS!", SafeText.clean("§c§lFree §kFPS§r!"));
		assertEquals("end", SafeText.clean("end§"));
	}

	@Test
	void bidiZeroWidthAndControlCharactersGo() {
		assertEquals("Update gpj.exe", SafeText.clean("Update gpj\u200B.\u2066exe\u2069"));
		assertEquals("abc", SafeText.clean("a\u202Eb\u200Fc\uFEFF"));
		assertEquals("ab", SafeText.clean("a\u0000\u001B\u007Fb"));
		assertEquals("a", SafeText.clean("a\uE000\uD800"));
	}

	@Test
	void lineBreaksAndTabsBecomeSpaces() {
		assertEquals("one two  three four", SafeText.clean("one\ntwo\r\nthree\tfour"));
		assertEquals("a b c", SafeText.clean("a\u2028b\u2029c"));
	}

	@Test
	void ordinaryTextIsUntouched() {
		String text = "Sodium 0.9.2 · 1% low → 60 FPS ● naïve 日本語 Ελληνικά (50% of %s)";
		assertSame(text, SafeText.clean(text), "nothing to change: the same string");
		assertEquals("", SafeText.clean(null));
	}
}
