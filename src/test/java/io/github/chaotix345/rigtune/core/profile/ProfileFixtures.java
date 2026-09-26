package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Settings snapshots and rules for the profile tests: every managed key a vanilla/Sodium/DH/Iris instance has, at each mod's
// defaults (or maxed out), and the rules pinned for the golden report (r13, no profileTemplates section).
final class ProfileFixtures {
	static final Map<String, String> VANILLA = ShareCodeTest.ordered(
			"vanilla.renderDistance", "12", "vanilla.simulationDistance", "12", "vanilla.entityDistanceScaling", "1.0",
			"vanilla.graphicsPreset", "fancy", "vanilla.maxFps", "120", "vanilla.enableVsync", "true",
			"vanilla.inactivityFpsLimit", "afk", "vanilla.particles", "0", "vanilla.biomeBlendRadius", "2",
			"vanilla.weatherRadius", "10", "vanilla.textureFiltering", "0", "vanilla.renderClouds", "true",
			"vanilla.prioritizeChunkUpdates", "0", "vanilla.improvedTransparency", "false", "vanilla.entityShadows", "true",
			"vanilla.cutoutLeaves", "true");
	static final Map<String, String> VANILLA_MAXED = ShareCodeTest.ordered(
			"vanilla.renderDistance", "32", "vanilla.simulationDistance", "32", "vanilla.entityDistanceScaling", "5.0",
			"vanilla.graphicsPreset", "custom", "vanilla.maxFps", "260", "vanilla.enableVsync", "false",
			"vanilla.inactivityFpsLimit", "minimized", "vanilla.particles", "0", "vanilla.biomeBlendRadius", "7",
			"vanilla.weatherRadius", "10", "vanilla.textureFiltering", "2", "vanilla.renderClouds", "true",
			"vanilla.prioritizeChunkUpdates", "2", "vanilla.improvedTransparency", "true", "vanilla.entityShadows", "true",
			"vanilla.cutoutLeaves", "true");
	static final Map<String, String> SODIUM = ShareCodeTest.ordered(
			"sodium.performance.use_fog_occlusion", "true", "sodium.performance.use_block_face_culling", "true",
			"sodium.performance.use_entity_culling", "true", "sodium.performance.animate_only_visible_textures", "true",
			"sodium.performance.chunk_builder_threads", "0", "sodium.performance.chunk_build_defer_mode", "ONE_FRAME",
			"sodium.performance.quad_splitting_mode", "SAFE");
	static final Map<String, String> DH = ShareCodeTest.ordered(
			"dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256",
			"dh.client.advanced.graphics.quality.verticalQuality", "MEDIUM",
			"dh.client.advanced.graphics.quality.horizontalQuality", "MEDIUM",
			"dh.client.advanced.graphics.quality.maxHorizontalResolution", "BLOCK",
			"dh.common.multiThreading.numberOfThreads", "4", "dh.client.advanced.debugging.rendererMode", "DEFAULT");
	static final Map<String, String> IRIS = ShareCodeTest.ordered("iris.maxShadowRenderDistance", "32", "iris.enableShaders", "true",
			"iris.shaderPack", "ComplementaryReimagined_r5.9.3.zip");
	static final List<List<String>> MOD_SETS = List.of(List.of(), List.of("sodium"), List.of("sodium", "iris"),
			List.of("sodium", "distanthorizons"), List.of("sodium", "distanthorizons", "iris"));

	private ProfileFixtures() {
	}

	static SettingsSnapshot snapshot(List<String> mods, boolean maxed) {
		Map<String, String> values = new LinkedHashMap<>(maxed ? VANILLA_MAXED : VANILLA);
		if (mods.contains("sodium")) {
			values.putAll(SODIUM);
		}
		if (mods.contains("distanthorizons")) {
			values.putAll(DH);
		}
		if (mods.contains("iris")) {
			values.putAll(IRIS);
		}
		return new SettingsSnapshot(values);
	}

	static RulesDocument rules() {
		return resource("/recommend/golden/rules-v2-r13.json");
	}

	static RulesDocument resource(String path) {
		try (InputStream in = ProfileFixtures.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException(path);
			}
			return RulesLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
