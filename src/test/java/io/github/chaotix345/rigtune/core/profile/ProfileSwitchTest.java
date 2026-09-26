package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.5.
class ProfileSwitchTest {
	@Test
	void onlyPresentChangeableDifferentKeysTakePart() {
		SettingsSnapshot snapshot = ProfileFixtures.snapshot(List.of("sodium"), false);
		Map<String, String> target = new LinkedHashMap<>();
		target.put("vanilla.renderDistance", "8");
		target.put("vanilla.simulationDistance", "12.0");
		target.put("vanilla.graphicsPreset", "fast");
		target.put("iris.shaderPack", "evil.zip");
		target.put("sodium.performance.use_entity_culling", "false");
		target.put("sodium.performance.chunk_builder_threads", "4");
		target.put("vanilla.maxFps", "144");
		target.put("vanilla.cutoutLeaves", "fal\nse");
		target.put("minecraft.unknown", "1");
		List<Recommendation> recs = ProfileSwitch.build(target, snapshot, Set.of("sodium"), Map.of(), "Battery");
		assertEquals(Map.of("vanilla.renderDistance", "8", "sodium.performance.use_entity_culling", "false",
				"sodium.performance.chunk_builder_threads", "4"), values(recs));
		Recommendation rd = recs.getFirst();
		assertEquals("set:vanilla.renderDistance", rd.id());
		assertEquals(new Action.SetSetting("vanilla.renderDistance", "12", "8"), rd.action());
		assertEquals("Render distance: 12 → 8", rd.title());
		assertEquals("Part of the Battery profile.", rd.reason());
		assertTrue(recs.stream().allMatch(Recommendation::selectedByDefault));
	}

	@Test
	void valuesAreWrittenInTheTablesSpelling() {
		SettingsSnapshot snapshot = ProfileFixtures.snapshot(List.of("sodium"), false);
		List<Recommendation> recs = ProfileSwitch.build(Map.of("sodium.performance.chunk_build_defer_mode", "always", "vanilla.enableVsync", " FALSE",
				"vanilla.renderDistance", "\"9\""), snapshot, Set.of("sodium"), Map.of(), "x");
		assertEquals(Map.of("sodium.performance.chunk_build_defer_mode", "ALWAYS", "vanilla.enableVsync", "false", "vanilla.renderDistance", "9"),
				values(recs));
	}

	@Test
	void modKeysNeedTheirModLoadedAndAKeyIsNeverCreated() {
		SettingsSnapshot vanillaOnly = ProfileFixtures.snapshot(List.of(), false);
		Map<String, String> target = Map.of("sodium.performance.use_entity_culling", "false", "iris.enableShaders", "false",
				"dh.client.advanced.debugging.rendererMode", "DISABLED");
		assertEquals(List.of(), ProfileSwitch.build(target, vanillaOnly, Set.of("sodium", "iris", "distanthorizons"), Map.of(), "x"));
		SettingsSnapshot all = ProfileFixtures.snapshot(List.of("sodium", "distanthorizons", "iris"), false);
		assertEquals(List.of(), ProfileSwitch.build(target, all, Set.of(), Map.of(), "x"));
		assertEquals(Map.of("sodium.performance.use_entity_culling", "false"), values(ProfileSwitch.build(target, all, Set.of("sodium"), Map.of(), "x")));
		assertEquals(3, ProfileSwitch.build(target, all, Set.of("sodium", "iris", "distanthorizons"), Map.of(), "x").size());
	}

	private static Map<String, String> values(List<Recommendation> recs) {
		Map<String, String> out = new LinkedHashMap<>();
		recs.forEach(r -> {
			Action.SetSetting set = (Action.SetSetting) r.action();
			out.put(set.key(), set.newValue());
		});
		return out;
	}
}
