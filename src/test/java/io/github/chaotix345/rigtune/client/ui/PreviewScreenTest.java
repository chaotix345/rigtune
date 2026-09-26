package io.github.chaotix345.rigtune.client.ui;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.4/SPEC.md 2b (AC2b.1): Preview names and values settings as History does, from the rules' settingLabels, not
// the file's raw key and value.
class PreviewScreenTest {
	private static final RulesDocument RULES = RulesLoader.loadBundled();
	// The labels History uses for a mod's key (GameState.label/value without the game's captions).
	private static final HistoryModel.Labels LABELS = new HistoryModel.Labels() {
		@Override
		public String label(String key) {
			return SettingValues.name(RULES.settingLabels.get(key), key);
		}

		@Override
		public String value(String key, String value) {
			return SettingValues.valueLabel(RULES.settingLabels.get(key), value);
		}
	};

	private static String english(Component text) throws IOException {
		TranslatableContents t = (TranslatableContents) text.getContents();
		List<Object> args = new ArrayList<>();
		for (Object arg : t.getArgs()) {
			args.add(arg instanceof Component c ? c.getContents() instanceof TranslatableContents ? english(c) : c.getString() : arg);
		}
		JsonObject lang = JsonParser.parseString(Files.readString(RepoFiles.resolve("src/main/resources/assets/rigtune/lang/en_us.json"))).getAsJsonObject();
		return lang.get(t.getKey()).getAsString().formatted(args.toArray());
	}

	private static ApplyPreview.Setting setting(String keyInFile, String before, String after) {
		return new ApplyPreview.Setting("r", Path.of("x"), keyInFile, before, after);
	}

	@Test
	void aLabelledKeyReadsAsTheMainListDescribesIt() throws IOException {
		assertEquals(SettingValues.describe(RULES.settingLabels.get("vanilla.particles"), "vanilla.particles", "0", "1").english(),
				english(PreviewScreen.settingRow(setting("particles", "0", "1"), "vanilla.", LABELS)));
		assertEquals("Particles: All → Decreased", english(PreviewScreen.settingRow(setting("particles", "0", "1"), "vanilla.", LABELS)));
		String deferMode = "sodium.performance.chunk_build_defer_mode";
		assertEquals(SettingValues.describe(RULES.settingLabels.get(deferMode), deferMode, "ZERO_FRAMES", "ALWAYS").english(),
				english(PreviewScreen.settingRow(setting("performance.chunk_build_defer_mode", "ZERO_FRAMES", "ALWAYS"), "sodium.", LABELS)));
	}

	@Test
	void anUnlabelledKeyFallsBackToTheCaptionFromItsName() throws IOException {
		assertEquals("Sodium: Some new option: 1 → 2", english(PreviewScreen.settingRow(setting("performance.some_new_option", "1", "2"), "sodium.", LABELS)));
	}

	@Test
	void anAbsentValueAndAFileRigTuneDoesNotKnowStayAsBefore() throws IOException {
		assertEquals("Iris: Max Shadow Distance: not set → 16",
				english(PreviewScreen.settingRow(setting("maxShadowRenderDistance", null, "16"), "iris.", LABELS)));
		assertEquals("particles: 0 → 1", english(PreviewScreen.settingRow(setting("particles", "0", "1"), null, LABELS)));
	}
}
