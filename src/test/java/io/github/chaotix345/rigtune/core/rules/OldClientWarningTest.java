package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4i, AC4i.1: the old-client warning's condition reaches exactly the released 0.2.0-0.4.0 clients (0.1.x
// gets it through rules-v1.json's override, RulesV1DifferentialTest) and never 0.5.0 or its pre-releases. Each old client
// is its own pinned code: 0.2.0 and 0.3.0 the v030 rules copies (core/rules is byte-identical in both), 0.4.0 the v040
// copies (ConditionEvaluator, EvalContext, Truth, the parser, ModScanner, and a Recommender stub with its `requires`
// check). Each context is built as that version's Recommender builds it: every scanned mod with a version goes into the
// version map (v0.2.0 Recommender.java:82-89, v0.3.0 :90-97, v0.4.0 Recommender.context :125-141), and the scanner keeps
// rigtune (v040 ModScannerPinTest). The released jars report "<mod_version>+mc<mc>".
class OldClientWarningTest {
	static final String ID = "old-client-launcher-mods";
	static final String WHEN = "{\"modVersion\": {\"rigtune\": \"<0.5.0-\"}}";
	static final List<String> V020 = List.of("0.2.0+mc26.2");
	static final List<String> V030 = List.of("0.3.0+mc26.2", "0.3.0+mc26.3");
	static final List<String> V040 = List.of("0.4.0+mc26.2", "0.4.0+mc26.3");
	static final List<String> V050 = List.of("0.5.0+mc26.2", "0.5.0+mc26.3", "0.5.0-dev", "0.5.0-dev+mc26.2", "0.5.0-alpha.1+mc26.2",
			"0.5.0-beta.1+mc26.3", "0.5.0-rc.1+mc26.2", "0.5.1+mc26.2", "0.6.0+mc26.4", "1.0.0+mc27.1");

	static String doc(String when) {
		return "{\"schemaVersion\": 2, \"revision\": 1, \"advice\": [{\"id\": \"" + ID + "\", \"when\": " + when
				+ ", \"kind\": \"warning\", \"title\": \"t\", \"text\": \"x\"}]}";
	}

	static Map<String, String> versions(String rigtune) {
		Map<String, String> out = new HashMap<>(Map.of("sodium", "0.9.2+mc26.2", "fabric-api", "0.161.0+26.2"));
		if (rigtune != null) {
			out.put("rigtune", rigtune);
		}
		return out;
	}

	static HardwareProfile hardware() {
		return Fixtures.userRig().build();
	}

	static final GpuClass GPU = new GpuClass(GpuVendor.AMD, false, 5, null);
	static final TierResult TIER = new TierResult(5, 5, 5, 5, 5, "gpu");

	static boolean v030(String when, String rigtune) {
		var rules = io.github.chaotix345.rigtune.v030.core.rules.RulesLoader.parse(doc(when));
		var rule = rules.advice.getFirst();
		var ctx = new io.github.chaotix345.rigtune.v030.core.rules.EvalContext(hardware(), GPU, TIER, Goal.BALANCED, versions(rigtune).keySet(),
				versions(rigtune), new SettingsSnapshot(Map.of()));
		return io.github.chaotix345.rigtune.v030.core.recommend.Recommender.supported(rule.requires)
				&& io.github.chaotix345.rigtune.v030.core.rules.ConditionEvaluator.matches(rule.when, ctx);
	}

	// As 0.4.0's RulesLoader.parse reads a document (v040 LegacyParserTest).
	static boolean v040(String when, String rigtune) {
		var rule = io.github.chaotix345.rigtune.v040.core.rules.V040Parser.parse(doc(when)).advice.getFirst();
		var ctx = new io.github.chaotix345.rigtune.v040.core.rules.EvalContext(hardware(), GPU, TIER, Goal.BALANCED, versions(rigtune).keySet(),
				versions(rigtune), new SettingsSnapshot(Map.of()));
		return io.github.chaotix345.rigtune.v040.core.recommend.Recommender.supported(rule.requires)
				&& io.github.chaotix345.rigtune.v040.core.rules.ConditionEvaluator.matches(rule.when, ctx);
	}

	static boolean current(String when, String rigtune) {
		var rule = RulesLoader.parse(doc(when)).advice.getFirst();
		var ctx = new EvalContext(hardware(), GPU, TIER, Goal.BALANCED, versions(rigtune).keySet(), versions(rigtune), new SettingsSnapshot(Map.of()));
		return Recommender.supported(rule.requires) && ConditionEvaluator.matches(rule.when, ctx);
	}

	// The deciding test (AC4i.1): TRUE on each released old client, FALSE on 0.5.0 and every pre-release or later version.
	@Test
	void theConditionReachesExactlyTheReleasedOldClients() {
		for (String version : V020) {
			assertTrue(v030(WHEN, version), "0.2.0: " + version);
		}
		for (String version : V030) {
			assertTrue(v030(WHEN, version), "0.3.0: " + version);
		}
		for (String version : V040) {
			assertTrue(v040(WHEN, version), "0.4.0: " + version);
		}
		for (String version : V050) {
			assertFalse(current(WHEN, version), "0.5+: " + version);
			assertFalse(v040(WHEN, version), "the same answer on 0.4.0's evaluator: " + version);
			assertFalse(v030(WHEN, version), "the same answer on 0.3.0's evaluator: " + version);
		}
		assertFalse(current(WHEN, null), "no rigtune in the list (a listed mod that isn't loaded is FALSE)");
		assertFalse(v040(WHEN, null));
		assertFalse(v030(WHEN, null));
	}

	@Test
	void theBundledRuleIsTheReachTestedOne() {
		RulesDocument rules = RulesLoader.loadBundled();
		RulesDocument.AdviceRule rule = rules.advice.stream().filter(a -> ID.equals(a.id)).findFirst().orElseThrow();
		assertEquals(Map.of("rigtune", "<0.5.0-"), rule.when.modVersion);
		assertTrue(rule.when.unknownFields == null || rule.when.unknownFields.isEmpty());
		assertNull(rule.requires, "no feature: 0.2.0-0.4.0 must not skip it");
		assertEquals("warning", rule.kind);
		JsonObject json = advice(bundledJson(), ID);
		assertEquals(Set.of("id", "when", "kind", "impact", "title", "text"), json.keySet());
		assertEquals(JsonParser.parseString(WHEN), json.get("when"));
		var legacy = io.github.chaotix345.rigtune.v030.core.rules.RulesLoader.parse(bundledJson()).advice.stream().filter(a -> ID.equals(a.id))
				.findFirst().orElseThrow();
		assertTrue(legacy.when.unknownFields == null || legacy.when.unknownFields.isEmpty(), "0.2.0/0.3.0 know every key of it");
	}

	// Through the whole main list: an old client gets the warning, a 0.5 client's list is the same with and without the rule.
	@Test
	void aFiveClientsMainListIsUnchanged() {
		RulesDocument rules = RulesLoader.loadBundled();
		JsonObject root = JsonParser.parseString(bundledJson()).getAsJsonObject();
		JsonArray advice = new JsonArray();
		root.getAsJsonArray("advice").forEach(a -> {
			if (!ID.equals(a.getAsJsonObject().get("id").getAsString())) {
				advice.add(a);
			}
		});
		root.add("advice", advice);
		RulesDocument without = RulesLoader.parse(root.toString());
		for (Fixtures.Hw hw : List.of(Fixtures.userRig(), Fixtures.lowEndLaptop())) {
			for (Goal goal : Goal.values()) {
				for (String version : V050) {
					assertEquals(recommend(without, hw, version, goal).stream().map(Recommendation::toString).toList(),
							recommend(rules, hw, version, goal).stream().map(Recommendation::toString).toList(), version + " " + goal);
				}
				for (String version : V040) {
					assertTrue(recommend(rules, hw, version, goal).stream().anyMatch(r -> r.id().equals("advice:" + ID)), version);
				}
			}
		}
	}

	static List<Recommendation> recommend(RulesDocument rules, Fixtures.Hw hw, String rigtune, Goal goal) {
		List<InstalledMod> mods = new ArrayList<>();
		versions(rigtune).forEach((id, version) -> mods.add(new InstalledMod(id, id, version, Path.of("mods", id + ".jar"), "0000" + id)));
		return Recommender.recommend(rules, hw.build(), mods, new SettingsSnapshot(Map.of("vanilla.renderDistance", "12")), OnlineData.offline(), goal)
				.recommendations();
	}

	static String bundledJson() {
		try {
			return LegacyRulesParseTest.bundledJson();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	static JsonObject advice(String json, String id) {
		for (var a : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("advice")) {
			if (id.equals(a.getAsJsonObject().get("id").getAsString())) {
				return a.getAsJsonObject();
			}
		}
		throw new AssertionError("no advice " + id);
	}
}
