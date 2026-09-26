package io.github.chaotix345.rigtune.core.profile;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.1.
class ShareCodeTest {
	// The research prototype's codes (scratchpad/r-profiles/proto/sharecode.py, name "Charlie's Balanced"): vanilla only (15
	// keys), typical vanilla + Sodium + Iris (24 keys) and every key (30 keys).
	static final String GOLDEN_VANILLA = "RT1-ARJDaGFybGllJ3MgQmFsYW5jZWQPAAoBBQICAxAEAAUBBgAHAggHCQEKAgsADAANAQ4BkbMbHQ";
	static final String GOLDEN_TYPICAL =
			"RT1-ARJDaGFybGllJ3MgQmFsYW5jZWQYAAoBBQICAxAEAAUBBgAHAggHCQEKAgsADAANAQ4BDwEQAREBEgETABQBFQEcEB0B9kvlDA";
	static final String GOLDEN_FULL =
			"RT1-ARJDaGFybGllJ3MgQmFsYW5jZWQeAAoBBQICAxAEAAUBBgAHAggHCQEKAgsADAANAQ4BDwEQAREBEgETABQBFQEWgAEXAhgCGQIaAxsAHBAdAUQBLsM";
	static final List<String> GOLDEN = List.of(GOLDEN_VANILLA, GOLDEN_TYPICAL, GOLDEN_FULL);
	static final String NAME = "Charlie's Balanced";

	static final Map<String, String> VANILLA = ordered("vanilla.renderDistance", "12", "vanilla.simulationDistance", "10",
			"vanilla.entityDistanceScaling", "1.0", "vanilla.maxFps", "170", "vanilla.enableVsync", "false", "vanilla.inactivityFpsLimit", "afk",
			"vanilla.particles", "0", "vanilla.biomeBlendRadius", "2", "vanilla.weatherRadius", "10", "vanilla.textureFiltering", "1",
			"vanilla.renderClouds", "true", "vanilla.prioritizeChunkUpdates", "0", "vanilla.improvedTransparency", "false",
			"vanilla.entityShadows", "true", "vanilla.cutoutLeaves", "true");
	static final Map<String, String> SODIUM = ordered("sodium.performance.use_fog_occlusion", "true",
			"sodium.performance.use_block_face_culling", "true", "sodium.performance.use_entity_culling", "true",
			"sodium.performance.animate_only_visible_textures", "true", "sodium.performance.chunk_build_defer_mode", "ONE_FRAME",
			"sodium.performance.quad_splitting_mode", "SAFE");
	static final Map<String, String> DH = ordered("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "160",
			"dh.client.advanced.graphics.quality.verticalQuality", "MEDIUM", "dh.client.advanced.graphics.quality.horizontalQuality", "MEDIUM",
			"dh.client.advanced.graphics.quality.maxHorizontalResolution", "FOUR_BLOCKS", "dh.client.advanced.debugging.rendererMode", "DEFAULT");
	static final Map<String, String> IRIS = ordered("iris.maxShadowRenderDistance", "16", "iris.enableShaders", "true");

	@Test
	void theGoldenCodesDecodeToThePrototypesValues() throws ShareCodeException {
		assertEquals(List.of(78, 102, 119), GOLDEN.stream().map(String::length).toList());

		ShareCode.Decoded vanilla = ShareCode.decode(GOLDEN_VANILLA);
		assertEquals(NAME, vanilla.name());
		assertEquals(VANILLA, vanilla.values(144));
		assertEquals(0, vanilla.unknownKeys());
		assertEquals(0, vanilla.localOnlyKeys());

		// The prototype also sent the machine-specific thread counts; they are local-only here, so they're dropped.
		ShareCode.Decoded typical = ShareCode.decode(GOLDEN_TYPICAL);
		assertEquals(merge(VANILLA, SODIUM, IRIS), typical.values(144));
		assertEquals(1, typical.localOnlyKeys());

		ShareCode.Decoded full = ShareCode.decode(GOLDEN_FULL);
		assertEquals(merge(VANILLA, SODIUM, DH, IRIS), full.values(144));
		assertEquals(2, full.localOnlyKeys());
		assertEquals(0, full.unknownKeys());
	}

	@Test
	void everyKeyRoundTripsAtItsMinimumMaximumAndEveryEnumValue() throws ShareCodeException {
		for (ShareKeys.Key key : ShareKeys.V1) {
			if (!key.shareable()) {
				continue;
			}
			for (int wire = 0; wire <= key.maxWire(); wire++) {
				if (key.kind() != ShareKeys.Kind.ENUM && key.kind() != ShareKeys.Kind.BOOL && wire != 0 && wire != key.maxWire()) {
					continue;
				}
				String value = key.decode(wire, 60);
				String code = ShareCode.encode("x", Map.of(key.key(), value), -1);
				ShareCode.Decoded decoded = ShareCode.decode(code);
				assertEquals(Map.of(key.key(), value), decoded.values(-1), key.key() + " " + value);
				assertEquals(wire, decoded.entries().getFirst().wire());
			}
		}
		// All shareable keys at once, at their maximum.
		Map<String, String> all = new LinkedHashMap<>();
		ShareKeys.V1.stream().filter(ShareKeys.Key::shareable).forEach(k -> all.put(k.key(), k.decode(k.maxWire(), 60)));
		assertEquals(all, ShareCode.decode(ShareCode.encode("All", all, -1)).values(-1));
	}

	@Test
	void theEncoderReproducesThePrototypesVanillaCode() {
		assertEquals(GOLDEN_VANILLA, ShareCode.encode(NAME, VANILLA, -1));
		// A typical profile without the local-only thread count: 23 keys, 2 bytes shorter.
		String typical = ShareCode.encode(NAME, merge(VANILLA, SODIUM, IRIS, Map.of("sodium.performance.chunk_builder_threads", "0")), -1);
		assertEquals(99, typical.length());
	}

	@Test
	void matchTheDisplayResolvesPerImportingDisplay() throws ShareCodeException {
		// Sent from a 180 Hz display whose cap is 170: "match the display".
		String code = ShareCode.encode("Mine", Map.of("vanilla.maxFps", "170"), 180);
		ShareCode.Decoded decoded = ShareCode.decode(code);
		assertEquals(ShareKeys.MATCH_DISPLAY, decoded.entries().getFirst().wire());
		assertEquals("140", decoded.values(144).get("vanilla.maxFps"));
		assertEquals("60", decoded.values(60).get("vanilla.maxFps"));
		assertEquals("60", decoded.values(-1).get("vanilla.maxFps"));
		// Another number, or an unknown sender display: the number itself.
		assertEquals("120", ShareCode.decode(ShareCode.encode("Mine", Map.of("vanilla.maxFps", "120"), 180)).values(60).get("vanilla.maxFps"));
		assertEquals("170", ShareCode.decode(ShareCode.encode("Mine", Map.of("vanilla.maxFps", "170"), -1)).values(60).get("vanilla.maxFps"));
	}

	@Test
	void unshareableAndUnrepresentableValuesAreLeftOut() throws ShareCodeException {
		assertNull(ShareCode.encode("x", Map.of("sodium.performance.chunk_builder_threads", "4", "vanilla.graphicsPreset", "fancy",
				"iris.shaderPack", "BSL.zip", "vanilla.maxFps", "144"), -1));
		ShareCode.Decoded decoded = ShareCode.decode(ShareCode.encode("x", Map.of("vanilla.renderDistance", "12",
				"dh.common.multiThreading.numberOfThreads", "4"), -1));
		assertEquals(Map.of("vanilla.renderDistance", "12"), decoded.values(-1));
	}

	@Test
	void namesAreSanitisedAndFitTheBody() throws ShareCodeException {
		String code = ShareCode.encode("  ‮evil​ name\n=#\"  ", Map.of("vanilla.renderDistance", "8"), -1);
		assertEquals("evil name", ShareCode.decode(code).name());
		assertNull(ShareCode.decode(ShareCode.encode("​\n", Map.of("vanilla.renderDistance", "8"), -1)).name());
		String wide = "😀".repeat(40);
		String decoded = ShareCode.decode(ShareCode.encode(wide, Map.of("vanilla.renderDistance", "8"), -1)).name();
		assertEquals("😀".repeat(16), decoded);
	}

	@Test
	void whitespaceFromChatWrappingIsIgnored() throws ShareCodeException {
		String wrapped = GOLDEN_VANILLA.substring(0, 30) + "\r\n  " + GOLDEN_VANILLA.substring(30, 60) + "\t" + GOLDEN_VANILLA.substring(60) + "\n";
		assertEquals(VANILLA, ShareCode.decode(wrapped).values(60));
		assertThrows(ShareCodeException.class, () -> ShareCode.decode(GOLDEN_VANILLA.substring(0, 30) + " " + GOLDEN_VANILLA.substring(30)));
	}

	@Test
	void everyDecodedValueReEncodesToTheSameWireValue() throws ShareCodeException {
		for (String golden : GOLDEN) {
			ShareCode.Decoded decoded = ShareCode.decode(golden);
			for (ShareCode.Entry entry : decoded.entries()) {
				assertEquals(entry.wire(), entry.key().encode(entry.key().decode(entry.wire(), 60)), entry.key().key());
			}
		}
		assertTrue(ShareCode.decode(GOLDEN_FULL).entries().size() > 20);
	}

	@SafeVarargs
	static Map<String, String> merge(Map<String, String>... maps) {
		Map<String, String> out = new LinkedHashMap<>();
		for (Map<String, String> map : maps) {
			out.putAll(map);
		}
		out.remove("sodium.performance.chunk_builder_threads");
		return out;
	}

	static Map<String, String> ordered(String... keyValues) {
		Map<String, String> out = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			out.put(keyValues[i], keyValues[i + 1]);
		}
		return out;
	}
}
