package io.github.chaotix345.rigtune;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.report.ShareReport;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.4/SPEC.md X4 (AC2j.2, external review §1 and §3): honest wording. No UI text or share report calls a component
// a bottleneck or says the game is "limited by" it (RigTune estimates tiers; it doesn't measure a limit), and what the
// stutter doctor, benchmark trends, change awareness and startup times say is a correlation, never a cause.
class WordingTest {
	private static final List<String> BOTTLENECK = List.of("limited by", "bottleneck");
	private static final List<String> CAUSAL = List.of("caused", "because of");
	private static final List<String> CORRELATION_PREFIXES = List.of("rigtune.stutter.", "rigtune.benchmark.trend.", "rigtune.awareness.", "rigtune.startup.");

	private static List<String> found(String text, List<String> words) {
		String lower = text.toLowerCase(Locale.ROOT);
		return words.stream().filter(lower::contains).toList();
	}

	@Test
	void noLanguageValueNamesABottleneckOrACause() throws IOException {
		JsonObject lang = JsonParser.parseString(Files.readString(RepoFiles.resolve("src/main/resources/assets/rigtune/lang/en_us.json"))).getAsJsonObject();
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : lang.entrySet()) {
			String value = entry.getValue().getAsString();
			found(value, BOTTLENECK).forEach(word -> problems.add(entry.getKey() + " says \"" + word + "\": " + value));
			if (CORRELATION_PREFIXES.stream().anyMatch(entry.getKey()::startsWith)) {
				found(value, CAUSAL).forEach(word -> problems.add(entry.getKey() + " says \"" + word + "\": " + value));
			}
		}
		assertEquals(List.of(), problems);
	}

	@Test
	void noShareReportNamesABottleneck() {
		Fixtures.Hw lowEnd = Fixtures.userRig().gpu("Intel", "Intel(R) Iris(R) Xe Graphics");
		lowEnd.cpu = new CpuInfo("11th Gen Intel(R) Core(TM) i5-1135G7 @ 2.40GHz", 4, 8, 4200);
		lowEnd.ramMb = 8192;
		lowEnd.heapMb = 2048;
		Fixtures.Hw unknown = Fixtures.userRig().gpu("Mystery Graphics Co.", "Mystery GPU 9000");
		unknown.cpu = new CpuInfo("Mystery CPU 9000", 8, 16, 4000);
		List<String> problems = new ArrayList<>();
		for (Fixtures.Hw hw : List.of(Fixtures.userRig(), lowEnd, unknown)) {
			for (Goal goal : Goal.values()) {
				Report report = Recommender.recommend(RulesLoader.loadBundled(), hw.build(), Fixtures.mods("sodium", "distanthorizons", "iris"),
						new SettingsSnapshot(Map.of()), OnlineData.offline(), goal);
				String text = ShareReport.format(report, new ShareReport.Versions("0.4.0-dev+mc26.2", "26.2", "0.19.5"), null, "Prism Launcher");
				found(text, BOTTLENECK).forEach(word -> problems.add(hw.cpu.name() + " / " + goal + ": \"" + word + "\" in\n" + text));
			}
		}
		assertEquals(List.of(), problems);
	}
}
