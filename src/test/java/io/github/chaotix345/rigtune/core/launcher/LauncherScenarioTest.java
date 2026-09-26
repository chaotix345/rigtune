package io.github.chaotix345.rigtune.core.launcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.2 (docs/v0.3/SPEC.md item 5, C-L1): the bundled rules' ram-* advice in the four reference setups gets the right
// launcher line for each launcher, and none for Unknown or for any other recommendation.
class LauncherScenarioTest {
	private static final String SHADERS = "shaders-enabled";

	private record Scenario(String name, Fixtures.Hw hw, List<String> mods, Set<String> expectedRamAdvice) {
	}

	private static Fixtures.Hw hw(long ramMb, long heapMb, String... flags) {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.ramMb = ramMb;
		hw.heapMb = heapMb;
		hw.flags = Set.of(flags);
		return hw;
	}

	private static List<Scenario> scenarios() {
		Fixtures.Hw huge = hw(32768, 16384);
		// ram-huge is for older CPUs (cpuTierAtMost 3): a Xeon E5 is tier 3 in the bundled table.
		huge.cpu = new CpuInfo("Intel(R) Xeon(R) CPU E5-2680 v4 @ 2.40GHz", 14, 28, 3300);
		return List.of(
				new Scenario("8 GB system, 2 GB heap", hw(8192, 2048), List.of("sodium"), Set.of("ram-low")),
				new Scenario("16 GB, 2 GB, Distant Horizons", hw(16384, 2048), List.of("sodium", "distanthorizons"),
						Set.of("ram-low", "ram-distant-horizons")),
				new Scenario("32 GB, 6 GB, Distant Horizons + shaders", hw(32768, 6144, SHADERS), List.of("sodium", "iris", "distanthorizons"),
						Set.of("ram-distant-horizons-shaders")),
				new Scenario("32 GB, 16 GB", huge, List.of("sodium"), Set.of("ram-huge")));
	}

	private static List<Recommendation> recommend(Scenario scenario) {
		return Recommender.recommend(RulesLoader.loadBundled(), scenario.hw().build(), Fixtures.mods(scenario.mods().toArray(String[]::new)),
				new SettingsSnapshot(Map.of()), OnlineData.offline(), Goal.BALANCED).recommendations();
	}

	private static Map<LauncherInfo, String> launchers() {
		Map<LauncherInfo, String> expected = new LinkedHashMap<>();
		expected.put(LauncherInfo.of(Launcher.PRISM), "rigtune.launcher.steps.prism");
		expected.put(LauncherInfo.of(Launcher.MODRINTH_APP), "rigtune.launcher.steps.modrinth_app");
		expected.put(LauncherInfo.of(Launcher.ATLAUNCHER), "rigtune.launcher.steps.atlauncher");
		expected.put(LauncherInfo.of(Launcher.OFFICIAL), "rigtune.launcher.steps.official");
		expected.put(new LauncherInfo(Launcher.CURSEFORGE, true), "rigtune.launcher.steps.curseforge.pack");
		expected.put(new LauncherInfo(Launcher.CURSEFORGE, null), "rigtune.launcher.steps.curseforge.pack");
		expected.put(new LauncherInfo(Launcher.CURSEFORGE, false), "rigtune.launcher.steps.curseforge.global");
		return expected;
	}

	@Test
	void everyKnownLauncherGetsItsLineUnderEveryRamAdvice() {
		for (Scenario scenario : scenarios()) {
			List<Recommendation> recs = recommend(scenario);
			Set<String> ramAdvice = recs.stream().filter(LauncherAdvice::isRamAdvice).map(r -> r.id().substring("advice:".length()))
					.collect(Collectors.toSet());
			assertEquals(scenario.expectedRamAdvice(), ramAdvice, scenario.name() + ": " + recs.stream().map(Recommendation::id).toList());
			assertTrue(recs.size() > ramAdvice.size(), scenario.name() + ": other recommendations too, so the negative case is checked");
			for (Map.Entry<LauncherInfo, String> launcher : launchers().entrySet()) {
				for (Recommendation r : recs) {
					String key = LauncherAdvice.stepsKey(r, launcher.getKey());
					if (LauncherAdvice.isRamAdvice(r)) {
						assertEquals(launcher.getValue(), key, scenario.name() + " / " + launcher.getKey() + " / " + r.id());
					} else {
						assertNull(key, scenario.name() + " / " + launcher.getKey() + " / " + r.id());
					}
				}
			}
			for (Recommendation r : recs) {
				assertNull(LauncherAdvice.stepsKey(r, LauncherInfo.UNKNOWN), scenario.name() + " / Unknown / " + r.id());
			}
		}
	}

	@Test
	void everyLauncherIsCovered() {
		Set<Launcher> covered = launchers().keySet().stream().map(LauncherInfo::launcher).collect(Collectors.toSet());
		for (Launcher launcher : Launcher.values()) {
			assertTrue(launcher == Launcher.UNKNOWN || covered.contains(launcher), launcher.name());
		}
	}

	@Test
	void onlyAdviceIdsStartingWithRamCount() {
		Recommendation ram = new Recommendation("advice:ram-anything", Category.ADVICE, Impact.LOW, "t", "r", new Action.None(), false);
		assertTrue(LauncherAdvice.isRamAdvice(ram));
		for (String id : List.of("advice:ramp", "advice:software-rendering", "set:vanilla.ram-low", "ram-low", "advice:update-queued:ram-x")) {
			Recommendation other = new Recommendation(id, ram.category(), ram.impact(), "t", "r", ram.action(), false);
			assertFalse(LauncherAdvice.isRamAdvice(other), id);
		}
	}

	@Test
	void everyLauncherKeyIsTranslated() throws IOException {
		JsonObject lang;
		try (InputStream in = LauncherScenarioTest.class.getResourceAsStream("/assets/rigtune/lang/en_us.json")) {
			assertNotNull(in, "en_us.json on the classpath");
			lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		Map<String, Integer> keys = new LinkedHashMap<>();
		keys.put("rigtune.launcher.advice", 2);
		keys.put("rigtune.launcher.header.cpu", 2);
		keys.put("rigtune.launcher.header.memory", 3);
		for (Launcher launcher : Launcher.values()) {
			for (Boolean override : new Boolean[]{null, true, false}) {
				LauncherInfo info = new LauncherInfo(launcher, override);
				if (info.known()) {
					keys.put(info.nameKey(), 0);
					keys.put(info.stepsKey(), 0);
					keys.put(info.jvmStepsKey(), 0);
				} else {
					assertNull(info.nameKey());
					assertNull(info.stepsKey());
					assertNull(info.jvmStepsKey());
				}
			}
		}
		List<String> launcherKeys = new ArrayList<>(lang.keySet().stream().filter(k -> k.startsWith("rigtune.launcher.")).toList());
		for (Map.Entry<String, Integer> key : keys.entrySet()) {
			assertTrue(lang.has(key.getKey()), "missing " + key.getKey());
			String text = lang.get(key.getKey()).getAsString();
			assertEquals((int) key.getValue(), text.split("%s", -1).length - 1, key.getKey() + ": " + text);
			launcherKeys.remove(key.getKey());
		}
		assertTrue(launcherKeys.isEmpty(), "unused rigtune.launcher keys: " + launcherKeys);
	}
}
