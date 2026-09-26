package io.github.chaotix345.rigtune.core.recommend;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.OnlineData;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

// docs/v0.4/SPEC.md AC4.6 / plan review X-M3: Recommender.recommend's whole output over a hardware x mods x goal x
// settings matrix, against a golden file written before the settingTargets extraction. The rules are a pinned copy of the
// bundled rules-v2.json (r13), so a rules regeneration never changes the golden; only a Recommender change can.
class RecommenderGoldenReportTest {
	private static final String RULES = "/recommend/golden/rules-v2-r13.json";
	private static final String GOLDEN = "/recommend/golden/report.txt";

	private static final Map<String, String> VANILLA_DEFAULTS = ordered(
			"vanilla.renderDistance", "12", "vanilla.simulationDistance", "12", "vanilla.entityDistanceScaling", "1.0",
			"vanilla.graphicsPreset", "fancy", "vanilla.maxFps", "120", "vanilla.enableVsync", "true",
			"vanilla.inactivityFpsLimit", "afk", "vanilla.particles", "0", "vanilla.biomeBlendRadius", "2",
			"vanilla.weatherRadius", "10", "vanilla.textureFiltering", "0", "vanilla.renderClouds", "true",
			"vanilla.prioritizeChunkUpdates", "0", "vanilla.improvedTransparency", "false", "vanilla.entityShadows", "true",
			"vanilla.cutoutLeaves", "true");
	private static final Map<String, String> VANILLA_MAXED = ordered(
			"vanilla.renderDistance", "32", "vanilla.simulationDistance", "32", "vanilla.entityDistanceScaling", "5.0",
			"vanilla.graphicsPreset", "custom", "vanilla.maxFps", "260", "vanilla.enableVsync", "false",
			"vanilla.inactivityFpsLimit", "minimized", "vanilla.particles", "0", "vanilla.biomeBlendRadius", "7",
			"vanilla.weatherRadius", "10", "vanilla.textureFiltering", "2", "vanilla.renderClouds", "true",
			"vanilla.prioritizeChunkUpdates", "2", "vanilla.improvedTransparency", "true", "vanilla.entityShadows", "true",
			"vanilla.cutoutLeaves", "true");
	private static final Map<String, String> SODIUM_DEFAULTS = ordered(
			"sodium.performance.use_fog_occlusion", "true", "sodium.performance.use_block_face_culling", "true",
			"sodium.performance.use_entity_culling", "true", "sodium.performance.animate_only_visible_textures", "true",
			"sodium.performance.chunk_builder_threads", "0", "sodium.performance.chunk_build_defer_mode", "ONE_FRAME",
			"sodium.performance.quad_splitting_mode", "SAFE");
	private static final Map<String, String> SODIUM_MAXED = ordered(
			"sodium.performance.use_fog_occlusion", "false", "sodium.performance.use_block_face_culling", "false",
			"sodium.performance.use_entity_culling", "false", "sodium.performance.animate_only_visible_textures", "false",
			"sodium.performance.chunk_builder_threads", "16", "sodium.performance.chunk_build_defer_mode", "ZERO_FRAMES",
			"sodium.performance.quad_splitting_mode", "UNLIMITED");
	private static final Map<String, String> DH_DEFAULTS = ordered(
			"dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "256",
			"dh.client.advanced.graphics.quality.verticalQuality", "MEDIUM",
			"dh.client.advanced.graphics.quality.horizontalQuality", "MEDIUM",
			"dh.client.advanced.graphics.quality.maxHorizontalResolution", "BLOCK",
			"dh.common.multiThreading.numberOfThreads", "4", "dh.client.advanced.debugging.rendererMode", "DEFAULT");
	private static final Map<String, String> DH_MAXED = ordered(
			"dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "512",
			"dh.client.advanced.graphics.quality.verticalQuality", "EXTREME",
			"dh.client.advanced.graphics.quality.horizontalQuality", "EXTREME",
			"dh.client.advanced.graphics.quality.maxHorizontalResolution", "BLOCK",
			"dh.common.multiThreading.numberOfThreads", "16", "dh.client.advanced.debugging.rendererMode", "DEFAULT");
	private static final Map<String, String> IRIS_DEFAULTS = ordered("iris.maxShadowRenderDistance", "32", "iris.enableShaders", "true",
			"iris.shaderPack", "ComplementaryReimagined_r5.9.3.zip");
	private static final Map<String, String> IRIS_MAXED = ordered("iris.maxShadowRenderDistance", "32", "iris.enableShaders", "true",
			"iris.shaderPack", "BSL_v10.1.8.zip");

	private static final List<List<String>> MOD_SETS = List.of(List.of(), List.of("sodium"), List.of("sodium", "iris"),
			List.of("sodium", "distanthorizons"), List.of("sodium", "distanthorizons", "iris"));

	@Test
	void recommendOutputMatchesTheGolden() throws IOException {
		String actual = render();
		String expected;
		try (InputStream in = RecommenderGoldenReportTest.class.getResourceAsStream(GOLDEN)) {
			expected = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		}
		if (expected != null && expected.equals(actual)) {
			return;
		}
		Path out = Path.of("recommender-golden-report.actual.txt").toAbsolutePath();
		Files.writeString(out, actual, StandardCharsets.UTF_8);
		if (expected == null) {
			fail("No golden file " + GOLDEN + "; the current output is in " + out);
		}
		String[] a = actual.split("\n", -1);
		String[] e = expected.split("\n", -1);
		for (int i = 0; i < Math.min(a.length, e.length); i++) {
			if (!a[i].equals(e[i])) {
				fail("recommend() output changed at line " + (i + 1) + "\nexpected: " + e[i] + "\nactual:   " + a[i] + "\nfull output: " + out);
			}
		}
		fail("recommend() output changed length (" + e.length + " -> " + a.length + " lines); full output: " + out);
	}

	static RulesDocument goldenRules() throws IOException {
		try (InputStream in = RecommenderGoldenReportTest.class.getResourceAsStream(RULES)) {
			assertNotNull(in, RULES);
			return RulesLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	static Map<String, Fixtures.Hw> hardware() {
		Map<String, Fixtures.Hw> hw = new LinkedHashMap<>();
		hw.put("userRig", Fixtures.userRig());
		hw.put("laptopOnBattery", Fixtures.lowEndLaptop());
		Fixtures.Hw pluggedIn = Fixtures.lowEndLaptop();
		pluggedIn.onBattery = false;
		hw.put("laptopPluggedIn", pluggedIn);
		Fixtures.Hw tier1 = Fixtures.lowEndLaptop();
		tier1.heapMb = 1024;
		hw.put("laptopTinyHeap", tier1);
		Fixtures.Hw tier3 = Fixtures.userRig().gpu("ATI Technologies Inc.", "AMD Radeon RX 5700 XT");
		tier3.display = new DisplayInfo(1920, 1080, -1, true);
		hw.put("tier3UnknownRefresh", tier3);
		Fixtures.Hw tier4 = Fixtures.userRig();
		tier4.heapMb = 4096;
		tier4.display = new DisplayInfo(2560, 1440, 144, true);
		hw.put("tier4Heap144Hz", tier4);
		Fixtures.Hw unknown = Fixtures.userRig();
		unknown.cpu = new CpuInfo("Mystery CPU 9000", 6, 12, -1);
		unknown.gpu = new GpuInfo("Mystery Graphics Co.", "Mystery GPU X", "1.0", GraphicsBackend.OPENGL, -1);
		unknown.ramMb = 16384;
		unknown.heapMb = 3000;
		unknown.display = new DisplayInfo(1920, 1080, 75, false);
		hw.put("unrecognised75Hz", unknown);
		Fixtures.Hw shaders = Fixtures.userRig().gpu("NVIDIA Corporation", "NVIDIA GeForce RTX 2060");
		shaders.heapMb = 2100;
		shaders.display = new DisplayInfo(1920, 1080, 50, true);
		shaders.flags = Set.of("shaders-enabled");
		hw.put("rtx2060SmallHeap50HzShaders", shaders);
		return hw;
	}

	static SettingsSnapshot snapshot(List<String> mods, boolean maxed) {
		Map<String, String> values = new LinkedHashMap<>(maxed ? VANILLA_MAXED : VANILLA_DEFAULTS);
		if (mods.contains("sodium")) {
			values.putAll(maxed ? SODIUM_MAXED : SODIUM_DEFAULTS);
		}
		if (mods.contains("distanthorizons")) {
			values.putAll(maxed ? DH_MAXED : DH_DEFAULTS);
		}
		if (mods.contains("iris")) {
			values.putAll(maxed ? IRIS_MAXED : IRIS_DEFAULTS);
		}
		return new SettingsSnapshot(values);
	}

	private static String render() throws IOException {
		RulesDocument rules = goldenRules();
		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, Fixtures.Hw> hw : hardware().entrySet()) {
			for (List<String> mods : MOD_SETS) {
				for (Goal goal : Goal.values()) {
					for (boolean maxed : new boolean[] {false, true}) {
						Report report = Recommender.recommend(rules, hw.getValue().build(), Fixtures.mods(mods.toArray(String[]::new)),
								snapshot(mods, maxed), OnlineData.offline(), goal, "0.4.0-dev+mc26.2", Set.of());
						out.append("## ").append(hw.getKey()).append(" mods=").append(mods).append(" goal=").append(goal)
								.append(" settings=").append(maxed ? "maxed" : "defaults").append('\n');
						out.append("tier ").append(report.tier()).append(" gpu ").append(report.gpuClass()).append('\n');
						for (Recommendation rec : report.recommendations()) {
							out.append(rec.id()).append(" | ").append(rec.category()).append(" | ").append(rec.impact()).append(" | ")
									.append(rec.selectedByDefault() ? "ticked" : "unticked").append(" | ").append(rec.title())
									.append(" | ").append(rec.reason()).append(" | ").append(action(rec.action())).append('\n');
						}
					}
				}
			}
		}
		return out.toString();
	}

	private static String action(Action action) {
		return switch (action) {
			case Action.SetSetting set -> "set " + set.key() + " " + set.currentValue() + " -> " + set.newValue();
			case Action.DisableMod disable -> "disable " + disable.modId() + " " + disable.file().getFileName();
			case Action.AddMod add -> "add " + add.slug() + " " + add.projectId();
			case Action.UpdateMod update -> "update " + update.modId();
			case Action.None none -> "none";
		};
	}

	private static Map<String, String> ordered(String... keyValues) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			map.put(keyValues[i], keyValues[i + 1]);
		}
		return map;
	}
}
