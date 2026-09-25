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
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
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
	private static final String RENDERER = "dh.client.advanced.debugging.rendererMode";
	private static final String SHADOW = "iris.maxShadowRenderDistance";
	private static final String SHADERS = "shaders-enabled";
	private static final List<String> DH_MODS = List.of("sodium", "distanthorizons");

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

	// Heap tier 1 (under 1792 MB) makes the laptop effective tier 1.
	private static Fixtures.Hw tier1Laptop() {
		Fixtures.Hw hw = Fixtures.lowEndLaptop();
		hw.heapMb = 1024;
		return hw;
	}

	// A GPU-tier-3 card with the tier-5 CPU and heap of the user's rig: effective tier 3.
	private static Fixtures.Hw tier3Rig() {
		return gpu("AMD Radeon RX 5700 XT");
	}

	// Heap tier 4 (3840..5631 MB) on the user's rig: effective tier 4.
	private static Fixtures.Hw tier4Rig() {
		Fixtures.Hw hw = Fixtures.userRig();
		hw.heapMb = 4096;
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

	private static Set<String> dhKeys(Map<String, Recommendation> recs) {
		return recs.keySet().stream().filter(id -> id.startsWith("set:dh.")).map(id -> id.substring("set:".length())).collect(Collectors.toSet());
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
			// Review 3, rules-accuracy-3: opt-in, a beta renderer pinned to an exact Sodium build.
			assertFalse(rec.selectedByDefault(), renderer);
		}
		assertFalse(run(gpu("NVIDIA GeForce GTX 1080"), "sodium").containsKey("add:nvidium"));
		assertFalse(run(Fixtures.userRig(), "sodium").containsKey("add:nvidium"), "AMD");
		for (String renderer : MESH_SHADER_GPUS) {
			assertTrue(run(gpu(renderer), "sodium").containsKey("add:nvidium"), renderer);
		}
		for (String renderer : OTHER_GPUS) {
			assertFalse(run(gpu(renderer), "sodium").containsKey("add:nvidium"), renderer);
		}
		// Review 3, rules-accuracy-1: the model whitelist decides, not the GPU tier (these laptop GPUs are tier 2).
		for (String renderer : List.of("NVIDIA GeForce RTX 2060 Laptop GPU", "NVIDIA GeForce RTX 2070 Laptop GPU",
				"NVIDIA GeForce RTX 3050 Ti Laptop GPU", "NVIDIA GeForce GTX 1650 Ti Laptop GPU")) {
			assertTrue(run(gpu(renderer), "sodium").containsKey("add:nvidium"), renderer);
		}
		assertFalse(run(gpu("NVIDIA GeForce RTX 2060")).containsKey("add:nvidium"), "needs Sodium");
		assertFalse(run(withFlags(gpu("NVIDIA GeForce RTX 2060"), SHADERS), "sodium").containsKey("add:nvidium"), "off with shaders");
	}

	@Test
	void nvidiumInstalledOnPreTuringIsDisabled() {
		for (String preTuring : List.of("NVIDIA GeForce GTX 1080", "NVIDIA GeForce GTX 1050 Ti", "NVIDIA GeForce GTX 980 Ti",
				"NVIDIA GeForce GT 1030", "NVIDIA GeForce MX150", "NVIDIA TITAN Xp", "Quadro P4000", "NVIDIA GeForce GTX 980M",
				"NVIDIA GeForce 940MX", "NVIDIA GeForce 920M", "NVIDIA GeForce MX250", "Quadro M1200")) {
			Recommendation rec = run(gpu(preTuring), "sodium", "nvidium").get("disable:nvidium");
			assertNotNull(rec, preTuring);
			assertTrue(rec.selectedByDefault(), preTuring);
		}
		assertTrue(run(Fixtures.userRig(), "sodium", "nvidium").containsKey("disable:nvidium"), "AMD");
		// Not offered on these, but not known to lack mesh shaders either: a working Nvidium must not be flagged.
		for (String renderer : List.of("NVIDIA GeForce RTX 2060", "NVIDIA GeForce RTX 2060 Laptop GPU", "NVIDIA GeForce RTX 2050",
				"NVIDIA GeForce GTX 1630", "NVIDIA TITAN RTX", "NVIDIA RTX PRO 6000 Blackwell Workstation Edition", "NVIDIA GeForce MX450")) {
			assertFalse(run(gpu(renderer), "sodium", "nvidium").containsKey("disable:nvidium"), renderer);
		}
	}

	@Test
	void nvidiumFailsClosedWithoutGpuInfo() {
		// No renderer and no vendor: the vendor and the model are UNKNOWN. An NVIDIA vendor string alone names no model.
		for (Fixtures.Hw hw : List.of(Fixtures.userRig().gpu("", ""), Fixtures.userRig().gpu("NVIDIA Corporation", ""))) {
			assertFalse(run(hw, "sodium", "nvidium").containsKey("disable:nvidium"));
			assertFalse(run(hw, "sodium").containsKey("add:nvidium"));
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
	void dhTier1Settings() {
		Map<String, Recommendation> recs = run(tier1Laptop(), DH_MODS, DH_DEFAULTS);
		assertEquals("48", target(recs, LOD));
		assertEquals("LOW", target(recs, VERTICAL));
		assertEquals("LOWEST", target(recs, HORIZONTAL));
		assertEquals("TWO_BLOCKS", target(recs, RESOLUTION));
		for (String key : List.of(LOD, VERTICAL, HORIZONTAL, RESOLUTION)) {
			assertTrue(recs.get("set:" + key).selectedByDefault(), key);
		}
	}

	@Test
	void dhTier2Settings() {
		Map<String, Recommendation> recs = run(Fixtures.lowEndLaptop(), DH_MODS, DH_DEFAULTS);
		assertEquals("64", target(recs, LOD));
		assertEquals("LOW", target(recs, VERTICAL));
		assertEquals("LOW", target(recs, HORIZONTAL));
		assertEquals("TWO_BLOCKS", target(recs, RESOLUTION));
		assertFalse(recs.containsKey("set:" + THREADS), "8 threads: DH's default of 4 is within the CPU-tier cap");
		for (String key : List.of(LOD, VERTICAL, HORIZONTAL, RESOLUTION)) {
			assertTrue(recs.get("set:" + key).selectedByDefault(), key);
		}
		assertEquals("Distant Horizons: LOD Chunk Render Distance Radius: 256 → 64", recs.get("set:" + LOD).title());
		assertEquals("Distant Horizons: Max Horizontal Resolution: Block → 2 blocks", recs.get("set:" + RESOLUTION).title());

		Map<String, Recommendation> withoutDh = run(Fixtures.lowEndLaptop(), List.of("sodium"), DH_DEFAULTS);
		assertTrue(dhKeys(withoutDh).isEmpty(), "gated on Distant Horizons");
	}

	@Test
	void dhLodCapNeverRaisesWhatThePlayerLowered() {
		assertFalse(run(Fixtures.lowEndLaptop(), DH_MODS, with(DH_DEFAULTS, LOD, "32")).containsKey("set:" + LOD), "tier 2");
		assertFalse(run(tier3Rig(), DH_MODS, with(DH_DEFAULTS, LOD, "64", THREADS, "8")).containsKey("set:" + LOD), "tier 3");
	}

	@Test
	void dhEnumValuesCanRaiseASettingBelowThem() {
		// Enums can't be capped: a value entry also raises a setting the player put lower. Known and accepted for tier <= 2.
		Map<String, Recommendation> recs = run(Fixtures.lowEndLaptop(), DH_MODS, with(DH_DEFAULTS, VERTICAL, "HEIGHT_MAP", RESOLUTION, "CHUNK"));
		assertEquals("LOW", target(recs, VERTICAL));
		assertEquals("TWO_BLOCKS", target(recs, RESOLUTION));
	}

	@Test
	void dhTier3CapsOnlyTheLodDistance() {
		Map<String, Recommendation> recs = run(tier3Rig(), DH_MODS, with(DH_DEFAULTS, THREADS, "8"));
		assertEquals(Set.of(LOD), dhKeys(recs));
		assertEquals("96", target(recs, LOD));
		assertTrue(recs.get("set:" + LOD).selectedByDefault());
	}

	@Test
	void dhTier4OffersAShorterLodDistanceUnticked() {
		Map<String, Recommendation> recs = run(tier4Rig(), DH_MODS, with(DH_DEFAULTS, THREADS, "8"));
		assertEquals(Set.of(LOD), dhKeys(recs));
		assertEquals("160", target(recs, LOD));
		assertFalse(recs.get("set:" + LOD).selectedByDefault());
	}

	@Test
	void dhTier5KeepsDefaults() {
		Map<String, String> defaults = with(DH_DEFAULTS, THREADS, "8");
		assertTrue(dhKeys(run(Fixtures.userRig(), DH_MODS, defaults)).isEmpty());

		Recommendation far = run(Fixtures.userRig(), DH_MODS, with(defaults, LOD, "512")).get("set:" + LOD);
		assertEquals("256", ((Action.SetSetting) far.action()).newValue());
		assertFalse(far.selectedByDefault());
	}

	@Test
	void dhThreadsClampedByCpuTier() {
		Fixtures.Hw fourThreads = Fixtures.userRig();
		fourThreads.cpu = new CpuInfo("Intel(R) Core(TM) i3-7100 CPU @ 3.90GHz", 2, 4, -1);
		assertEquals("2", target(run(fourThreads, DH_MODS, with(DH_DEFAULTS, THREADS, "8")), THREADS));
		assertFalse(run(fourThreads, DH_MODS, with(DH_DEFAULTS, THREADS, "1")).containsKey("set:" + THREADS), "never raises");
	}

	@Test
	void irisShadowDistanceWithShadersOnly() {
		Map<String, String> iris = Map.of(SHADOW, "32");
		List<String> mods = List.of("sodium", "iris");

		Recommendation tier1 = run(withFlags(tier1Laptop(), SHADERS), mods, iris).get("set:" + SHADOW);
		assertEquals("4", ((Action.SetSetting) tier1.action()).newValue());
		assertTrue(tier1.selectedByDefault());

		Recommendation tier2 = run(withFlags(Fixtures.lowEndLaptop(), SHADERS), mods, iris).get("set:" + SHADOW);
		assertEquals("6", ((Action.SetSetting) tier2.action()).newValue());
		assertTrue(tier2.selectedByDefault());
		assertEquals("Iris: Max Shadow Distance: 32 → 6", tier2.title());

		Recommendation tier3 = run(withFlags(tier3Rig(), SHADERS), mods, iris).get("set:" + SHADOW);
		assertEquals("8", ((Action.SetSetting) tier3.action()).newValue());
		assertFalse(tier3.selectedByDefault());

		assertFalse(run(withFlags(Fixtures.userRig(), SHADERS), mods, iris).containsKey("set:" + SHADOW), "tier 5");
		assertFalse(run(Fixtures.lowEndLaptop(), mods, iris).containsKey("set:" + SHADOW), "shaders off");
	}

	@Test
	void renderScaleForMidTierGpuAt1440p() {
		Fixtures.Hw midTier = tier3Rig();
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

		Set<String> midTier = advice(run(withFlags(tier3Rig(), SHADERS), "sodium", "iris"));
		assertTrue(midTier.contains("heavy-shaders"));
		assertFalse(midTier.contains("shaders-entry-level"));

		// Tier 1 because of the heap, with a strong GPU: the GPU isn't what's short, so neither shader warning.
		Fixtures.Hw strongGpu = withFlags(Fixtures.userRig(), SHADERS);
		strongGpu.heapMb = 1024;
		Set<String> heapLimited = advice(run(strongGpu, "sodium", "iris"));
		assertFalse(heapLimited.contains("shaders-entry-level"));
		assertFalse(heapLimited.contains("heavy-shaders"));
	}

	@Test
	void shaderPacksWithDhSupport() {
		Recommendation rec = run(withFlags(Fixtures.userRig(), SHADERS), "sodium", "iris", "distanthorizons").get("advice:shaders-distant-horizons");
		assertNotNull(rec);
		assertEquals(Category.ADVICE, rec.category());
		assertFalse(run(Fixtures.userRig(), "sodium", "iris", "distanthorizons").containsKey("advice:shaders-distant-horizons"));
	}

	@Test
	void ixerisIsTickedOnBothVersions() {
		for (String mc : List.of("26.2", "26.3")) {
			Fixtures.Hw hw = Fixtures.userRig();
			hw.mcVersion = mc;
			Recommendation rec = run(hw, "sodium").get("add:ixeris");
			assertNotNull(rec, mc);
			assertTrue(rec.selectedByDefault(), mc);
			assertTrue(rec.reason().contains("26.3") && rec.reason().contains("SDL"), rec.reason());
		}
		assertEquals(1, RulesLoader.loadBundled().mods.stream().filter(m -> m.slug.equals("ixeris")).count());
	}

	@Test
	void everySettingKeyHasALabel() {
		RulesDocument rules = RulesLoader.loadBundled();
		for (RulesDocument.SettingRule rule : rules.settings) {
			SettingLabel label = rules.settingLabels.get(rule.key);
			assertTrue(label != null && label.name != null && !label.name.isBlank(), rule.key);
		}
	}

	// Phase 5 finding 1: Distant Horizons' own auto-updater replaces its jar at exit, which collides with RigTune's update.
	@Test
	void dhUpdatesItselfWhileItsAutoUpdaterIsOn() {
		String autoUpdater = "dh.client.advanced.autoUpdater.enableAutoUpdater";
		UpdateInfo update = new UpdateInfo("distanthorizons", "uCdwusMi", "3.3.0", "v", "3.3.2", null);
		OnlineData online = new OnlineData(true, Map.of(), Map.of("distanthorizons", update));
		for (String on : List.of("true", "false")) {
			Map<String, Recommendation> recs = Recommender.recommend(RulesLoader.loadBundled(), Fixtures.userRig().build(),
							Fixtures.mods("sodium", "distanthorizons"), new SettingsSnapshot(with(DH_DEFAULTS, autoUpdater, on)), online, Goal.BALANCED)
					.recommendations().stream().collect(Collectors.toMap(Recommendation::id, r -> r));
			boolean selfUpdating = on.equals("true");
			assertEquals(!selfUpdating, recs.containsKey("update:distanthorizons"), on);
			assertEquals(selfUpdating, recs.containsKey("advice:updates-itself:distanthorizons"), on);
		}
	}

	// Phase 5 finding 2: the vanilla render-distance clamps are about Distant Horizons drawing the far terrain.
	@Test
	void dhRenderDistanceClampOnlyWhileDhRenders() {
		Map<String, String> far = with(DH_DEFAULTS, "vanilla.renderDistance", "32", THREADS, "8");
		assertEquals("12", target(run(Fixtures.userRig(), DH_MODS, with(far, RENDERER, "DEFAULT")), "vanilla.renderDistance"));
		assertEquals("8", target(run(Fixtures.lowEndLaptop(), DH_MODS, with(far, RENDERER, "DEFAULT")), "vanilla.renderDistance"));
		for (Map<String, String> settings : List.of(with(far, RENDERER, "DISABLED"), far)) {
			Recommendation rec = run(Fixtures.userRig(), DH_MODS, settings).get("set:vanilla.renderDistance");
			assertTrue(rec == null || !rec.reason().contains("Distant Horizons"), String.valueOf(rec));
		}
	}

	// Phase 5 finding 10: with Chunky, Distant Horizons wants at least as many threads as C2ME and warns about too few.
	@Test
	void dhThreadsAreNotCappedWithChunky() {
		Fixtures.Hw fourThreads = Fixtures.userRig();
		fourThreads.cpu = new CpuInfo("Intel(R) Core(TM) i3-7100 CPU @ 3.90GHz", 2, 4, -1);
		assertFalse(run(fourThreads, List.of("sodium", "distanthorizons", "chunky", "c2me"), with(DH_DEFAULTS, THREADS, "8"))
				.containsKey("set:" + THREADS));
	}

	// Phase 5 finding 3.
	@Test
	void modernFixReasonFitsEveryVersion() {
		for (String mc : List.of("26.2", "26.3")) {
			Fixtures.Hw hw = Fixtures.userRig();
			hw.mcVersion = mc;
			String reason = run(hw, "sodium").get("add:modernfix-mvus").reason();
			assertFalse(reason.contains("26."), reason);
		}
	}
}
