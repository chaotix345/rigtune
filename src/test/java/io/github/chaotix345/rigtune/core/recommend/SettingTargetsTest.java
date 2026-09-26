package io.github.chaotix345.rigtune.core.recommend;

import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender.Clamp;
import io.github.chaotix345.rigtune.core.recommend.Recommender.SettingTarget;
import io.github.chaotix345.rigtune.core.rules.EvalContext;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The settingTargets extraction (plan review X-M3): the targets recommend() builds its `set:` recommendations from.
class SettingTargetsTest {
	@Test
	void everySetRecommendationIsATargetThatDiffersFromTheSnapshot() throws IOException {
		RulesDocument rules = RecommenderGoldenReportTest.goldenRules();
		for (Fixtures.Hw hw : RecommenderGoldenReportTest.hardware().values()) {
			List<String> mods = List.of("sodium", "distanthorizons", "iris");
			for (Goal goal : Goal.values()) {
				SettingsSnapshot snapshot = RecommenderGoldenReportTest.snapshot(mods, true);
				EvalContext ctx = Recommender.context(rules, hw.build(), Fixtures.mods(mods.toArray(String[]::new)), snapshot, goal);
				Map<String, SettingTarget> targets = Recommender.settingTargets(rules, ctx, snapshot);
				List<Recommendation> sets = Recommender.recommend(rules, hw.build(), Fixtures.mods(mods.toArray(String[]::new)), snapshot,
						OnlineData.offline(), goal).recommendations().stream().filter(r -> r.id().startsWith("set:")).toList();
				for (Recommendation rec : sets) {
					Action.SetSetting set = (Action.SetSetting) rec.action();
					assertEquals(targets.get(set.key()).value(), set.newValue(), set.key());
					assertEquals(targets.get(set.key()).reason(), rec.reason(), set.key());
				}
				long differing = targets.entrySet().stream().filter(e -> snapshot.has(e.getKey())
						&& !SettingValues.same(snapshot.get(e.getKey()), e.getValue().value())).count();
				assertEquals(differing, sets.size(), goal + " " + hw.build().gpu().renderer());
			}
		}
	}

	@Test
	void clampsApplyToTheTargetOrTheSnapshotAndAreReported() {
		EvalContext ctx = Recommender.context(new RulesDocument(), Fixtures.userRig().build(), List.of(),
				new SettingsSnapshot(Map.of()), Goal.BALANCED);
		Map<String, SettingTarget> targets = new LinkedHashMap<>();
		targets.put("vanilla.renderDistance", new SettingTarget("16", "Far.", io.github.chaotix345.rigtune.core.model.Impact.HIGH, true));
		List<Clamp> applied = new ArrayList<>();
		SettingsSnapshot snapshot = new SettingsSnapshot(Map.of("vanilla.simulationDistance", "12", "vanilla.particles", "0"));
		Recommender.applyClamps(targets, List.of(clamp("vanilla.renderDistance", null, 8.0, "Small heap."),
				clamp("vanilla.simulationDistance", null, 6.0, "Battery."), clamp("vanilla.particles", 1.0, null, "Fewer.")), ctx, snapshot, applied);
		assertEquals("8", targets.get("vanilla.renderDistance").value());
		assertEquals("Far. Small heap.", targets.get("vanilla.renderDistance").reason());
		assertEquals("6", targets.get("vanilla.simulationDistance").value());
		assertEquals("1", targets.get("vanilla.particles").value());
		assertEquals(List.of(new Clamp("vanilla.renderDistance", "16", "8", "Small heap."),
				new Clamp("vanilla.simulationDistance", "12", "6", "Battery."), new Clamp("vanilla.particles", "0", "1", "Fewer.")), applied);
	}

	@Test
	void callerTokensResolveAndUnknownTokensSkipTheEntry() {
		EvalContext ctx = Recommender.context(new RulesDocument(), Fixtures.userRig().build(), List.of(),
				new SettingsSnapshot(Map.of()), Goal.BALANCED);
		List<SettingRule> rules = List.of(value("vanilla.maxFps", "$recordingFps"), value("vanilla.renderDistance", "$nope"),
				value("vanilla.weatherRadius", "$refreshRateCap"));
		Map<String, SettingTarget> values = Recommender.settingValues(rules, ctx, Map.of("$recordingFps", "60"));
		assertEquals("60", values.get("vanilla.maxFps").value());
		assertFalse(values.containsKey("vanilla.renderDistance"));
		assertEquals("170", values.get("vanilla.weatherRadius").value());
		assertTrue(Recommender.settingValues(rules, ctx, Map.of()).containsKey("vanilla.weatherRadius"));
		assertFalse(Recommender.settingValues(rules, ctx, Map.of()).containsKey("vanilla.maxFps"));
	}

	private static SettingRule clamp(String key, Double min, Double max, String reason) {
		SettingRule rule = new SettingRule();
		rule.key = key;
		rule.min = min;
		rule.max = max;
		rule.reason = reason;
		return rule;
	}

	private static SettingRule value(String key, String value) {
		SettingRule rule = new SettingRule();
		rule.key = key;
		rule.value = new JsonPrimitive(value);
		return rule;
	}
}
