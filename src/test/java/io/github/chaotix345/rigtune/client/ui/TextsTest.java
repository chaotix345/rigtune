package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
			Text.join("; ", Text.literal("a would be missing b"), Text.of("t", "mod %s would be loaded twice (%s)", "c", "c-1.jar, c-2.jar")),
			Text.sentences(Text.literal(" Edges kept "), Text.of("n", "(alpha build)")),
			new Text.Joined(" ", List.of(Text.literal(""), Text.of("b", "It is bundled inside another mod."))),
			Text.of("k", "%99999999999$s", "a"));

	@Test
	void withoutATranslationTheClientShowsCoresEnglish() {
		for (Text text : SAMPLES) {
			assertEquals(text.english(), Texts.component(text).getString(), text.toString());
		}
	}

	// review-8 SE-2: literal data (rule titles and texts, names) and string arguments are drawn without formatting codes or
	// bidi controls, which Minecraft's font would otherwise apply.
	@Test
	void outsideTextIsInert() {
		String rlo = String.valueOf((char) 0x202E);
		assertEquals("Free FPS", Texts.component(Text.literal("§cFree " + rlo + "FPS")).getString());
		assertEquals("Install Sodium", Texts.component(Text.of("rigtune.rec.install.title", "Install %s", "§kSodium")).getString());
		assertEquals("a b", SafeLiteral.of("a" + (char) 10 + "b").getString());
		// review-9 SE2-RESIDUAL: a benchmark step's failure message comes from another mod's exception.
		assertEquals("No pack red", BenchmarkResultScreen.reason(SessionResult.NOT_MEASURED_FAILED + "No pack §cred" + rlo).getString());
	}

	// RigTune's en_us.json as the game's language, for client code that uses Component.translatable.
	static Language rigtuneEnglish(Language base) throws IOException {
		Map<String, String> lang = new HashMap<>();
		try (InputStream in = Files.newInputStream(RepoFiles.resolve("src/main/resources/assets/rigtune/lang/en_us.json"))) {
			Language.loadFromJson(in, lang::put);
		}
		return new Language() {
			@Override
			public String getOrDefault(String key, String fallback) {
				return lang.containsKey(key) ? lang.get(key) : base.getOrDefault(key, fallback);
			}

			@Override
			public boolean has(String key) {
				return lang.containsKey(key) || base.has(key);
			}

			@Override
			public boolean isDefaultRightToLeft() {
				return false;
			}

			@Override
			public FormattedCharSequence getVisualOrder(FormattedText text) {
				return base.getVisualOrder(text);
			}
		};
	}

	// docs/v0.3/SPEC.md AC9.4 at unit level: the header's units read exactly as the String code did.
	@Test
	void unitsAndTheDisplayReadAsBefore() throws IOException {
		Language previous = Language.getInstance();
		Language.inject(rigtuneEnglish(previous));
		try {
			for (long mb : new long[]{0, -1, 1, 2048, 6144, 10239, 10240, 16384, 32768, 12345}) {
				double gb = mb / 1024.0;
				String old = mb <= 0 ? "?" : gb >= 10 ? Math.round(gb) + " GB" : String.format(Locale.ROOT, "%.1f GB", gb);
				assertEquals(old, RigTuneScreen.gb(mb).getString(), Long.toString(mb));
			}
			for (DisplayInfo d : List.of(new DisplayInfo(2560, 1440, 180, true), new DisplayInfo(1920, 1080, 0, false), new DisplayInfo(0, 0, 60, true))) {
				String old = d.width() > 0 ? d.width() + "×" + d.height() + (d.refreshRate() > 0 ? " @ " + d.refreshRate() + " Hz" : "") : "?";
				assertEquals(old, RigTuneScreen.display(d).getString(), d.toString());
			}
		} finally {
			Language.inject(previous);
		}
	}

	// The Preview's "Not changed" lines show the title and the planner's reason from their Texts; in English as before.
	@Test
	void previewLinesReadAsBefore() throws IOException {
		Language previous = Language.getInstance();
		Language.inject(rigtuneEnglish(previous));
		try {
			Text title = Text.of("rigtune.rec.update.title", "Update %s", "Sodium");
			Text detail = Text.of("rigtune.download.target_exists", "%s is already in the mods folder", "sodium-0.6.jar");
			ApplyPreview.Skipped refused = new ApplyPreview.Skipped("update:sodium", title.english(), ApplyPreview.Reason.DOWNLOAD_FAILED, detail.english(),
					title, detail);
			assertEquals(Component.translatable("rigtune.preview.skipped.detail", refused.title(), refused.detail()).getString(),
					PreviewScreen.skipped(refused).getString());
			ApplyPreview.Skipped unchanged = new ApplyPreview.Skipped("set:x", "Render distance: 12 → 8", ApplyPreview.Reason.UNCHANGED, null);
			assertEquals("Render distance: 12 → 8: already set", PreviewScreen.skipped(unchanged).getString());
		} finally {
			Language.inject(previous);
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
