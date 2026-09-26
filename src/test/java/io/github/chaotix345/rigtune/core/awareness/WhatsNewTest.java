package io.github.chaotix345.rigtune.core.awareness;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.awareness.WhatsNew.Kind.ADVANCED;
import static io.github.chaotix345.rigtune.core.awareness.WhatsNew.Kind.NEW;
import static io.github.chaotix345.rigtune.core.awareness.WhatsNew.Kind.NOTHING;
import static io.github.chaotix345.rigtune.core.awareness.WhatsNew.Kind.SEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 9 (AC9.6, plan review W-L3): revision unchanged -> nothing; bumped without a baseline -> nothing,
// seeded; bumped with one -> the right difference, limited to appliable recommendations and warning/critical advice the
// new rules can produce and the old couldn't; dismissal updates the baseline.
class WhatsNewTest {
	@TempDir
	Path config;

	private static final String RULES_13 = """
			{"schemaVersion": 2, "revision": 13,
			 "mods": [{"slug": "sodium", "modIds": ["sodium"], "title": "Sodium", "projectId": "AANobbMI", "recommendWhen": {"always": true}},
			          {"slug": "lithium", "modIds": ["lithium"], "title": "Lithium", "projectId": "gvQqBUqZ", "recommendWhen": {"always": false},
			           "avoidWhen": {"always": false}, "conflictsWith": ["canary"]}],
			 "obsolete": [{"modIds": ["indium"]}],
			 "settings": [{"key": "vanilla.renderDistance", "value": 12, "when": {"goal": ["quality"]}}],
			 "advice": [{"id": "old-warning", "kind": "warning", "when": {"always": false}, "title": "Old", "text": "x"}]}
			""";
	private static final String RULES_14 = """
			{"schemaVersion": 2, "revision": 14,
			 "mods": [{"slug": "sodium", "modIds": ["sodium"], "title": "Sodium", "projectId": "AANobbMI", "recommendWhen": {"always": true}},
			          {"slug": "lithium", "modIds": ["lithium"], "title": "Lithium", "projectId": "gvQqBUqZ", "recommendWhen": {"always": false},
			           "avoidWhen": {"always": false}, "conflictsWith": ["canary"]},
			          {"slug": "ferrite-core", "modIds": ["ferritecore"], "title": "FerriteCore", "projectId": "uXXizFIs", "recommendWhen": {"always": true}}],
			 "obsolete": [{"modIds": ["indium"]}],
			 "settings": [{"key": "vanilla.renderDistance", "value": 12, "when": {"always": true}},
			              {"key": "vanilla.particles", "value": "1", "when": {"always": true}}],
			 "advice": [{"id": "old-warning", "kind": "warning", "when": {"always": false}, "title": "Old", "text": "x"},
			            {"id": "new-warning", "kind": "warning", "when": {"always": true}, "title": "New warning", "text": "x"},
			            {"id": "new-info", "when": {"always": true}, "title": "New info", "text": "x"}]}
			""";

	@Test
	void potentialIdsCoverEveryShape() {
		Set<String> ids = WhatsNew.potentialIds(RulesLoader.parse(RULES_13));
		assertEquals(Set.of("add:sodium", "add:lithium", "disable:lithium", "conflict:canary+lithium", "disable:indium",
				"set:vanilla.renderDistance", "advice:old-warning"), ids);
		RulesDocument byModId = RulesLoader.parse("""
				{"schemaVersion": 2, "revision": 1, "mods": [{"slug": "a", "modIds": ["a"], "conflictsWith": ["b-id", "a"]},
				 {"slug": "b", "modIds": ["b-id"]}]}
				""");
		assertTrue(WhatsNew.potentialIds(byModId).contains("conflict:a+b"), "a mod id reference resolves to its slug, as the Recommender does");
	}

	@Test
	void potentialIdsContainWhatTheRecommenderProduces() throws Exception {
		RulesDocument bundled = RulesLoader.loadBundled();
		Set<String> potential = WhatsNew.potentialIds(bundled);
		for (Goal goal : Goal.values()) {
			Report report = Recommender.recommend(bundled, Fixtures.lowEndLaptop().build(), Fixtures.mods("sodium", "distanthorizons", "iris", "indium"),
					new SettingsSnapshot(Map.of("vanilla.renderDistance", "32", "vanilla.simulationDistance", "32", "vanilla.maxFps", "260",
							"vanilla.enableVsync", "false", "vanilla.particles", "0")), OnlineData.offline(), goal);
			for (Recommendation r : report.recommendations()) {
				if (!r.id().startsWith("update:") && !r.id().startsWith("advice:update")) {
					assertTrue(potential.contains(r.id()), r.id() + " can come from the rules");
				}
			}
		}
		assertTrue(potential.size() <= WhatsNew.MAX_IDS, "the bundled rules' baseline fits: " + potential.size());
	}

	@Test
	void revisionUnchangedIsNothing() {
		WhatsNew.Baseline baseline = new WhatsNew.Baseline(14, Set.of());
		assertEquals(NOTHING, WhatsNew.compare(baseline, 14, recs14(), WhatsNew.potentialIds(RulesLoader.parse(RULES_14))).kind());
	}

	@Test
	void bumpedWithoutABaselineIsSeededSilently() {
		AwarenessStore store = AwarenessStore.shared(config);
		RulesDocument rules = RulesLoader.parse(RULES_14);
		WhatsNew.Result result = WhatsNew.check(store, report(rules, recs14()), rules);
		assertEquals(SEEDED, result.kind());
		assertTrue(result.fresh().isEmpty());
		WhatsNew.Baseline seeded = WhatsNew.Baseline.read(store.read());
		assertNotNull(seeded);
		assertEquals(14, seeded.revision());
		assertEquals(WhatsNew.potentialIds(rules), seeded.ids());
		assertEquals(NOTHING, WhatsNew.check(store, report(rules, recs14()), rules).kind());
	}

	@Test
	void bumpedWithABaselineGivesTheLimitedDifferenceAndStaysUntilDismissed() {
		AwarenessStore store = AwarenessStore.shared(config);
		RulesDocument r13 = RulesLoader.parse(RULES_13);
		RulesDocument r14 = RulesLoader.parse(RULES_14);
		assertTrue(WhatsNew.acknowledge(store, 13, WhatsNew.potentialIds(r13)));
		WhatsNew.Result result = WhatsNew.check(store, report(r14, recs14()), r14);
		assertEquals(NEW, result.kind());
		// add:ferrite-core (new rule, appliable), set:vanilla.particles (new key), advice:new-warning (new warning); not
		// set:vanilla.renderDistance (the r13 rules could produce it: only its condition changed), advice:new-info (info),
		// update:sodium (not from the rules), add:sodium (old rule).
		assertEquals(List.of("advice:new-warning", "add:ferrite-core", "set:vanilla.particles"), result.fresh().stream().map(Recommendation::id).toList());
		assertEquals(NEW, WhatsNew.check(store, report(r14, recs14()), r14).kind(), "stays until dismissed");
		assertEquals(13, WhatsNew.Baseline.read(store.read()).revision());
		assertTrue(WhatsNew.acknowledge(store, 14, WhatsNew.potentialIds(r14)));
		assertEquals(NOTHING, WhatsNew.check(store, report(r14, recs14()), r14).kind(), "dismissal updated the baseline");
	}

	@Test
	void bumpedWithNothingNewMovesTheBaselineOnSilently() {
		AwarenessStore store = AwarenessStore.shared(config);
		RulesDocument r13 = RulesLoader.parse(RULES_13);
		RulesDocument r14 = RulesLoader.parse(RULES_14);
		assertTrue(WhatsNew.acknowledge(store, 13, WhatsNew.potentialIds(r13)));
		List<Recommendation> old = List.of(rec("add:sodium", Category.ADD_MOD, new Action.AddMod("sodium", "AANobbMI", "Sodium")),
				rec("advice:new-info", Category.ADVICE, new Action.None()));
		assertEquals(ADVANCED, WhatsNew.check(store, report(r14, old), r14).kind());
		assertEquals(14, WhatsNew.Baseline.read(store.read()).revision());
	}

	@Test
	void aReportFromOtherRulesOrAReadOnlyFileShowsNothing() throws Exception {
		AwarenessStore store = AwarenessStore.shared(config);
		RulesDocument r14 = RulesLoader.parse(RULES_14);
		RulesDocument r13 = RulesLoader.parse(RULES_13);
		assertEquals(NOTHING, WhatsNew.check(store, report(r13, recs14()), r14).kind());
		assertNull(WhatsNew.Baseline.read(store.read()), "nothing written");
		Path file = AwarenessStore.file(config);
		java.nio.file.Files.createDirectories(file.getParent());
		java.nio.file.Files.writeString(file, "{\"formatVersion\": 9, \"lastSeenRulesRevision\": 1, \"lastSeenRecommendationIds\": []}");
		AwarenessStore newer = AwarenessStore.shared(config);
		assertEquals(NOTHING, WhatsNew.check(newer, report(r14, recs14()), r14).kind());
	}

	@Test
	void baselineReadIsDefensive() {
		assertNull(WhatsNew.Baseline.read(com.google.gson.JsonParser.parseString("{\"lastSeenRulesRevision\": \"13\", \"lastSeenRecommendationIds\": []}").getAsJsonObject()));
		assertNull(WhatsNew.Baseline.read(com.google.gson.JsonParser.parseString("{\"lastSeenRulesRevision\": 13.5, \"lastSeenRecommendationIds\": []}").getAsJsonObject()));
		assertNull(WhatsNew.Baseline.read(com.google.gson.JsonParser.parseString("{\"lastSeenRulesRevision\": 13}").getAsJsonObject()));
		WhatsNew.Baseline b = WhatsNew.Baseline.read(com.google.gson.JsonParser.parseString(
				"{\"lastSeenRulesRevision\": 13, \"lastSeenRecommendationIds\": [\"a\", 5, null, {\"x\": 1}, \"b\"]}").getAsJsonObject());
		assertEquals(new WhatsNew.Baseline(13, Set.of("a", "b")), b);
	}

	private static List<Recommendation> recs14() {
		return List.of(
				rec("advice:new-warning", Category.WARNING, new Action.None()),
				rec("add:sodium", Category.ADD_MOD, new Action.AddMod("sodium", "AANobbMI", "Sodium")),
				rec("add:ferrite-core", Category.ADD_MOD, new Action.AddMod("ferrite-core", "uXXizFIs", "FerriteCore")),
				rec("update:sodium", Category.UPDATE_MOD, new Action.None()),
				rec("set:vanilla.renderDistance", Category.SETTING, new Action.SetSetting("vanilla.renderDistance", "8", "12")),
				rec("set:vanilla.particles", Category.SETTING, new Action.SetSetting("vanilla.particles", "0", "1")),
				rec("advice:new-info", Category.ADVICE, new Action.None())).stream()
				.sorted(java.util.Comparator.comparing(Recommendation::category)).toList();
	}

	private static Recommendation rec(String id, Category category, Action action) {
		return new Recommendation(id, category, Impact.MEDIUM, id, "reason", action, false);
	}

	private static Report report(RulesDocument rules, List<Recommendation> recs) {
		return new Report(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, null), new TierResult(5, 5, 5, 5, 5, "gpu"),
				Goal.BALANCED, recs, rules.revision, "bundled", false, Instant.now());
	}
}
