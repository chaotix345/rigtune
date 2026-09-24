package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommenderTest {
	private static final String BASE = """
			"schemaVersion":1,"revision":4,
			"gpuVendorFallback":{"amd":5},
			"cpuTiers":[{"pattern":"(?i)ryzen","tier":5}],
			"heapTiers":[{"atLeastMb":0,"tier":5}]
			""";

	private static RulesDocument rules(String body) {
		return RulesLoader.parse("{" + BASE + (body.isBlank() ? "" : "," + body) + "}");
	}

	private static Report run(RulesDocument rules, Fixtures.Hw hw, List<InstalledMod> mods, Map<String, String> settings, OnlineData online) {
		return Recommender.recommend(rules, hw.build(), mods, new SettingsSnapshot(settings), online, Goal.BALANCED);
	}

	private static Map<String, Recommendation> byId(Report report) {
		return report.recommendations().stream().collect(Collectors.toMap(Recommendation::id, r -> r));
	}

	@Test
	void lastMatchingValueWinsThenClampsApply() {
		RulesDocument rules = rules("""
				"settings":[
				 {"key":"vanilla.renderDistance","value":8,"when":{"always":true},"reason":"Base."},
				 {"key":"vanilla.renderDistance","value":20,"when":{"tierAtLeast":5},"reason":"Strong.","impact":"high"},
				 {"key":"vanilla.renderDistance","value":4,"when":{"tierAtMost":1},"reason":"Never."},
				 {"key":"vanilla.renderDistance","max":16,"when":{"always":true},"reason":"Clamped."},
				 {"key":"vanilla.renderDistance","max":12,"when":{"tierAtMost":1},"reason":"Not applied."}
				]""");
		Recommendation rec = byId(run(rules, Fixtures.userRig(), List.of(), Map.of("vanilla.renderDistance", "10"), OnlineData.offline()))
				.get("set:vanilla.renderDistance");
		assertEquals(new Action.SetSetting("vanilla.renderDistance", "10", "16"), rec.action());
		assertEquals("Strong. Clamped.", rec.reason());
		assertEquals(Impact.HIGH, rec.impact());
		assertEquals(Category.SETTING, rec.category());
	}

	@Test
	void clampAppliesToCurrentValueWithoutValueEntry() {
		RulesDocument rules = rules("""
				"settings":[{"key":"vanilla.renderDistance","max":8,"when":{"heapMbAtMost":99999},"reason":"Low memory."}]""");
		Recommendation rec = byId(run(rules, Fixtures.userRig(), List.of(), Map.of("vanilla.renderDistance", "24"), OnlineData.offline()))
				.get("set:vanilla.renderDistance");
		assertEquals("8", ((Action.SetSetting) rec.action()).newValue());
		assertEquals("Low memory.", rec.reason());
	}

	@Test
	void refreshRateTokens() {
		RulesDocument rules = rules("""
				"settings":[
				 {"key":"vanilla.maxFps","value":"$refreshRateCap","reason":"Cap."},
				 {"key":"vanilla.other","value":"$refreshRate","reason":"Rate."}
				]""");
		Map<String, String> settings = Map.of("vanilla.maxFps", "260", "vanilla.other", "1");
		Fixtures.Hw hw = Fixtures.userRig();
		hw.display = new DisplayInfo(1920, 1080, 144, true);
		Map<String, Recommendation> recs = byId(run(rules, hw, List.of(), settings, OnlineData.offline()));
		assertEquals("141", ((Action.SetSetting) recs.get("set:vanilla.maxFps").action()).newValue());
		assertEquals("144", ((Action.SetSetting) recs.get("set:vanilla.other").action()).newValue());

		hw.display = new DisplayInfo(1920, 1080, -1, true);
		recs = byId(run(rules, hw, List.of(), settings, OnlineData.offline()));
		assertEquals("57", ((Action.SetSetting) recs.get("set:vanilla.maxFps").action()).newValue());
		assertEquals("60", ((Action.SetSetting) recs.get("set:vanilla.other").action()).newValue());

		hw.display = new DisplayInfo(1920, 1080, 24, true);
		recs = byId(run(rules, hw, List.of(), settings, OnlineData.offline()));
		assertEquals("30", ((Action.SetSetting) recs.get("set:vanilla.maxFps").action()).newValue());
	}

	@Test
	void equalValuesAfterNormalisationAndUnknownKeysAreSkipped() {
		RulesDocument rules = rules("""
				"settings":[
				 {"key":"vanilla.entityDistanceScaling","value":1,"reason":"x"},
				 {"key":"vanilla.enableVsync","value":true,"reason":"x"},
				 {"key":"sodium.performance.chunk_build_defer_mode","value":"always","reason":"x"},
				 {"key":"sodium.performance.use_fog_occlusion","value":true,"reason":"x"},
				 {"key":"vanilla.renderClouds","value":"fast","reason":"x"}
				]""");
		Map<String, String> settings = Map.of(
				"vanilla.entityDistanceScaling", "1.0",
				"vanilla.enableVsync", "TRUE",
				"sodium.performance.chunk_build_defer_mode", "ALWAYS",
				"vanilla.renderClouds", "\"false\"");
		Report report = run(rules, Fixtures.userRig(), List.of(), settings, OnlineData.offline());
		assertEquals(List.of("set:vanilla.renderClouds"), report.recommendations().stream().map(Recommendation::id).toList());
		assertEquals("Render clouds: \"false\" → fast", report.recommendations().get(0).title());
	}

	@Test
	void settingLabels() {
		assertEquals("Render distance", SettingValues.label("vanilla.renderDistance"));
		assertEquals("Sodium chunk build defer mode", SettingValues.label("sodium.performance.chunk_build_defer_mode"));
	}

	@Test
	void modAdditionsHonourConditionsConflictsAndStability() {
		RulesDocument rules = rules("""
				"mods":[
				 {"slug":"fast","projectId":"P1","title":"Fast","modIds":["fast"],"impact":"high","stability":"stable","reason":"Quick.","recommendWhen":{"always":true}},
				 {"slug":"shiny","projectId":"P2","title":"Shiny","modIds":["shiny"],"impact":"low","stability":"alpha","reason":"Shiny.","recommendWhen":{"always":true}},
				 {"slug":"never","projectId":"P3","modIds":["never"],"reason":"No.","recommendWhen":{"always":false}},
				 {"slug":"avoided","projectId":"P4","modIds":["avoided"],"reason":"No.","recommendWhen":{"always":true},"avoidWhen":{"gpuVendor":["amd"]}},
				 {"slug":"clash","projectId":"P5","modIds":["clash"],"reason":"No.","recommendWhen":{"always":true},"conflictsWith":["other-mod"]},
				 {"slug":"optout","projectId":"P6","modIds":["optout"],"reason":"Maybe.","recommendWhen":{},"defaultSelected":false},
				 {"slug":"have","projectId":"P7","modIds":["have-a","have-b"],"reason":"Installed.","recommendWhen":{"always":true}}
				]""");
		Map<String, Recommendation> recs = byId(run(rules, Fixtures.userRig(), Fixtures.mods("other-mod", "have-b"), Map.of(), OnlineData.offline()));
		assertEquals(List.of("add:fast", "add:optout", "add:shiny"), recs.keySet().stream().sorted().toList());

		Recommendation fast = recs.get("add:fast");
		assertEquals(new Action.AddMod("fast", "P1", "Fast"), fast.action());
		assertEquals(Impact.HIGH, fast.impact());
		assertEquals("Install Fast", fast.title());
		assertTrue(fast.selectedByDefault());

		Recommendation shiny = recs.get("add:shiny");
		assertFalse(shiny.selectedByDefault());
		assertTrue(shiny.reason().contains(Recommender.ALPHA_NOTE));
		assertFalse(recs.get("add:optout").selectedByDefault());
	}

	@Test
	void avoidWhenDisablesInstalledMod() {
		RulesDocument rules = rules("""
				"mods":[{"slug":"nv","projectId":"P","title":"NV Only","modIds":["nv"],"impact":"high","reason":"x",
				 "recommendWhen":{"gpuVendor":["nvidia"]},"avoidWhen":{"not":{"gpuVendor":["nvidia"]}},"avoidReason":"Needs NVIDIA."}]""");
		Recommendation rec = byId(run(rules, Fixtures.userRig(), Fixtures.mods("nv"), Map.of(), OnlineData.offline())).get("disable:nv");
		assertEquals(Category.REMOVE_MOD, rec.category());
		assertEquals("Needs NVIDIA.", rec.reason());
		assertEquals(new Action.DisableMod("nv", Path.of("mods", "nv.jar")), rec.action());
	}

	@Test
	void conflictIdIsSortedAndDeduplicated() {
		RulesDocument rules = rules("""
				"mods":[
				 {"slug":"zeta","projectId":"Z","title":"Zeta","modIds":["zeta"],"reason":"x","conflictsWith":["alpha"]},
				 {"slug":"alpha","projectId":"A","title":"Alpha","modIds":["alpha_mod"],"reason":"x","conflictsWith":["zeta"]}
				]""");
		List<Recommendation> conflicts = run(rules, Fixtures.userRig(), Fixtures.mods("zeta", "alpha_mod"), Map.of(), OnlineData.offline())
				.recommendations().stream().filter(r -> r.category() == Category.WARNING).toList();
		assertEquals(1, conflicts.size());
		assertEquals("conflict:alpha+zeta", conflicts.get(0).id());
		assertFalse(conflicts.get(0).appliable());
	}

	@Test
	void updatesComeFromOnlineData() {
		UpdateInfo update = new UpdateInfo("lithium", "gvQqBUqZ", "0.25.2", "v2", "0.25.3", new ModFile("https://cdn", "lithium.jar", "abc", 10));
		OnlineData online = new OnlineData(true, Map.of(), Map.of("lithium", update, "ghost", update));
		Map<String, Recommendation> recs = byId(run(rules(""), Fixtures.userRig(), Fixtures.mods("lithium"), Map.of(), online));
		Recommendation rec = recs.get("update:lithium");
		assertEquals(Category.UPDATE_MOD, rec.category());
		assertEquals(Impact.LOW, rec.impact());
		assertEquals(new Action.UpdateMod("lithium", Path.of("mods", "lithium.jar"), update), rec.action());
		assertTrue(rec.reason().contains("0.25.3"));
		assertFalse(recs.containsKey("update:ghost"));
	}

	@Test
	void obsoleteModsSkipUpdates() {
		UpdateInfo update = new UpdateInfo("indium", "x", "1", "v", "2", new ModFile("u", "f", "h", 1));
		RulesDocument rules = rules("""
				"obsolete":[{"modIds":["indium"],"title":"Indium","reason":"Built into Sodium."}]""");
		Map<String, Recommendation> recs = byId(run(rules, Fixtures.userRig(), Fixtures.mods("indium"), Map.of(),
				new OnlineData(true, Map.of(), Map.of("indium", update))));
		assertTrue(recs.containsKey("disable:indium"));
		assertFalse(recs.containsKey("update:indium"));
	}

	@Test
	void adviceKindsMapToCategoriesAndImpacts() {
		RulesDocument rules = rules("""
				"advice":[
				 {"id":"a","when":{},"kind":"critical","impact":"low","title":"Critical","text":"t"},
				 {"id":"b","when":{},"kind":"warning","impact":"medium","title":"Warn","text":"t"},
				 {"id":"c","when":{},"kind":"info","title":"Info","text":"t"},
				 {"id":"d","when":{"always":false},"kind":"info","title":"Hidden","text":"t"}
				]""");
		Map<String, Recommendation> recs = byId(run(rules, Fixtures.userRig(), List.of(), Map.of(), OnlineData.offline()));
		assertEquals(Category.WARNING, recs.get("advice:a").category());
		assertEquals(Impact.HIGH, recs.get("advice:a").impact());
		assertEquals(Category.WARNING, recs.get("advice:b").category());
		assertEquals(Impact.MEDIUM, recs.get("advice:b").impact());
		assertEquals(Category.ADVICE, recs.get("advice:c").category());
		assertEquals(Impact.LOW, recs.get("advice:c").impact());
		assertFalse(recs.containsKey("advice:d"));
	}

	@Test
	void sortedByCategoryImpactThenTitle() {
		RulesDocument rules = RulesLoader.loadBundled();
		List<InstalledMod> mods = Fixtures.mods("indium", "c2me", "moonrise");
		Report report = Recommender.recommend(rules, Fixtures.lowEndLaptop().build(), mods,
				new SettingsSnapshot(Map.of("vanilla.renderDistance", "32", "vanilla.particles", "0")), OnlineData.offline(), Goal.PERFORMANCE);
		List<Recommendation> recs = report.recommendations();
		Comparator<Recommendation> order = Comparator.comparing(Recommendation::category)
				.thenComparing(Recommendation::impact)
				.thenComparing(Recommendation::title, String.CASE_INSENSITIVE_ORDER);
		for (int i = 1; i < recs.size(); i++) {
			assertTrue(order.compare(recs.get(i - 1), recs.get(i)) <= 0, recs.get(i - 1).id() + " before " + recs.get(i).id());
		}
		assertEquals(Category.WARNING, recs.get(0).category());
		assertEquals(Category.ADVICE, recs.get(recs.size() - 1).category());
	}

	@Test
	void reportsRulesRevisionAndOldRigTuneVersion() {
		RulesDocument rules = rules("\"minModVersion\":\"0.2.0\"");
		Report old = Recommender.recommend(rules, Fixtures.userRig().build(), List.of(), new SettingsSnapshot(Map.of()), null, Goal.BALANCED, "0.1.9");
		assertEquals(4, old.rulesRevision());
		assertEquals("unknown", old.rulesSource());
		assertInstanceOf(Action.None.class, byId(old).get("advice:update-rigtune").action());
		Report current = Recommender.recommend(rules, Fixtures.userRig().build(), List.of(), new SettingsSnapshot(Map.of()), null, Goal.BALANCED, "0.2.0");
		assertFalse(byId(current).containsKey("advice:update-rigtune"));
		assertTrue(Recommender.compareVersions("0.10.0", "0.9.9") > 0);
		assertEquals(0, Recommender.compareVersions("1.0", "1.0.0"));
	}
}
