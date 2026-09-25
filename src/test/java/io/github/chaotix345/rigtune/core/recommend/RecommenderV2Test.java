package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommenderV2Test {
	private static final String BASE = """
			"schemaVersion":2,"revision":1,
			"gpuVendorFallback":{"amd":5,"nvidia":5},
			"cpuTiers":[{"pattern":"(?i)ryzen","tier":5}],
			"heapTiers":[{"atLeastMb":0,"tier":5}]
			""";
	private static final String EVERY_KIND = """
			"mods":[
			 {"slug":"addme","projectId":"p1","title":"Add Me","modIds":["addme"],"reason":"r","recommendWhen":{"always":true}REQ},
			 {"slug":"avoidme","projectId":"p2","title":"Avoid Me","modIds":["avoidme"],"reason":"r","recommendWhen":{"always":false},"avoidWhen":{"always":true}REQ},
			 {"slug":"clash","projectId":"p3","title":"Clash","modIds":["clash"],"reason":"r","recommendWhen":{"always":false},"conflictsWith":["sodium"]REQ}
			],
			"obsolete":[{"modIds":["oldmod"],"title":"Old","reason":"gone"REQ}],
			"settings":[
			 {"key":"vanilla.renderDistance","value":20,"reason":"v"REQ},
			 {"key":"vanilla.simulationDistance","max":5,"reason":"c"REQ}
			],
			"advice":[{"id":"tip","title":"Tip","text":"t","when":{"always":true}REQ}]""";
	private static final Set<String> EVERY_KIND_IDS = Set.of("add:addme", "disable:avoidme", "conflict:clash+sodium", "disable:oldmod",
			"set:vanilla.renderDistance", "set:vanilla.simulationDistance", "advice:tip");
	private static final Map<String, String> SETTINGS = Map.of("vanilla.renderDistance", "12", "vanilla.simulationDistance", "12",
			"sodium.performance.chunk_builder_threads", "0");

	private static RulesDocument rules(String body) {
		return RulesLoader.parse("{" + BASE + "," + body + "}");
	}

	private static Map<String, Recommendation> run(RulesDocument rules, Fixtures.Hw hw, List<InstalledMod> mods) {
		return Recommender.recommend(rules, hw.build(), mods, new SettingsSnapshot(SETTINGS), OnlineData.offline(), Goal.BALANCED)
				.recommendations().stream().collect(Collectors.toMap(Recommendation::id, r -> r));
	}

	private static Map<String, Recommendation> run(String body, String... installed) {
		return run(rules(body), Fixtures.userRig(), Fixtures.mods(installed));
	}

	private static InstalledMod mod(String id, String version) {
		return new InstalledMod(id, id, version, Path.of("mods", id + ".jar"), "0000" + id);
	}

	@Test
	void ruleRequiringAnUnknownFeatureIsSkippedForEveryKind() {
		Map<String, Recommendation> skipped = run(EVERY_KIND.replace("REQ", ",\"requires\":[\"future-feature\"]"),
				"avoidme", "clash", "sodium", "oldmod");
		assertTrue(skipped.keySet().stream().noneMatch(EVERY_KIND_IDS::contains), skipped.keySet().toString());

		Map<String, Recommendation> kept = run(EVERY_KIND.replace("REQ", ""), "avoidme", "clash", "sodium", "oldmod");
		assertTrue(kept.keySet().containsAll(EVERY_KIND_IDS), kept.keySet().toString());
	}

	@Test
	void conflictReferencesStillResolveRulesThatRequiresSkipped() {
		Map<String, Recommendation> recs = run("""
				"mods":[
				 {"slug":"c2me-fabric","projectId":"p1","title":"C2ME","modIds":["c2me"],"reason":"r","recommendWhen":{"always":false},"requires":["future"]},
				 {"slug":"other","projectId":"p2","title":"Other","modIds":["other"],"reason":"r","recommendWhen":{"always":true},"conflictsWith":["c2me-fabric"]}
				]""", "c2me");
		assertFalse(recs.containsKey("add:other"), recs.keySet().toString());
	}

	@Test
	void emptyRequiresIsAllowed() {
		Map<String, Recommendation> recs = run(EVERY_KIND.replace("REQ", ",\"requires\":[]"), "avoidme", "clash", "sodium", "oldmod");
		assertTrue(recs.keySet().containsAll(EVERY_KIND_IDS), recs.keySet().toString());
	}

	@Test
	void noFeaturesAreSupportedYet() {
		assertTrue(Recommender.SUPPORTED_FEATURES.isEmpty());
	}

	@Test
	void avoidSelectedFalseGivesAnUntickedDisable() {
		Recommendation rec = run("""
				"mods":[{"slug":"ldl","projectId":"p","title":"LDL","modIds":["lambdynlights"],"reason":"r",
				 "recommendWhen":{"always":false},"avoidWhen":{"tierAtMost":5},"avoidSelected":false}]""", "lambdynlights")
				.get("disable:lambdynlights");
		assertFalse(rec.selectedByDefault());
		assertEquals(new Action.DisableMod("lambdynlights", Path.of("mods", "lambdynlights.jar")), rec.action());
	}

	@Test
	void avoidSelectedDefaultsToTicked() {
		Recommendation rec = run("""
				"mods":[{"slug":"ldl","projectId":"p","title":"LDL","modIds":["lambdynlights"],"reason":"r",
				 "recommendWhen":{"always":false},"avoidWhen":{"tierAtMost":5}}]""", "lambdynlights")
				.get("disable:lambdynlights");
		assertTrue(rec.selectedByDefault());
	}

	@Test
	void poisonedAvoidWhenDisablesNothing() {
		Map<String, Recommendation> recs = run("""
				"mods":[{"slug":"avoidme","projectId":"p","modIds":["avoidme"],"reason":"r","recommendWhen":{"always":false},
				 "avoidWhen":{"not":{"futureKey":true}}}]""", "avoidme");
		assertFalse(recs.containsKey("disable:avoidme"));
	}

	@Test
	void unknownAvoidWhenBlocksTheAddition() {
		String body = """
				"mods":[{"slug":"addme","projectId":"p","modIds":["addme"],"reason":"r","recommendWhen":{"always":true},
				 "avoidWhen":{"not":{"gpuModelMatches":"(?i)rtx"}}}]""";
		assertFalse(run(rules(body), Fixtures.userRig().gpu("", ""), List.of()).containsKey("add:addme"));
		assertFalse(run(rules(body), Fixtures.userRig(), List.of()).containsKey("add:addme"));
		assertTrue(run(rules(body), Fixtures.userRig().gpu("NVIDIA Corporation", "NVIDIA GeForce RTX 3070"), List.of()).containsKey("add:addme"));
	}

	@Test
	void poisonedRecommendWhenDoesNotAdd() {
		assertFalse(run("""
				"mods":[{"slug":"addme","projectId":"p","modIds":["addme"],"reason":"r","recommendWhen":{"always":true,"futureKey":1}}]""")
				.containsKey("add:addme"));
	}

	@Test
	void nullConditionsFireNothing() {
		Map<String, Recommendation> recs = run("""
				"mods":[{"slug":"addme","projectId":"p","modIds":["addme"],"reason":"r","recommendWhen":null},
				 {"slug":"avoidme","projectId":"p2","modIds":["avoidme"],"reason":"r","recommendWhen":{"always":false},"avoidWhen":null}],
				"settings":[{"key":"vanilla.renderDistance","value":20,"reason":"v","when":null}],
				"advice":[{"id":"tip","title":"Tip","text":"t","when":null}]""", "avoidme");
		assertTrue(recs.keySet().stream().noneMatch(id -> id.equals("add:addme") || id.equals("disable:avoidme")
				|| id.equals("set:vanilla.renderDistance") || id.equals("advice:tip")), recs.keySet().toString());
	}

	@Test
	void poisonedSettingEntriesAreSkipped() {
		Map<String, Recommendation> recs = run("""
				"settings":[
				 {"key":"vanilla.renderDistance","value":20,"reason":"v","when":{"futureKey":1}},
				 {"key":"vanilla.simulationDistance","max":5,"reason":"c","when":{"not":{"futureKey":1}}}
				]""");
		assertFalse(recs.containsKey("set:vanilla.renderDistance"));
		assertFalse(recs.containsKey("set:vanilla.simulationDistance"));
	}

	@Test
	void poisonedAdviceIsNotShown() {
		assertFalse(run("""
				"advice":[{"id":"tip","title":"Tip","text":"t","when":{"anyOf":[{"always":true},{"futureKey":1}]}}]""")
				.containsKey("advice:tip"));
	}

	@Test
	void labelsAreUsedInSettingTitles() {
		Recommendation rec = run("""
				"settings":[{"key":"sodium.performance.chunk_builder_threads","value":4,"reason":"threads"}],
				"settingLabels":{"sodium.performance.chunk_builder_threads":{"name":"Chunk builder threads","values":{"0":"Auto"}}}""")
				.get("set:sodium.performance.chunk_builder_threads");
		assertEquals("Chunk builder threads: Auto → 4", rec.title());
		assertEquals(new Action.SetSetting("sodium.performance.chunk_builder_threads", "0", "4"), rec.action());
	}

	@Test
	void valueLabelsMatchNormalisedValues() {
		Recommendation rec = run("""
				"settings":[{"key":"vanilla.renderDistance","value":16,"reason":"r"}],
				"settingLabels":{"vanilla.renderDistance":{"values":{"12.0":"Twelve","16":"Sixteen"}}}""")
				.get("set:vanilla.renderDistance");
		assertEquals("Render distance: Twelve → Sixteen", rec.title());
	}

	@Test
	void missingLabelFallsBackToTheCaption() {
		Recommendation rec = run("""
				"settings":[{"key":"vanilla.renderDistance","value":20,"reason":"v"}],
				"settingLabels":{"vanilla.simulationDistance":{"name":"Other"}}""")
				.get("set:vanilla.renderDistance");
		assertEquals("Render distance: 12 → 20", rec.title());
	}

	@Test
	void modVersionConditionSeesInstalledVersions() {
		RulesDocument rules = rules("""
				"advice":[{"id":"new-sodium","title":"T","text":"t","when":{"modVersion":{"sodium":">=0.9"}}}]""");
		assertTrue(run(rules, Fixtures.userRig(), List.of(mod("sodium", "0.9.2"))).containsKey("advice:new-sodium"));
		assertFalse(run(rules, Fixtures.userRig(), List.of(mod("sodium", "0.8.0"))).containsKey("advice:new-sodium"));
		assertFalse(run(rules, Fixtures.userRig(), List.of(mod("sodium", null))).containsKey("advice:new-sodium"));
	}
}
