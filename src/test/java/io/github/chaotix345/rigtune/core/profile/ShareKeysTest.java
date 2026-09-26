package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.SettingKeys;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.4: the v1 table is frozen and append-only.
class ShareKeysTest {
	// PINNED: index | key | kind | bounds or values | shareable. Never edit a line; a new key is a new line at the end (and a new
	// enum value goes at the end of its list).
	private static final List<String> PINNED = List.of(
			"0|vanilla.renderDistance|INT|2..32|true",
			"1|vanilla.simulationDistance|INT|5..32|true",
			"2|vanilla.entityDistanceScaling|QUARTER|2..20|true",
			"3|vanilla.maxFps|INT10|10..260|true",
			"4|vanilla.enableVsync|BOOL|0..1|true",
			"5|vanilla.inactivityFpsLimit|ENUM|[minimized, afk]|true",
			"6|vanilla.particles|ENUM|[0, 1, 2]|true",
			"7|vanilla.biomeBlendRadius|INT|0..7|true",
			"8|vanilla.weatherRadius|INT|3..10|true",
			"9|vanilla.textureFiltering|ENUM|[0, 1, 2]|true",
			"10|vanilla.renderClouds|ENUM|[false, fast, true]|true",
			"11|vanilla.prioritizeChunkUpdates|ENUM|[0, 1, 2]|true",
			"12|vanilla.improvedTransparency|BOOL|0..1|true",
			"13|vanilla.entityShadows|BOOL|0..1|true",
			"14|vanilla.cutoutLeaves|BOOL|0..1|true",
			"15|sodium.performance.use_fog_occlusion|BOOL|0..1|true",
			"16|sodium.performance.use_block_face_culling|BOOL|0..1|true",
			"17|sodium.performance.use_entity_culling|BOOL|0..1|true",
			"18|sodium.performance.animate_only_visible_textures|BOOL|0..1|true",
			"19|sodium.performance.chunk_builder_threads|INT|0..32|false",
			"20|sodium.performance.chunk_build_defer_mode|ENUM|[ALWAYS, ONE_FRAME, ZERO_FRAMES]|true",
			"21|sodium.performance.quad_splitting_mode|ENUM|[OFF, SAFE, UNLIMITED]|true",
			"22|dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius|INT|32..512|true",
			"23|dh.client.advanced.graphics.quality.verticalQuality|ENUM|[HEIGHT_MAP, LOW, MEDIUM, HIGH, VERY_HIGH, EXTREME, PIXEL_ART]|true",
			"24|dh.client.advanced.graphics.quality.horizontalQuality|ENUM|[LOWEST, LOW, MEDIUM, HIGH, EXTREME]|true",
			"25|dh.client.advanced.graphics.quality.maxHorizontalResolution|ENUM|[CHUNK, HALF_CHUNK, FOUR_BLOCKS, TWO_BLOCKS, BLOCK]|true",
			"26|dh.common.multiThreading.numberOfThreads|INT|1..32|false",
			"27|dh.client.advanced.debugging.rendererMode|ENUM|[DEFAULT, DISABLED]|true",
			"28|iris.maxShadowRenderDistance|INT|0..32|true",
			"29|iris.enableShaders|BOOL|0..1|true");

	@Test
	void theV1TableEqualsThePinnedList() {
		List<String> actual = ShareKeys.V1.stream().map(k -> k.index() + "|" + k.key() + "|" + k.kind() + "|"
				+ (k.kind() == ShareKeys.Kind.ENUM ? k.values().toString() : k.min() + ".." + k.max()) + "|" + k.shareable()).toList();
		assertEquals(PINNED, actual);
		for (int i = 0; i < ShareKeys.V1.size(); i++) {
			assertEquals(i, ShareKeys.V1.get(i).index());
			assertEquals(ShareKeys.V1.get(i), ShareKeys.byIndex(i));
			assertEquals(ShareKeys.V1.get(i), ShareKeys.byKey(ShareKeys.V1.get(i).key()));
		}
		assertNull(ShareKeys.byIndex(ShareKeys.V1.size()));
		assertNull(ShareKeys.byIndex(-1));
	}

	@Test
	void everyKeyIsChangeableAndTheExcludedOnesAreNotShareable() {
		for (ShareKeys.Key key : ShareKeys.V1) {
			assertTrue(SettingKeys.changeable(key.key()), key.key());
		}
		assertFalse(ShareKeys.managed("vanilla.graphicsPreset"));
		assertFalse(ShareKeys.managed("iris.shaderPack"));
		assertFalse(ShareKeys.byKey("sodium.performance.chunk_builder_threads").shareable());
		assertFalse(ShareKeys.byKey("dh.common.multiThreading.numberOfThreads").shareable());
		assertEquals(28, ShareKeys.V1.stream().filter(ShareKeys.Key::shareable).count());
		assertEquals(30, ShareKeys.MANAGED.size());
	}

	@Test
	void everyWireValueRoundTrips() {
		for (ShareKeys.Key key : ShareKeys.V1) {
			for (int wire = 0; wire <= key.maxWire(); wire++) {
				String value = key.decode(wire, 60);
				assertEquals(wire, key.encode(value), key.key() + " " + value);
			}
			assertFalse(key.validWire(key.maxWire() + 1 == ShareKeys.MATCH_DISPLAY ? key.maxWire() + 2 : key.maxWire() + 1), key.key());
			assertFalse(key.validWire(-1), key.key());
		}
	}

	@Test
	void valuesOutsideAKeyAreNotEncoded() {
		ShareKeys.Key rd = ShareKeys.byKey("vanilla.renderDistance");
		assertNull(rd.encode("1"));
		assertNull(rd.encode("33"));
		assertNull(rd.encode("12.5"));
		assertNull(rd.encode("twelve"));
		assertEquals(10, rd.encode("12"));
		assertEquals(10, rd.encode(" \"12\" "));
		ShareKeys.Key fps = ShareKeys.byKey("vanilla.maxFps");
		assertNull(fps.encode("144"));
		assertNull(fps.encode("270"));
		assertEquals(25, fps.encode("260"));
		assertEquals(0, fps.encode("10"));
		ShareKeys.Key scale = ShareKeys.byKey("vanilla.entityDistanceScaling");
		assertEquals(2, scale.encode("1.0"));
		assertEquals(1, scale.encode("0.75"));
		assertNull(scale.encode("0.8"));
		assertNull(scale.encode("NaN"));
		assertNull(scale.encode("Infinity"));
		assertEquals("0.75", scale.decode(1, 60));
		assertEquals("5.0", scale.decode(18, 60));
		ShareKeys.Key vsync = ShareKeys.byKey("vanilla.enableVsync");
		assertEquals(1, vsync.encode("TRUE"));
		assertNull(vsync.encode("1"));
		ShareKeys.Key renderer = ShareKeys.byKey("dh.client.advanced.debugging.rendererMode");
		assertNull(renderer.encode("DEBUG_TRIANGLE"));
		assertEquals(1, renderer.encode("\"DISABLED\""));
		assertEquals("140", fps.decode(ShareKeys.MATCH_DISPLAY, 140));
	}
}
