package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.profile.ProfileTemplates.Result;
import io.github.chaotix345.rigtune.core.profile.ProfileTemplates.TemplateId;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.6 over fixture rules (the pinned r13 rules, which have no profileTemplates section, so the built-in
// definitions apply; WS-R's bundled section gets its own scenario tests once it lands).
class ProfileTemplatesTest {
	private static final String RD = "vanilla.renderDistance";
	private static final String FPS = "vanilla.maxFps";
	private static final String VSYNC = "vanilla.enableVsync";
	private static final String INACTIVITY = "vanilla.inactivityFpsLimit";
	private static final String SHADERS = "iris.enableShaders";
	private static final String RENDERER = "dh.client.advanced.debugging.rendererMode";

	private static Map<String, Fixtures.Hw> rigs() {
		Map<String, Fixtures.Hw> rigs = new LinkedHashMap<>();
		rigs.put("userRig180Hz", Fixtures.userRig());
		rigs.put("laptop60Hz", Fixtures.lowEndLaptop());
		Fixtures.Hw plugged = Fixtures.lowEndLaptop();
		plugged.onBattery = false;
		rigs.put("laptopPluggedIn", plugged);
		Fixtures.Hw rig144 = Fixtures.userRig();
		rig144.display = new DisplayInfo(2560, 1440, 144, true);
		rig144.heapMb = 4096;
		rigs.put("rig144HzHeap4G", rig144);
		Fixtures.Hw smallHeap = Fixtures.userRig();
		smallHeap.heapMb = 2048;
		rigs.put("smallHeap", smallHeap);
		Fixtures.Hw unknown = Fixtures.userRig().gpu("ATI Technologies Inc.", "AMD Radeon RX 5700 XT");
		unknown.display = new DisplayInfo(1920, 1080, -1, true);
		rigs.put("unknownRefresh", unknown);
		Fixtures.Hw slow = Fixtures.userRig();
		slow.display = new DisplayInfo(1920, 1080, 50, true);
		rigs.put("display50Hz", slow);
		return rigs;
	}

	private static Result compute(TemplateId id, Fixtures.Hw hw, List<String> mods, SettingsSnapshot snapshot) {
		return ProfileTemplates.compute(id, ProfileFixtures.rules(), null, hw.build(), Fixtures.mods(mods.toArray(String[]::new)), snapshot,
				snapshot.values());
	}

	@Test
	void everyValueOfEveryTemplateIsInsideItsTableBounds() {
		for (Map.Entry<String, Fixtures.Hw> rig : rigs().entrySet()) {
			for (List<String> mods : ProfileFixtures.MOD_SETS) {
				for (boolean maxed : new boolean[] {false, true}) {
					SettingsSnapshot snapshot = ProfileFixtures.snapshot(mods, maxed);
					for (TemplateId id : TemplateId.values()) {
						Result result = compute(id, rig.getValue(), mods, snapshot);
						assertFalse(result.values().isEmpty(), id + " " + rig.getKey());
						result.values().forEach((key, value) -> {
							ShareKeys.Key tableKey = ShareKeys.byKey(key);
							assertNotNull(tableKey, key);
							assertNotNull(tableKey.encode(value), id + " " + rig.getKey() + " " + mods + " " + key + "=" + value);
						});
						assertFalse(result.values().containsKey("vanilla.graphicsPreset"));
						assertFalse(result.values().containsKey("iris.shaderPack"));
					}
				}
			}
		}
	}

	@Test
	void batteryCapsFramesAndSwitchesShadersAndDistantHorizonsOff() {
		for (Map.Entry<String, Fixtures.Hw> rig : rigs().entrySet()) {
			for (List<String> mods : ProfileFixtures.MOD_SETS) {
				Map<String, String> battery = compute(TemplateId.BATTERY, rig.getValue(), mods, ProfileFixtures.snapshot(mods, true)).values();
				String where = rig.getKey() + " " + mods;
				assertEquals("60", battery.get(FPS), where);
				assertEquals("true", battery.get(VSYNC), where);
				assertEquals("afk", battery.get(INACTIVITY), where);
				assertEquals("false", battery.get("vanilla.renderClouds"), where);
				assertTrue(Integer.parseInt(battery.get(RD)) <= 8, where);
				assertTrue(Integer.parseInt(battery.get("vanilla.simulationDistance")) <= 6, where);
				assertTrue(Integer.parseInt(battery.get("vanilla.particles")) >= 1, where);
				assertEquals(mods.contains("iris") ? "false" : null, battery.get(SHADERS), where);
				assertEquals(mods.contains("distanthorizons") ? "DISABLED" : null, battery.get(RENDERER), where);
			}
		}
	}

	@Test
	void recordingCapsAtTheRecordingFrameRate() {
		assertEquals(60, ProfileTemplates.recordingFps(144));
		assertEquals(50, ProfileTemplates.recordingFps(50));
		assertEquals(60, ProfileTemplates.recordingFps(-1));
		assertEquals(60, ProfileTemplates.recordingFps(60));
		assertEquals(50, ProfileTemplates.recordingFps(59));
		assertEquals(30, ProfileTemplates.recordingFps(24));
		Map<String, Fixtures.Hw> rigs = rigs();
		Map<String, String> at144 = compute(TemplateId.RECORDING, rigs.get("rig144HzHeap4G"), List.of("sodium"), ProfileFixtures.snapshot(List.of("sodium"), true)).values();
		assertEquals("60", at144.get(FPS));
		assertEquals("false", at144.get(VSYNC));
		assertEquals("minimized", at144.get(INACTIVITY));
		assertEquals("ALWAYS", at144.get("sodium.performance.chunk_build_defer_mode"));
		Map<String, String> at50 = compute(TemplateId.RECORDING, rigs.get("display50Hz"), List.of(), ProfileFixtures.snapshot(List.of(), true)).values();
		assertEquals("50", at50.get(FPS));
		assertEquals("true", at50.get(VSYNC));
		assertEquals("0", at50.get("vanilla.prioritizeChunkUpdates"));
		assertEquals("minimized", at50.get(INACTIVITY));
		Map<String, String> unknown = compute(TemplateId.RECORDING, rigs.get("unknownRefresh"), List.of(), ProfileFixtures.snapshot(List.of(), true)).values();
		assertEquals("60", unknown.get(FPS));
		Map<String, String> laptop = compute(TemplateId.RECORDING, rigs.get("laptop60Hz"), List.of(), ProfileFixtures.snapshot(List.of(), true)).values();
		assertEquals("60", laptop.get(FPS));
		assertEquals("true", laptop.get(VSYNC));
		assertEquals("minimized", laptop.get(INACTIVITY));
	}

	@Test
	void maxFpsIsUnlimitedWithoutVsync() {
		Map<String, String> max = compute(TemplateId.MAX_FPS, Fixtures.userRig(), List.of("sodium"), ProfileFixtures.snapshot(List.of("sodium"), false)).values();
		assertEquals("260", max.get(FPS));
		assertEquals("false", max.get(VSYNC));
	}

	@Test
	void aSmallHeapCapsRenderDistanceInQualityAndMaxFps() {
		Fixtures.Hw hw = rigs().get("smallHeap");
		for (TemplateId id : List.of(TemplateId.QUALITY, TemplateId.MAX_FPS, TemplateId.BALANCED)) {
			for (List<String> mods : ProfileFixtures.MOD_SETS) {
				Result result = compute(id, hw, mods, ProfileFixtures.snapshot(mods, true));
				assertTrue(Integer.parseInt(result.values().get(RD)) <= 8, id + " " + mods + " " + result.values().get(RD));
				// Quality's own rule value is above the cap, so the heap clamp is what holds it.
				assertTrue(id != TemplateId.QUALITY || result.clamps().stream().anyMatch(c -> c.key().equals(RD)), id + " " + mods);
			}
		}
		// Without the small heap, Quality's render distance is the rules' tier-5 value.
		assertEquals("16", compute(TemplateId.QUALITY, Fixtures.userRig(), List.of(), ProfileFixtures.snapshot(List.of(), true)).values().get(RD));
	}

	@Test
	void balancedEqualsApplyingEveryCurrentSettingRecommendation() {
		RulesDocument rules = ProfileFixtures.rules();
		for (Map.Entry<String, Fixtures.Hw> rig : rigs().entrySet()) {
			for (List<String> mods : ProfileFixtures.MOD_SETS) {
				for (boolean maxed : new boolean[] {false, true}) {
					SettingsSnapshot snapshot = ProfileFixtures.snapshot(mods, maxed);
					HardwareProfile hw = rig.getValue().build();
					Map<String, String> applied = new LinkedHashMap<>(snapshot.values());
					for (Recommendation rec : Recommender.recommend(rules, hw, Fixtures.mods(mods.toArray(String[]::new)), snapshot,
							OnlineData.offline(), Goal.BALANCED).recommendations()) {
						if (rec.action() instanceof Action.SetSetting set) {
							applied.put(set.key(), set.newValue());
						}
					}
					Map<String, String> balanced = compute(TemplateId.BALANCED, rig.getValue(), mods, snapshot).values();
					String where = rig.getKey() + " " + mods + " " + maxed;
					balanced.forEach((key, value) -> assertTrue(!snapshot.has(key) || SettingValues.same(applied.get(key), value),
							where + " " + key + ": " + value + " vs " + applied.get(key)));
					applied.forEach((key, value) -> {
						if (ShareKeys.managed(key)) {
							assertTrue(balanced.containsKey(key), where + " " + key);
						}
					});
				}
			}
		}
	}

	@Test
	void aMissingSectionFallsBackToTheBundledOneThenTheBuiltInOne() throws IOException {
		RulesDocument withSection = rulesWithSection();
		RulesDocument without = ProfileFixtures.rules();
		SettingsSnapshot snapshot = ProfileFixtures.snapshot(List.of(), false);
		HardwareProfile hw = Fixtures.userRig().build();
		// The active rules lack the section: the bundled section's max_fps (maxFps 250 in the fixture) applies.
		assertEquals("250", ProfileTemplates.compute(TemplateId.MAX_FPS, without, withSection, hw, List.of(), snapshot, snapshot.values())
				.values().get(FPS));
		// The active rules have it: theirs.
		assertEquals("250", ProfileTemplates.compute(TemplateId.MAX_FPS, withSection, without, hw, List.of(), snapshot, snapshot.values())
				.values().get(FPS));
		// Neither: the built-in copy (260).
		assertEquals("260", ProfileTemplates.compute(TemplateId.MAX_FPS, without, without, hw, List.of(), snapshot, snapshot.values())
				.values().get(FPS));
		// A template the section doesn't define, or one with an unknown `requires`, comes from the next source.
		assertEquals("60", ProfileTemplates.compute(TemplateId.BATTERY, withSection, null, hw, List.of(), snapshot, snapshot.values())
				.values().get(FPS));
		assertEquals(null, ProfileTemplates.definition(TemplateId.QUALITY, withSection, null).requires);
	}

	@Test
	void savedAndImportedProfilesAreClampedAndEachClampIsListed() {
		Fixtures.Hw hw = rigs().get("smallHeap");
		Map<String, String> imported = Map.of(RD, "32", FPS, "260", "vanilla.simulationDistance", "32");
		SettingsSnapshot snapshot = ProfileFixtures.snapshot(List.of(), false);
		Result result = ProfileTemplates.clamp(imported, ProfileFixtures.rules(), hw.build(), List.of(), snapshot, Goal.BALANCED);
		assertEquals("8", result.values().get(RD));
		assertEquals("260", result.values().get(FPS));
		assertTrue(result.clamps().stream().anyMatch(c -> c.key().equals(RD) && c.from().equals("32") && c.to().equals("8")
				&& c.reason().contains("2 GB")), result.clamps().toString());
		// A key the profile doesn't hold is never added by a clamp.
		assertFalse(ProfileTemplates.clamp(Map.of(FPS, "120"), ProfileFixtures.rules(), hw.build(), List.of(), snapshot, Goal.BALANCED).values()
				.containsKey(RD));
	}

	private static RulesDocument rulesWithSection() throws IOException {
		try (InputStream in = ProfileTemplatesTest.class.getResourceAsStream("/recommend/golden/rules-v2-r13.json")) {
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
			String section = """
					, "profileTemplates": {"templates": [
					  {"id": "max_fps", "goal": "performance", "settings": [{"key": "vanilla.maxFps", "value": 250}]},
					  {"id": "quality", "goal": "quality", "requires": ["time-travel"], "settings": [{"key": "vanilla.maxFps", "value": 30}]}
					]}}""";
			return RulesLoader.parse(json.substring(0, json.length() - 1) + section);
		}
	}
}
