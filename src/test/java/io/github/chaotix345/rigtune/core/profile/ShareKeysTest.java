package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.4: the v1 table is frozen and append-only.
class ShareKeysTest {
	// PINNED: index | key | kind | bounds or values | shareable. Never edit a line; a new key is a new line at the end.
	// never extend an existing key's range or enum; add a new key index (older decoders skip unknown indices): a 0.4.0
	// decoder rejects the whole code on an out-of-range known key (docs/v0.5/SPEC.md Latent 1).
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

	// docs/v0.5/SPEC.md AC2P.6: each key's wire range, pinned: index | smallest wire value | largest wire value (maxFps also
	// takes MATCH_DISPLAY). A 0.4.0 decoder rejects a whole code with a known key outside its own range, so none of these
	// ever changes.
	private static final List<String> PINNED_WIRE = List.of(
			"0|0|30", "1|0|27", "2|0|18", "3|0|25+26", "4|0|1", "5|0|1", "6|0|2", "7|0|7", "8|0|7", "9|0|2",
			"10|0|2", "11|0|2", "12|0|1", "13|0|1", "14|0|1", "15|0|1", "16|0|1", "17|0|1", "18|0|1", "19|0|32",
			"20|0|2", "21|0|2", "22|0|480", "23|0|6", "24|0|4", "25|0|4", "26|0|31", "27|0|1", "28|0|32", "29|0|1");

	@Test
	void everyKeysWireRangeIsPinned() {
		List<String> actual = ShareKeys.V1.stream().map(k -> k.index() + "|" + (k.validWire(0) ? 0 : -1) + "|" + k.maxWire()
				+ (k.validWire(ShareKeys.MATCH_DISPLAY) && ShareKeys.MATCH_DISPLAY > k.maxWire() ? "+" + ShareKeys.MATCH_DISPLAY : "")).toList();
		assertEquals(PINNED_WIRE, actual);
		for (ShareKeys.Key key : ShareKeys.V1) {
			assertFalse(key.validWire(-1), key.key());
			assertFalse(key.validWire(key.maxWire() + (key.maxWire() + 1 == ShareKeys.MATCH_DISPLAY && key.kind() == ShareKeys.Kind.INT10 ? 2 : 1)), key.key());
		}
	}

	// docs/v0.5/SPEC.md Latent 1 (AC2P.6): the table's comment and this test's pin comment both carry the rule, and neither
	// invites a value at the end of an existing enum any more. Only the files' comment lines are read.
	@Test
	void theLatent1RuleIsInBothComments() throws IOException {
		String rule = "never extend an existing key's range or enum; add a new key index (older decoders skip unknown indices)";
		for (String file : List.of("src/main/java/io/github/chaotix345/rigtune/core/profile/ShareKeys.java",
				"src/test/java/io/github/chaotix345/rigtune/core/profile/ShareKeysTest.java")) {
			String comments = String.join(" ", Files.readAllLines(RepoFiles.resolve(file)).stream().map(String::strip)
					.filter(line -> line.startsWith("//")).map(line -> line.substring(2).strip()).toList());
			assertTrue(comments.contains(rule), file + " carries the rule");
			assertFalse(comments.contains("at the end of an enum") || comments.contains("enum value goes at the end"), file + " no longer invites it");
		}
	}

	// docs/v0.5/SPEC.md PF-5 (AC2P.5): DH 3.3.2 allows an LOD radius of 32..4096, the wire only 32..512 (key 22 is never
	// widened). A profile holds the game's range; a code carries only the wire's.
	@Test
	void pf5TheDhRadiusHasALocalRangeWiderThanTheWire() {
		ShareKeys.Key radius = ShareKeys.byKey("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius");
		assertEquals("1024", radius.local("1024"));
		assertEquals("4096", radius.local(" \"4096\" "));
		assertEquals("512", radius.local("512"));
		assertEquals("32", radius.local("32"));
		assertNull(radius.encode("1024"), "the wire never carries it");
		assertNull(radius.local("5000"));
		assertNull(radius.local("31"));
		assertNull(radius.local("1024.5"));
		assertNull(radius.local("far"));
		// Every other key's local range is its wire range, in the table's spelling.
		ShareKeys.Key rd = ShareKeys.byKey("vanilla.renderDistance");
		assertNull(rd.local("33"));
		assertEquals("10", rd.local("1e1"));
		assertEquals("ALWAYS", ShareKeys.byKey("sodium.performance.chunk_build_defer_mode").local("always"));
		assertNull(ShareKeys.byKey("vanilla.maxFps").local("144"));
		for (ShareKeys.Key key : ShareKeys.V1) {
			for (int wire = 0; wire <= key.maxWire(); wire++) {
				assertEquals(key.decode(wire, 60), key.local(key.decode(wire, 60)), key.key());
			}
		}
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
