package io.github.chaotix345.rigtune.core.model;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md item 9: the English of a core-built Text is what Minecraft's TranslatableContents would show for the
// fallback template (javap-checked on 26.2 and 26.3).
class TextTest {
	@Test
	void formatsArgumentsInOrder() {
		assertEquals("Install Sodium", Text.of("k", "Install %s", "Sodium").english());
		assertEquals("Version 2 is available (you have 1).", Text.of("k", "Version %s is available (you have %s).", "2", "1").english());
		assertEquals("7 threads", Text.of("k", "%s threads", 7).english());
	}

	@Test
	void positionalArgumentsDontMoveTheCounter() {
		assertEquals("b then a", Text.of("k", "%2$s then %1$s", "a", "b").english());
		assertEquals("a a b", Text.of("k", "%s %1$s %s", "a", "b").english());
	}

	@Test
	void doublePercentIsAPercentSign() {
		assertEquals("1% low", Text.of("k", "1%% low").english());
		assertEquals("50% of 3", Text.of("k", "50%% of %s", 3).english());
	}

	@Test
	void aTemplateMinecraftCantFormatIsShownAsItIs() {
		assertEquals("%d items", Text.of("k", "%d items", 3).english());
		assertEquals("50% off", Text.of("k", "50% off").english());
		assertEquals("%s and %s", Text.of("k", "%s and %s", "one").english());
		assertEquals("%3$s", Text.of("k", "%3$s", "a").english());
		assertEquals("trailing %", Text.of("k", "trailing %").english());
	}

	@Test
	void nullArgumentsReadNull() {
		assertEquals("you have null", Text.of("k", "you have %s", (Object) null).english());
	}

	@Test
	void textArgumentsAreRenderedToo() {
		Text inner = Text.of("inner", "%s or %s", "A", "B");
		Text outer = Text.of("outer", "RigTune doesn't also offer %s.", inner);
		assertEquals("RigTune doesn't also offer A or B.", outer.english());
		Map<String, String> upper = Map.of("inner", "%s OR %s", "outer", "NOT %s");
		assertEquals("NOT A OR B", outer.render(upper::get));
	}

	@Test
	void aTranslationReplacesTheFallbackAndAMissingOneKeepsIt() {
		Text text = Text.of("rigtune.rec.install.title", "Install %s", "Sodium");
		assertEquals("Installer Sodium", text.render(key -> key.equals("rigtune.rec.install.title") ? "Installer %s" : null));
		assertEquals("Install Sodium", text.render(key -> null));
	}

	@Test
	void literalsAreNeverFormattedOrTranslated() {
		Text text = Text.literal("50% of %s");
		assertEquals("50% of %s", text.english());
		assertEquals("50% of %s", text.render(key -> "X"));
		assertEquals("", Text.literal(null).english());
		assertTrue(Text.literal(" ").isBlank());
	}

	@Test
	void joinDropsBlankPartsAndStripsLikeTheOldTrim() {
		Text joined = Text.sentences(Text.literal(""), Text.of("k", "(alpha build)"));
		assertEquals("(alpha build)", joined.english());
		assertEquals("a  b", Text.sentences(Text.literal(" a "), Text.literal("b")).english());
		assertEquals("x; y", Text.join("; ", List.of(Text.literal("x"), Text.literal("y"))).english());
		assertTrue(Text.sentences(Text.literal(""), Text.literal(" ")).isBlank());
		Text only = Text.sentences(Text.literal("Faster."));
		assertEquals("Faster.", only.english());
		assertFalse(only.isBlank());
	}

	@Test
	void argumentsAreKeptAsGiven() {
		Text.Translatable text = assertInstanceOf(Text.Translatable.class, Text.of("k", "%s %s", "a", Text.literal("b")));
		assertEquals("a", text.args().get(0));
		assertEquals(Text.literal("b"), text.args().get(1));
	}

	@Test
	void textExceptionsCarryTheirTextAndReadInEnglish() {
		TextException e = new TextException(Text.of("k", "No file for %s", "1.0"));
		assertInstanceOf(IOException.class, e);
		assertEquals("No file for 1.0", e.getMessage());
		assertEquals(Text.of("k", "No file for %s", "1.0"), e.text());
	}
}
