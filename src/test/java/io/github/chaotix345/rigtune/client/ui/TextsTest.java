package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.3/SPEC.md item 9: the client shows a Text's translation when the language has the key, else the English
// fallback, which must read exactly as core's english() (the String fields next to it and the share report).
class TextsTest {
	static final List<Text> SAMPLES = List.of(
			Text.literal("Sodium 0.9.2 (50% of %s)"),
			Text.of("rigtune.rec.install.title", "Install %s", "Sodium"),
			Text.of("rigtune.rec.update.reason", "Version %s is available (you have %s).", "2.0", null),
			Text.of("k", "%2$s then %1$s %s", "a", "b"),
			Text.of("k", "1%% low of %s", 60),
			Text.of("k", "%d is not a format Minecraft knows", 3),
			Text.of("k", "%s and %s", "one"),
			Text.of("outer", "RigTune doesn't also offer %s, which conflict with it.", Text.of("inner", "%s or %s", "A, B", "C")),
			Text.sentences(Text.literal("Faster."), Text.of("k", "RigTune doesn't also offer %s.", "X"), Text.of("n", "(alpha build)")),
			Text.join("; ", Text.literal("a would be missing b"), Text.of("t", "mod %s would be loaded twice (%s)", "c", "c-1.jar, c-2.jar")));

	@Test
	void withoutATranslationTheClientShowsCoresEnglish() {
		for (Text text : SAMPLES) {
			assertEquals(text.english(), Texts.component(text).getString(), text.toString());
		}
	}

	@Test
	void aTranslationWinsWhereTheLanguageHasTheKey() {
		Language previous = Language.getInstance();
		Language.inject(new Language() {
			@Override
			public String getOrDefault(String key, String fallback) {
				return switch (key) {
					case "rigtune.rec.install.title" -> "%s installieren";
					case "inner" -> "%s oder %s";
					default -> fallback;
				};
			}

			@Override
			public boolean has(String key) {
				return key.equals("rigtune.rec.install.title") || key.equals("inner");
			}

			@Override
			public boolean isDefaultRightToLeft() {
				return false;
			}

			@Override
			public FormattedCharSequence getVisualOrder(FormattedText text) {
				return previous.getVisualOrder(text);
			}
		});
		try {
			assertEquals("Sodium installieren", Texts.component(SAMPLES.get(1)).getString());
			assertEquals("RigTune doesn't also offer A, B oder C, which conflict with it.", Texts.component(SAMPLES.get(7)).getString());
			assertEquals("Version 2.0 is available (you have null).", Texts.component(SAMPLES.get(2)).getString());
		} finally {
			Language.inject(previous);
		}
	}
}
