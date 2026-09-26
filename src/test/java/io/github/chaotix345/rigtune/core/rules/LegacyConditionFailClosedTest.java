package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.v030.core.recommend.Recommender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/v0.4/SPEC.md "Compatibility promise" (a) and (c), AC9.3, AC6.3: on 0.2.0 and 0.3.0 (the pinned v030 classes, which
 * stand for both) every bundled rule whose condition uses driverVersion or a Stutter Doctor key evaluates UNKNOWN, so it
 * never fires; every jvm-* rule is skipped outright by its `requires`; and the stutterAdvice section is invisible (and would
 * fail closed if it weren't). The current code's own TRUE/FALSE for these keys comes with the evaluators (WS-W, WS-S, WS-J).
 */
class LegacyConditionFailClosedTest {
	private record Rule(String label, Condition current, io.github.chaotix345.rigtune.v030.core.rules.Condition legacy, List<String> requires) {
	}

	private static boolean uses(Condition c, Predicate<Condition> key) {
		if (c == null) {
			return false;
		}
		return key.test(c) || uses(c.not, key) || c.anyOf != null && c.anyOf.stream().anyMatch(sub -> uses(sub, key));
	}

	private static boolean usesDriverVersion(Condition c) {
		return uses(c, n -> n.driverVersion != null);
	}

	private static boolean usesStutterKey(Condition c) {
		return uses(c, ConditionEvaluator::hasStutterKey);
	}

	private static boolean usesJvmFlag(Condition c) {
		return uses(c, n -> n.flags != null && n.flags.stream().anyMatch(f -> f != null && f.startsWith("jvm-")));
	}

	// Every condition of every rule in the bundled rules, paired with 0.3.0's parse of the same rule.
	private static List<Rule> bundledRules() throws IOException {
		String json = LegacyRulesParseTest.bundledJson();
		RulesDocument current = RulesLoader.parse(json);
		var legacy = io.github.chaotix345.rigtune.v030.core.rules.RulesLoader.parse(json);
		List<Rule> out = new ArrayList<>();
		for (int i = 0; i < current.advice.size(); i++) {
			assertEquals(current.advice.get(i).id, legacy.advice.get(i).id);
			out.add(new Rule("advice " + current.advice.get(i).id, current.advice.get(i).when, legacy.advice.get(i).when, current.advice.get(i).requires));
		}
		for (int i = 0; i < current.settings.size(); i++) {
			assertEquals(current.settings.get(i).key, legacy.settings.get(i).key);
			out.add(new Rule("settings[" + i + "]", current.settings.get(i).when, legacy.settings.get(i).when, current.settings.get(i).requires));
		}
		for (int i = 0; i < current.mods.size(); i++) {
			RulesDocument.ModRule mod = current.mods.get(i);
			var old = legacy.mods.get(i);
			assertEquals(mod.slug, old.slug);
			out.add(new Rule("mods " + mod.slug + " recommendWhen", mod.recommendWhen, old.recommendWhen, mod.requires));
			out.add(new Rule("mods " + mod.slug + " avoidWhen", mod.avoidWhen, old.avoidWhen, mod.requires));
			out.add(new Rule("mods " + mod.slug + " skipUpdateWhen", mod.skipUpdateWhen, old.skipUpdateWhen, mod.requires));
		}
		return out;
	}

	// Hardware on which the seeds' other keys hold (Windows, the driver ranges, an HD 4000), so the new key decides.
	private static Map<String, io.github.chaotix345.rigtune.v030.core.rules.EvalContext> legacyContexts() {
		Map<String, io.github.chaotix345.rigtune.v030.core.rules.EvalContext> out = new LinkedHashMap<>();
		out.put("nvidia 531.18", legacyContext(new GpuInfo("NVIDIA Corporation", "NVIDIA GeForce RTX 3060/PCIe/SSE2", "4.6.0 NVIDIA 531.18",
				GraphicsBackend.OPENGL, 12288), new GpuClass(GpuVendor.NVIDIA, false, 4, null)));
		out.put("nvidia 560.94", legacyContext(new GpuInfo("NVIDIA Corporation", "NVIDIA GeForce RTX 3060/PCIe/SSE2", "4.6.0 NVIDIA 560.94",
				GraphicsBackend.OPENGL, 12288), new GpuClass(GpuVendor.NVIDIA, false, 4, null)));
		out.put("hd 4000 old driver", legacyContext(new GpuInfo("Intel", "Intel(R) HD Graphics 4000", "4.0.0 - Build 10.18.10.4358",
				GraphicsBackend.OPENGL, -1), new GpuClass(GpuVendor.INTEL, true, 1, null)));
		out.put("amd", legacyContext(Fixtures.userRig().gpu, new GpuClass(GpuVendor.AMD, false, 5, null)));
		return out;
	}

	private static io.github.chaotix345.rigtune.v030.core.rules.EvalContext legacyContext(GpuInfo gpu, GpuClass gpuClass) {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.gpu = gpu;
		hw.flags = Set.of("shaders-enabled");
		HardwareProfile profile = hw.build();
		return new io.github.chaotix345.rigtune.v030.core.rules.EvalContext(profile, gpuClass, new TierResult(3, 3, 3, 3, 3, "gpu"), Goal.BALANCED,
				Set.of("sodium", "iris", "distanthorizons"), Map.of(), new SettingsSnapshot(Map.of()));
	}

	@Test
	void everyDriverVersionRuleIsUnknownOnTheLegacyEvaluator() throws IOException {
		List<String> checked = new ArrayList<>();
		for (Rule rule : bundledRules()) {
			if (!usesDriverVersion(rule.current())) {
				continue;
			}
			checked.add(rule.label());
			assertTrue(rule.requires() == null || rule.requires().isEmpty(), rule.label() + ": SPEC 9 seeds carry no requires");
			for (var ctx : legacyContexts().entrySet()) {
				assertEquals(io.github.chaotix345.rigtune.v030.core.rules.Truth.UNKNOWN,
						io.github.chaotix345.rigtune.v030.core.rules.ConditionEvaluator.evaluate(rule.legacy(), ctx.getValue()), rule.label() + " on " + ctx.getKey());
			}
		}
		assertEquals(List.of("advice driver-nvidia-threaded-optimization", "advice driver-intel-gen7-old"), checked);
	}

	@Test
	void noMainListRuleUsesAStutterKey() throws IOException {
		for (Rule rule : bundledRules()) {
			assertFalse(usesStutterKey(rule.current()), rule.label());
		}
	}

	@Test
	void everyJvmRuleIsSkippedByTheLegacyRecommender() throws IOException {
		List<String> checked = new ArrayList<>();
		for (Rule rule : bundledRules()) {
			if (!usesJvmFlag(rule.current()) && !rule.label().startsWith("advice jvm-")) {
				continue;
			}
			checked.add(rule.label());
			assertTrue(rule.requires() != null && rule.requires().contains("jvm-flags"), rule.label());
			assertFalse(Recommender.supported(rule.requires()), rule.label());
			assertTrue(io.github.chaotix345.rigtune.core.recommend.Recommender.SUPPORTED_FEATURES.containsAll(rule.requires()), rule.label());
		}
		assertEquals(9, checked.size(), checked.toString());
	}

	// 0.2.0/0.3.0 never read stutterAdvice (no such field). Even read as ordinary advice, every entry fails closed there: its
	// requires names a feature they don't know, and its stutter keys poison the condition.
	@Test
	void stutterAdviceWouldFailClosedOnTheLegacyClasses() throws IOException {
		JsonObject bundled = JsonParser.parseString(LegacyRulesParseTest.bundledJson()).getAsJsonObject();
		JsonObject asAdvice = new JsonObject();
		asAdvice.addProperty("schemaVersion", 2);
		asAdvice.addProperty("revision", 1);
		asAdvice.add("advice", bundled.getAsJsonArray("stutterAdvice"));
		var legacy = io.github.chaotix345.rigtune.v030.core.rules.RulesLoader.parse(asAdvice.toString());
		assertEquals(5, legacy.advice.size());
		for (var rule : legacy.advice) {
			assertFalse(Recommender.supported(rule.requires), rule.id);
			assertFalse(io.github.chaotix345.rigtune.core.recommend.Recommender.SUPPORTED_FEATURES.contains("stutter-doctor"));
			assertFalse(rule.when.unknownFields.isEmpty(), rule.id);
			for (var ctx : legacyContexts().entrySet()) {
				assertEquals(io.github.chaotix345.rigtune.v030.core.rules.Truth.UNKNOWN,
						io.github.chaotix345.rigtune.v030.core.rules.ConditionEvaluator.evaluate(rule.when, ctx.getValue()), rule.id + " on " + ctx.getKey());
			}
		}
	}
}
