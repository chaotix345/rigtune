package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingLabel;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The v0.2 knowledge (SPEC items 9, 7 and AC2.3) over the bundled rules-v2.json.
class KnowledgeV2ScenarioTest {
	private static final String LOD = "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius";
	private static final String VERTICAL = "dh.client.advanced.graphics.quality.verticalQuality";
	private static final String HORIZONTAL = "dh.client.advanced.graphics.quality.horizontalQuality";
	private static final String RESOLUTION = "dh.client.advanced.graphics.quality.maxHorizontalResolution";
	private static final String THREADS = "dh.common.multiThreading.numberOfThreads";
	private static final String SHADOW = "iris.maxShadowRenderDistance";
	private static final String SHADERS = "shaders-enabled";

	// Distant Horizons 3.3.2's defaults (numberOfThreads is half the logical cores; 4 on the 8-thread laptop).
	private static final Map<String, String> DH_DEFAULTS = Map.of(LOD, "256", VERTICAL, "MEDIUM", HORIZONTAL, "MEDIUM",
			RESOLUTION, "BLOCK", THREADS, "4");

	// triage.md §5
	private static final List<String> MESH_SHADER_GPUS = List.of("NVIDIA GeForce RTX 4070", "NVIDIA GeForce GTX 1660 SUPER",
			"NVIDIA GeForce GTX 1650", "NVIDIA GeForce GTX 1650 Ti", "NVIDIA GeForce RTX 2060", "NVIDIA GeForce RTX 2060 SUPER",
			"NVIDIA GeForce RTX 2080 Ti", "NVIDIA GeForce RTX 3080", "NVIDIA GeForce RTX 3070 Laptop GPU", "NVIDIA GeForce RTX 4090",
			"NVIDIA GeForce RTX 5080", "Quadro RTX 4000", "Quadro RTX 5000", "Quadro RTX 8000", "NVIDIA RTX A2000", "NVIDIA RTX A4000",
			"NVIDIA RTX A6000", "NVIDIA RTX 4000 Ada Generation");
	private static final List<String> OTHER_GPUS = List.of("NVIDIA GeForce GTX 1080", "NVIDIA GeForce GTX 1080 Ti",
			"NVIDIA GeForce GTX 1070", "NVIDIA GeForce GTX 1060", "NVIDIA GeForce GTX 980 Ti", "NVIDIA GeForce GT 1030",
			"NVIDIA GeForce MX450", "Quadro P4000", "Quadro M4000", "AMD Radeon RX 6800 XT", "Intel(R) Iris(R) Xe Graphics");

	private static Map<String, Recommendation> run(Fixtures.Hw hw, List<String> mods, Map<String, String> settings) {
		return Recommender.recommend(RulesLoader.loadBundled(), hw.build(), Fixtures.mods(mods.toArray(String[]::new)),
						new SettingsSnapshot(settings), OnlineData.offline(), Goal.BALANCED)
				.recommendations().stream().collect(Collectors.toMap(Recommendation::id, r -> r));
	}

	private static Map<String, Recommendation> run(Fixtures.Hw hw, String... mods) {
		return run(hw, List.of(mods), Map.of());
	}

	private static Fixtures.Hw gpu(String renderer) {
		String vendor = renderer.startsWith("AMD") ? "ATI Technologies Inc." : renderer.startsWith("Intel") ? "Intel" : "NVIDIA Corporation";
		return Fixtures.userRig().gpu(vendor, renderer);
	}

	private static Fixtures.Hw withFlags(Fixtures.Hw hw, String... flags) {
		hw.flags = Set.of(flags);
		return hw;
	}

	private static Map<String, String> with(Map<String, String> base, String... keyValues) {
		Map<String, String> out = new HashMap<>(base);
		for (int i = 0; i < keyValues.length; i += 2) {
			out.put(keyValues[i], keyValues[i + 1]);
		}
		return out;
	}

	private static String target(Map<String, Recommendation> recs, String key) {
		Recommendation rec = recs.get("set:" + key);
		return rec == null ? null : ((Action.SetSetting) rec.action()).newValue();
	}

	private static Set<String> advice(Map<String, Recommendation> recs) {
		return recs.keySet().stream().filter(id -> id.startsWith("advice:")).map(id -> id.substring("advice:".length()))
				.collect(Collectors.toSet());
	}

	@Test
	void nvidiumOnTuringAndNewer() {
		for (String renderer : List.of("NVIDIA GeForce RTX 2060", "NVIDIA GeForce GTX 1660 SUPER")) {
			Recommendation rec = run(gpu(renderer), "sodium").get("add:nvidium");
			assertNotNull(rec, renderer);
			assertTrue(rec.selectedByDefault(), renderer);
		}
		assertFalse(run(gpu("NVIDIA GeForce GTX 1080"), "sodium").containsKey("add:nvidium"));
		assertFalse(run(Fixtures.userRig(), "sodium").containsKey("add:nvidium"), "AMD");
		for (String renderer : MESH_SHADER_GPUS) {
			assertTrue(run(gpu(renderer), "sodium").containsKey("add:nvidium"), renderer);
		}
		for (String renderer : OTHER_GPUS) {
			assertFalse(run(gpu(renderer), "sodium").containsKey("add:nvidium"), renderer);
		}
		assertFalse(run(gpu("NVIDIA GeForce RTX 2060")).containsKey("add:nvidium"), "needs Sodium");
		assertFalse(run(withFlags(gpu("NVIDIA GeForce RTX 2060"), SHADERS), "sodium").containsKey("add:nvidium"), "off with shaders");
	}

	@Test
	void nvidiumInstalledOnPascalIsDisabled() {
		for (String pascalOrOlder : List.of("NVIDIA GeForce GTX 1080", "NVIDIA GeForce GTX 1050 Ti", "NVIDIA GeForce GTX 980 Ti",
				"NVIDIA GeForce GT 1030", "NVIDIA GeForce MX150", "NVIDIA TITAN Xp", "Quadro P4000")) {
			assertTrue(run(gpu(pascalOrOlder), "sodium", "nvidium").containsKey("disable:nvidium"), pascalOrOlder);
		}
		assertTrue(run(Fixtures.userRig(), "sodium", "nvidium").containsKey("disable:nvidium"), "AMD");
		// Not in the recommend list, but not known to lack mesh shaders either: a working Nvidium must not be flagged.
		for (String renderer : List.of("NVIDIA GeForce RTX 2060", "NVIDIA GeForce RTX 2050", "NVIDIA GeForce GTX 1630", "NVIDIA TITAN RTX",
				"NVIDIA RTX PRO 6000 Blackwell Workstation Edition", "NVIDIA GeForce MX450")) {
			assertFalse(run(gpu(renderer), "sodium", "nvidium").containsKey("disable:nvidium"), renderer);
		}
	}

	@Test
	void lambDynamicLightsOnTier2IsAnUntickedDisable() {
		Map<String, Recommendation> laptop = run(Fixtures.lowEndLaptop(), "fabric-api", "lambdynlights");
		Recommendation rec = laptop.get("disable:lambdynlights");
		assertNotNull(rec);
		assertEquals(Category.REMOVE_MOD, rec.category());
		assertEquals(Impact.LOW, rec.impact());
		assertFalse(rec.selectedByDefault());
		assertInstanceOf(Action.DisableMod.class, rec.action());

		assertFalse(run(Fixtures.userRig(), "sodium", "lambdynlights").containsKey("disable:lambdynlights"), "tier 5");
		assertFalse(run(Fixtures.lowEndLaptop(), "fabric-api").containsKey("add:lambdynamiclights"));
		assertFalse(run(Fixtures.userRig(), "sodium").containsKey("add:lambdynamiclights"));
	}

	@Test
	void dhTier2Settings() {
		Map<String, Recommendation> recs = run(Fixtures.lowEndLaptop(), List.of("sodium", "distanthorizons"), DH_DEFAULTS);
		assertEquals("64", target(recs, LOD));
		assertEquals("LOW", target(recs, VERTICAL));
		assertEquals("LOW", target(recs, HORIZONTAL));
		assertEquals("HALF_CHUNK", target(recs, RESOLUTION));
		assertFalse(recs.containsKey("set:" + THREADS), "8 threads: DH's default of 4 is within the CPU-tier cap");
		for (String key : List.of(LOD, VERTICAL, HORIZONTAL, RESOLUTION)) {
			assertTrue(recs.get("set:" + key).selectedByDefault(), key);
		}
		assertEquals("Distant Horizons: LOD Chunk Render Distance Radius: 256 → 64", recs.get("set:" + LOD).title());
		assertEquals("Distant Horizons: Max Horizontal Resolution: Block → Half a chunk", recs.get("set:" + RESOLUTION).title());

		Map<String, Recommendation> withoutDh = run(Fixtures.lowEndLaptop(), List.of("sodium"), DH_DEFAULTS);
		assertTrue(withoutDh.keySet().stream().noneMatch(id -> id.startsWith("set:dh.")), "gated on Distant Horizons");
	}

	@Test
	void dhTier2NeverRaisesWhatThePlayerLowered() {
		Map<String, Recommendation> recs = run(Fixtures.lowEndLaptop(), List.of("sodium", "distanthorizons"), with(DH_DEFAULTS, LOD, "32"));
		assertFalse(recs.containsKey("set:" + LOD));
	}

	@Test
	void dhTier5KeepsDefaults() {
		Map<String, String> defaults = with(DH_DEFAULTS, THREADS, "8");
		Map<String, Recommendation> recs = run(Fixtures.userRig(), List.of("sodium", "distanthorizons"), defaults);
		assertTrue(recs.keySet().stream().noneMatch(id -> id.startsWith("set:dh.")), recs.keySet().toString());

		Recommendation far = run(Fixtures.userRig(), List.of("sodium", "distanthorizons"), with(defaults, LOD, "512")).get("set:" + LOD);
		assertEquals("256", ((Action.SetSetting) far.action()).newValue());
		assertFalse(far.selectedByDefault());
	}

	@Test
	void dhThreadsClampedByCpuTier() {
		Fixtures.Hw fourThreads = Fixtures.userRig();
		fourThreads.cpu = new CpuInfo("Intel(R) Core(TM) i3-7100 CPU @ 3.90GHz", 2, 4, -1);
		Map<String, Recommendation> recs = run(fourThreads, List.of("sodium", "distanthorizons"), with(DH_DEFAULTS, THREADS, "8"));
		assertEquals("2", target(recs, THREADS));
	}

	@Test
	void irisShadowDistanceWithShadersOnly() {
		Map<String, String> iris = Map.of(SHADOW, "32");
		Recommendation rec = run(withFlags(Fixtures.lowEndLaptop(), SHADERS), List.of("sodium", "iris"), iris).get("set:" + SHADOW);
		assertEquals("6", ((Action.SetSetting) rec.action()).newValue());
		assertTrue(rec.selectedByDefault());
		assertEquals("Iris: Max Shadow Distance: 32 → 6", rec.title());
		assertFalse(run(Fixtures.lowEndLaptop(), List.of("sodium", "iris"), iris).containsKey("set:" + SHADOW));
	}

	@Test
	void renderScaleForMidTierGpuAt1440p() {
		Fixtures.Hw midTier = gpu("AMD Radeon RX 5700 XT");
		assertTrue(run(midTier, "sodium").containsKey("add:renderscale"), "2560x1440");
		midTier.display = new DisplayInfo(1920, 1080, 144, true);
		assertFalse(run(midTier, "sodium").containsKey("add:renderscale"), "1920x1080");
		assertFalse(run(Fixtures.userRig(), "sodium").containsKey("add:renderscale"), "tier-5 GPU at 1440p");
		assertTrue(run(Fixtures.lowEndLaptop(), "fabric-api").containsKey("add:renderscale"), "weak integrated GPU");
	}

	@Test
	void ramAdviceWithDhAndShaders() {
		Set<String> ram = Set.of("ram-distant-horizons", "ram-shaders", "ram-distant-horizons-shaders",
				"ram-distant-horizons-shaders-limited", "ram-distant-horizons-low-system");
		Fixtures.Hw both = withFlags(Fixtures.userRig(), SHADERS);
		both.heapMb = 4096;
		assertEquals(Set.of("ram-distant-horizons-shaders"), intersect(advice(run(both, "sodium", "iris", "distanthorizons")), ram));
		both.ramMb = 12288;
		assertEquals(Set.of("ram-distant-horizons-shaders-limited"), intersect(advice(run(both, "sodium", "iris", "distanthorizons")), ram));

		Fixtures.Hw dhOnly = Fixtures.userRig();
		dhOnly.heapMb = 4096;
		assertEquals(Set.of("ram-distant-horizons"), intersect(advice(run(dhOnly, "sodium", "distanthorizons")), ram));
		Fixtures.Hw shadersOnly = withFlags(Fixtures.userRig(), SHADERS);
		shadersOnly.heapMb = 4096;
		assertEquals(Set.of("ram-shaders"), intersect(advice(run(shadersOnly, "sodium", "iris")), ram));

		assertTrue(advice(run(Fixtures.lowEndLaptop(), "fabric-api", "distanthorizons")).contains("ram-distant-horizons-low-system"));
	}

	private static Set<String> intersect(Set<String> a, Set<String> b) {
		return a.stream().filter(b::contains).collect(Collectors.toSet());
	}

	@Test
	void shadersOnEntryLevelHardware() {
		Set<String> laptop = advice(run(withFlags(Fixtures.lowEndLaptop(), SHADERS), "fabric-api", "iris"));
		assertTrue(laptop.contains("shaders-entry-level"));
		assertFalse(laptop.contains("heavy-shaders"));

		Set<String> midTier = advice(run(withFlags(gpu("AMD Radeon RX 5700 XT"), SHADERS), "sodium", "iris"));
		assertTrue(midTier.contains("heavy-shaders"));
		assertFalse(midTier.contains("shaders-entry-level"));
	}

	@Test
	void shaderPacksWithDhSupport() {
		Recommendation rec = run(withFlags(Fixtures.userRig(), SHADERS), "sodium", "iris", "distanthorizons").get("advice:shaders-distant-horizons");
		assertNotNull(rec);
		assertEquals(Category.ADVICE, rec.category());
		assertFalse(run(Fixtures.userRig(), "sodium", "iris", "distanthorizons").containsKey("advice:shaders-distant-horizons"));
	}

	@Test
	void ixerisReasonDependsOnMcVersion() {
		Recommendation on262 = run(Fixtures.userRig(), "sodium").get("add:ixeris");
		assertTrue(on262.selectedByDefault());
		assertFalse(on262.reason().contains("26.3"), on262.reason());

		Fixtures.Hw mc263 = Fixtures.userRig();
		mc263.mcVersion = "26.3";
		Recommendation on263 = run(mc263, "sodium").get("add:ixeris");
		assertTrue(on263.selectedByDefault());
		assertTrue(on263.reason().contains("26.3") && on263.reason().contains("SDL"), on263.reason());
	}

	@Test
	void everySettingKeyHasALabel() {
		RulesDocument rules = RulesLoader.loadBundled();
		for (RulesDocument.SettingRule rule : rules.settings) {
			SettingLabel label = rules.settingLabels.get(rule.key);
			assertTrue(label != null && label.name != null && !label.name.isBlank(), rule.key);
		}
	}
}
