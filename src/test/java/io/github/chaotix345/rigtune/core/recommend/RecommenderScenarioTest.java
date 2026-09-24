package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommenderScenarioTest {
	private static final List<String> USER_MODS = List.of(
			"sodium", "lithium", "ferritecore", "modernfix", "immediatelyfast", "entityculling", "scalablelux", "c2me",
			"dynamic_fps", "iris", "distanthorizons",
			"fabric-api", "fabricloader", "minecraft", "java", "cloth-config", "modmenu", "yet_another_config_lib_v3", "xaerominimap",
			"xaeroworldmap", "jade", "appleskin", "continuity", "lambdynlights", "zoomify", "litematica", "malilib", "carpet");

	private static final Map<String, String> USER_SETTINGS = Map.ofEntries(
			Map.entry("vanilla.renderDistance", "12"),
			Map.entry("vanilla.simulationDistance", "12"),
			Map.entry("vanilla.maxFps", "170"),
			Map.entry("vanilla.enableVsync", "false"),
			Map.entry("vanilla.particles", "0"),
			Map.entry("vanilla.biomeBlendRadius", "2"),
			Map.entry("vanilla.entityDistanceScaling", "1.0"),
			Map.entry("vanilla.entityShadows", "true"),
			Map.entry("vanilla.renderClouds", "false"),
			Map.entry("vanilla.cutoutLeaves", "true"),
			Map.entry("vanilla.improvedTransparency", "false"),
			Map.entry("vanilla.textureFiltering", "1"),
			Map.entry("vanilla.weatherRadius", "10"),
			Map.entry("vanilla.inactivityFpsLimit", "afk"),
			Map.entry("vanilla.prioritizeChunkUpdates", "1"),
			Map.entry("vanilla.mipmapLevels", "4"),
			Map.entry("vanilla.graphicsPreset", "custom"),
			Map.entry("vanilla.preferredGraphicsBackend", "default"),
			Map.entry("sodium.quality.hidden_fluid_culling", "true"),
			Map.entry("sodium.performance.chunk_builder_threads", "0"),
			Map.entry("sodium.performance.chunk_build_defer_mode", "ALWAYS"),
			Map.entry("sodium.performance.animate_only_visible_textures", "true"),
			Map.entry("sodium.performance.use_entity_culling", "true"),
			Map.entry("sodium.performance.use_fog_occlusion", "true"),
			Map.entry("sodium.performance.use_block_face_culling", "true"),
			Map.entry("sodium.performance.use_no_error_g_l_context", "true"),
			Map.entry("sodium.performance.quad_splitting_mode", "SAFE"));

	private static final Map<String, String> LAPTOP_SETTINGS = Map.ofEntries(
			Map.entry("vanilla.renderDistance", "12"),
			Map.entry("vanilla.simulationDistance", "12"),
			Map.entry("vanilla.maxFps", "120"),
			Map.entry("vanilla.enableVsync", "false"),
			Map.entry("vanilla.particles", "0"),
			Map.entry("vanilla.biomeBlendRadius", "2"),
			Map.entry("vanilla.entityDistanceScaling", "1.0"),
			Map.entry("vanilla.entityShadows", "true"),
			Map.entry("vanilla.renderClouds", "true"),
			Map.entry("vanilla.cutoutLeaves", "true"),
			Map.entry("vanilla.textureFiltering", "0"),
			Map.entry("vanilla.inactivityFpsLimit", "afk"));

	private static Map<String, Recommendation> byId(Report report) {
		return report.recommendations().stream().collect(Collectors.toMap(Recommendation::id, r -> r));
	}

	private static Optional<Action.SetSetting> setting(Report report, String key) {
		return report.recommendations().stream()
				.filter(r -> r.id().equals("set:" + key))
				.map(r -> (Action.SetSetting) r.action())
				.findFirst();
	}

	private static Set<String> criticalAdviceIds(RulesDocument rules) {
		return rules.advice.stream().filter(a -> "critical".equals(a.kind)).map(a -> "advice:" + a.id).collect(Collectors.toSet());
	}

	@Test
	void userRig() {
		RulesDocument rules = RulesLoader.loadBundled();
		Report report = Recommender.recommend(rules, Fixtures.userRig().build(), Fixtures.mods(USER_MODS.toArray(String[]::new)),
				new SettingsSnapshot(USER_SETTINGS), OnlineData.offline(), Goal.BALANCED);
		Map<String, Recommendation> recs = byId(report);

		assertEquals(GpuVendor.AMD, report.gpuClass().vendor());
		assertFalse(report.gpuClass().integrated());
		assertEquals(5, report.gpuClass().tier());
		assertEquals(5, report.tier().cpuTier());
		assertEquals(5, report.tier().memTier());
		assertEquals(5, report.tier().effectiveTier());
		assertEquals("bundled", report.rulesSource());
		assertFalse(report.online());

		assertFalse(recs.containsKey("add:nvidium"));
		assertTrue(recs.containsKey("add:moreculling"));
		assertTrue(recs.containsKey("add:better-block-entities"));
		for (String installed : List.of("sodium", "lithium", "ferrite-core", "modernfix-mvus", "immediatelyfast", "entityculling",
				"scalablelux", "c2me-fabric", "dynamic-fps")) {
			assertFalse(recs.containsKey("add:" + installed), installed);
		}
		assertFalse(recs.containsKey("add:moonrise-opt"));
		assertFalse(recs.containsKey("add:vulkanmod"));

		assertFalse(setting(report, "vanilla.maxFps").isPresent(), "170 is already the 10-step cap for 180 Hz");
		assertFalse(setting(report, "vanilla.enableVsync").isPresent());
		assertFalse(setting(report, "vanilla.simulationDistance").isPresent());
		assertFalse(report.recommendations().stream().anyMatch(r -> r.id().startsWith("set:sodium.")));

		assertTrue(report.recommendations().stream().noneMatch(r -> criticalAdviceIds(rules).contains(r.id())));
		assertTrue(report.recommendations().stream().noneMatch(r -> r.category() == Category.WARNING && r.impact() == Impact.HIGH));
		assertTrue(report.recommendations().stream().noneMatch(r -> r.id().startsWith("disable:") || r.id().startsWith("conflict:")));
	}

	@Test
	void lowEndLaptopOnBattery() {
		Report report = Recommender.recommend(RulesLoader.loadBundled(), Fixtures.lowEndLaptop().build(), Fixtures.mods("fabric-api"),
				new SettingsSnapshot(LAPTOP_SETTINGS), OnlineData.offline(), Goal.BALANCED);
		Map<String, Recommendation> recs = byId(report);

		assertEquals(GpuVendor.INTEL, report.gpuClass().vendor());
		assertTrue(report.gpuClass().integrated());
		assertEquals(2, report.tier().effectiveTier());

		for (String slug : List.of("sodium", "lithium", "ferrite-core", "modernfix-mvus", "immediatelyfast", "entityculling", "dynamic-fps")) {
			Recommendation rec = recs.get("add:" + slug);
			assertTrue(rec != null, slug);
			assertEquals(Category.ADD_MOD, rec.category());
			assertTrue(rec.reason().contains(Recommender.AVAILABILITY_UNKNOWN_NOTE), slug);
			assertInstanceOf(Action.AddMod.class, rec.action());
		}
		assertEquals(Impact.HIGH, recs.get("add:sodium").impact());
		assertFalse(recs.containsKey("add:better-block-entities"));
		assertFalse(recs.containsKey("add:sodium-extra"));
		assertFalse(recs.containsKey("add:nvidium"));

		int renderDistance = Integer.parseInt(setting(report, "vanilla.renderDistance").orElseThrow().newValue());
		assertTrue(renderDistance <= 8, "render distance " + renderDistance);
		assertEquals("60", setting(report, "vanilla.maxFps").orElseThrow().newValue());
		assertEquals(new Action.SetSetting("vanilla.enableVsync", "false", "true"), setting(report, "vanilla.enableVsync").orElseThrow());
		assertEquals("false", setting(report, "vanilla.cutoutLeaves").orElseThrow().newValue());

		assertTrue(recs.containsKey("advice:ram-low"));
		assertTrue(recs.containsKey("advice:battery"));
		assertEquals(Category.ADVICE, recs.get("advice:battery").category());
		assertFalse(recs.get("advice:battery").appliable());
	}

	@Test
	void lowTierNeverRaisesSettingsThePlayerAlreadyLowered() {
		Map<String, String> alreadyLow = new HashMap<>(LAPTOP_SETTINGS);
		alreadyLow.put("vanilla.particles", "2");
		alreadyLow.put("vanilla.biomeBlendRadius", "0");
		alreadyLow.put("vanilla.entityDistanceScaling", "0.5");
		alreadyLow.put("vanilla.simulationDistance", "5");
		alreadyLow.put("vanilla.renderClouds", "false");
		Report report = Recommender.recommend(RulesLoader.loadBundled(), Fixtures.lowEndLaptop().build(), Fixtures.mods("fabric-api"),
				new SettingsSnapshot(alreadyLow), OnlineData.offline(), Goal.BALANCED);
		for (String key : List.of("vanilla.particles", "vanilla.biomeBlendRadius", "vanilla.entityDistanceScaling",
				"vanilla.simulationDistance", "vanilla.renderClouds")) {
			assertFalse(setting(report, key).isPresent(), key);
		}

		Report fromDefaults = Recommender.recommend(RulesLoader.loadBundled(), Fixtures.lowEndLaptop().build(), Fixtures.mods("fabric-api"),
				new SettingsSnapshot(LAPTOP_SETTINGS), OnlineData.offline(), Goal.BALANCED);
		assertEquals("1", setting(fromDefaults, "vanilla.particles").orElseThrow().newValue());
		assertEquals("1", setting(fromDefaults, "vanilla.biomeBlendRadius").orElseThrow().newValue());
		assertEquals("0.75", setting(fromDefaults, "vanilla.entityDistanceScaling").orElseThrow().newValue());
		assertEquals("6", setting(fromDefaults, "vanilla.simulationDistance").orElseThrow().newValue());
	}

	@Test
	void softwareRenderingIsCritical() {
		Fixtures.Hw hw = Fixtures.userRig().gpu("Mesa", "llvmpipe (LLVM 15.0.7, 256 bits)");
		Report report = Recommender.recommend(RulesLoader.loadBundled(), hw.build(), Fixtures.mods("sodium"),
				new SettingsSnapshot(Map.of()), OnlineData.offline(), Goal.BALANCED);
		assertEquals(0, report.gpuClass().tier());
		assertEquals(0, report.tier().effectiveTier());
		Recommendation critical = byId(report).get("advice:software-rendering");
		assertEquals(Category.WARNING, critical.category());
		assertEquals(Impact.HIGH, critical.impact());
		assertEquals(critical, report.recommendations().get(0));
	}

	@Test
	void obsoleteIndiumIsDisabled() {
		List<InstalledMod> mods = new ArrayList<>(Fixtures.mods("sodium", "fabric-api"));
		mods.add(new InstalledMod("indium", "Indium", "1.0.36+mc1.20.1", Path.of("mods", "indium-1.0.36.jar"), "abc"));
		Report report = Recommender.recommend(RulesLoader.loadBundled(), Fixtures.userRig().build(), mods,
				new SettingsSnapshot(Map.of()), OnlineData.offline(), Goal.BALANCED);
		Recommendation rec = byId(report).get("disable:indium");
		assertEquals(Category.REMOVE_MOD, rec.category());
		assertEquals(Impact.HIGH, rec.impact());
		assertEquals(new Action.DisableMod("indium", Path.of("mods", "indium-1.0.36.jar")), rec.action());
		assertTrue(rec.selectedByDefault());
	}

	@Test
	void c2meAndMoonriseConflict() {
		Report report = Recommender.recommend(RulesLoader.loadBundled(), Fixtures.userRig().build(), Fixtures.mods("sodium", "c2me", "moonrise"),
				new SettingsSnapshot(Map.of()), OnlineData.offline(), Goal.BALANCED);
		List<Recommendation> conflicts = report.recommendations().stream().filter(r -> r.id().startsWith("conflict:")).toList();
		assertEquals(1, conflicts.size());
		Recommendation conflict = conflicts.get(0);
		assertEquals("conflict:c2me-fabric+moonrise-opt", conflict.id());
		assertEquals(Category.WARNING, conflict.category());
		assertInstanceOf(Action.None.class, conflict.action());
		assertTrue(conflict.title().contains("C2ME") && conflict.title().contains("Moonrise"));
		assertFalse(byId(report).containsKey("add:scalablelux"));
	}

	@Test
	void availabilityOfflineVersusOnline() {
		Fixtures.Hw hw = Fixtures.lowEndLaptop();
		List<InstalledMod> mods = Fixtures.mods("fabric-api");
		SettingsSnapshot settings = new SettingsSnapshot(Map.of());

		Map<String, Recommendation> unknown = byId(Recommender.recommend(RulesLoader.loadBundled(), hw.build(), mods, settings, OnlineData.offline(), Goal.BALANCED));
		assertTrue(unknown.get("add:sodium").reason().contains(Recommender.AVAILABILITY_UNKNOWN_NOTE));

		RulesDocument offlineRules = RulesLoader.loadBundled();
		offlineRules.availability.put("26.2", List.of("lithium"));
		Map<String, Recommendation> offline = byId(Recommender.recommend(offlineRules, hw.build(), mods, settings, OnlineData.offline(), Goal.BALANCED));
		assertFalse(offline.containsKey("add:sodium"));
		assertFalse(offline.get("add:lithium").reason().contains(Recommender.AVAILABILITY_UNKNOWN_NOTE));

		OnlineData onlineData = new OnlineData(true, Map.of("sodium", true, "lithium", false), Map.of());
		Report onlineReport = Recommender.recommend(offlineRules, hw.build(), mods, settings, onlineData, Goal.BALANCED);
		Map<String, Recommendation> online = byId(onlineReport);
		assertTrue(onlineReport.online());
		assertFalse(online.get("add:sodium").reason().contains(Recommender.AVAILABILITY_UNKNOWN_NOTE));
		assertFalse(online.containsKey("add:lithium"));
		assertFalse(online.containsKey("add:ferrite-core"));

		OnlineData staleOffline = new OnlineData(false, Map.of("sodium", false), Map.of());
		Map<String, Recommendation> stale = byId(Recommender.recommend(RulesLoader.loadBundled(), hw.build(), mods, settings, staleOffline, Goal.BALANCED));
		assertTrue(stale.containsKey("add:sodium"));
	}
}
