package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.v010.core.model.Action;
import io.github.chaotix345.rigtune.v010.core.model.CpuInfo;
import io.github.chaotix345.rigtune.v010.core.model.DisplayInfo;
import io.github.chaotix345.rigtune.v010.core.model.Goal;
import io.github.chaotix345.rigtune.v010.core.model.GpuInfo;
import io.github.chaotix345.rigtune.v010.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.v010.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.v010.core.model.InstalledMod;
import io.github.chaotix345.rigtune.v010.core.model.OnlineData;
import io.github.chaotix345.rigtune.v010.core.model.Recommendation;
import io.github.chaotix345.rigtune.v010.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.v010.core.recommend.Recommender;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a pinned copy of the v0.1.0 Recommender (package io.github.chaotix345.rigtune.v010, copied from tag v0.1.0)
 * over the baseline rules-v1.json (the one 0.1.0 shipped) and the repository's rules-v1.json, on a hardware × mods ×
 * goal × settings matrix. rules-v1.json may only take ticked actions away from a 0.1.x user, never add one (plan
 * review H2). If a new ticked action is intended, review it and replace src/test/resources/v010/rules-v1-baseline.json
 * with rules/rules-v1.json (tools/README.md).
 */
class RulesV1DifferentialTest {
	private static final String BASELINE = "/v010/rules-v1-baseline.json";

	private static String baselineJson() throws IOException {
		try (InputStream in = RulesV1DifferentialTest.class.getResourceAsStream(BASELINE)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String repoJson(String file) throws IOException {
		return Files.readString(RepoFiles.resolve("rules/" + file), StandardCharsets.UTF_8);
	}

	private static io.github.chaotix345.rigtune.v010.core.rules.RulesDocument v010(String json) {
		return io.github.chaotix345.rigtune.v010.core.rules.RulesLoader.parse(json);
	}

	@Test
	void v010ParserAcceptsRepoRulesV1() throws IOException {
		io.github.chaotix345.rigtune.v010.core.rules.RulesDocument doc = v010(repoJson("rules-v1.json"));
		assertEquals(1, doc.schemaVersion);
		assertFalse(doc.mods.isEmpty());
		assertFalse(doc.settings.isEmpty());
	}

	@Test
	void v010ParserRejectsRulesV2() throws IOException {
		String v2 = repoJson("rules-v2.json");
		assertThrows(IllegalArgumentException.class, () -> v010(v2));
	}

	@Test
	void repoRulesV1AddsNoTickedActionOverTheBaseline() throws IOException {
		List<String> added = newTickedActions(baselineJson(), repoJson("rules-v1.json"));
		assertTrue(added.isEmpty(), "rules-v1.json gives 0.1.x users ticked actions the baseline didn't:\n" + String.join("\n", added));
	}

	@Test
	void theDifferentialCatchesANewTickedAction() throws IOException {
		JsonObject doc = JsonParser.parseString(baselineJson()).getAsJsonObject();
		JsonObject entry = new JsonObject();
		entry.addProperty("key", "vanilla.renderDistance");
		entry.addProperty("value", 3);
		entry.addProperty("reason", "Injected by the test.");
		doc.getAsJsonArray("settings").add(entry);
		List<String> added = newTickedActions(baselineJson(), doc.toString());
		assertFalse(added.isEmpty());
		assertTrue(added.getFirst().contains("set:vanilla.renderDistance=3"), added.getFirst());
	}

	@Test
	void theMatrixReachesEveryKindOfAction() throws IOException {
		JsonObject empty = JsonParser.parseString(baselineJson()).getAsJsonObject();
		for (String section : List.of("mods", "obsolete", "settings", "advice")) {
			empty.add(section, new JsonArray());
		}
		Set<String> kinds = new TreeSet<>();
		for (String action : newTickedActions(empty.toString(), baselineJson())) {
			kinds.add(action.substring(0, action.indexOf(':')));
		}
		assertEquals(Set.of("add", "disable", "set"), kinds);
	}

	@Test
	void removedActionsAreAllowed() throws IOException {
		JsonObject doc = JsonParser.parseString(baselineJson()).getAsJsonObject();
		doc.add("mods", new JsonArray());
		doc.add("settings", new JsonArray());
		assertEquals(List.of(), newTickedActions(baselineJson(), doc.toString()));
	}

	// Every ticked, appliable recommendation the new rules give that the old rules don't, with the matrix point.
	static List<String> newTickedActions(String oldJson, String newJson) {
		io.github.chaotix345.rigtune.v010.core.rules.RulesDocument oldRules = v010(oldJson);
		io.github.chaotix345.rigtune.v010.core.rules.RulesDocument newRules = v010(newJson);
		Set<String> slugs = new TreeSet<>();
		Set<String> modIds = new TreeSet<>();
		Set<String> settingKeys = new TreeSet<>();
		for (io.github.chaotix345.rigtune.v010.core.rules.RulesDocument rules : List.of(oldRules, newRules)) {
			rules.mods.forEach(m -> {
				slugs.add(m.slug);
				modIds.addAll(m.modIds);
			});
			rules.obsolete.forEach(o -> modIds.addAll(o.modIds));
			rules.settings.forEach(s -> settingKeys.add(s.key));
		}
		Map<String, Boolean> available = new LinkedHashMap<>();
		slugs.forEach(slug -> available.put(slug, true));
		OnlineData online = new OnlineData(true, available, Map.of());

		List<String> added = new ArrayList<>();
		Map<String, HardwareProfile> hardware = hardware();
		for (Map.Entry<String, HardwareProfile> hw : hardware.entrySet()) {
			for (Map.Entry<String, List<String>> mods : modSets(modIds).entrySet()) {
				List<InstalledMod> installed = mods.getValue().stream().map(RulesV1DifferentialTest::mod).toList();
				boolean sodium = mods.getValue().contains("sodium");
				for (Map.Entry<String, Map<String, String>> settings : snapshots(settingKeys, sodium).entrySet()) {
					for (Goal goal : Goal.values()) {
						Set<String> before = ticked(oldRules, hw.getValue(), installed, settings.getValue(), online, goal);
						for (String action : ticked(newRules, hw.getValue(), installed, settings.getValue(), online, goal)) {
							if (!before.contains(action)) {
								added.add(action + " on " + hw.getKey() + " / mods " + mods.getKey() + " / settings " + settings.getKey() + " / " + goal);
							}
						}
					}
				}
			}
		}
		return added;
	}

	private static Set<String> ticked(io.github.chaotix345.rigtune.v010.core.rules.RulesDocument rules, HardwareProfile hw,
			List<InstalledMod> mods, Map<String, String> settings, OnlineData online, Goal goal) {
		Set<String> out = new TreeSet<>();
		for (Recommendation r : Recommender.recommend(rules, hw, mods, new SettingsSnapshot(settings), online, goal).recommendations()) {
			if (r.selectedByDefault() && !(r.action() instanceof Action.None)) {
				out.add(r.action() instanceof Action.SetSetting set ? r.id() + "=" + set.newValue() : r.id());
			}
		}
		return out;
	}

	private static InstalledMod mod(String id) {
		return new InstalledMod(id, id, "1.0.0", Path.of("mods", id + ".jar"), "0000" + id);
	}

	private static HardwareProfile hw(String cpu, int cores, int threads, long mhz, String gpuVendor, String renderer,
			GraphicsBackend backend, long vramMb, long ramMb, long heapMb, DisplayInfo display, boolean battery, boolean onBattery,
			String os, String mc, Set<String> flags) {
		return new HardwareProfile(new CpuInfo(cpu, cores, threads, mhz), new GpuInfo(gpuVendor, renderer, "1.0", backend, vramMb),
				ramMb, heapMb, display, battery, onBattery, os, mc, flags);
	}

	private static Map<String, HardwareProfile> hardware() {
		GraphicsBackend gl = GraphicsBackend.OPENGL;
		Map<String, HardwareProfile> out = new LinkedHashMap<>();
		out.put("user-rig-amd", hw("AMD Ryzen 7 7800X3D 8-Core Processor", 8, 16, 4201, "ATI Technologies Inc.", "AMD Radeon RX 7800 XT", gl,
				16384, 32768, 6144, new DisplayInfo(2560, 1440, 180, true), false, false, "Windows 11", "26.2", Set.of()));
		out.put("low-end-laptop", hw("Intel(R) Core(TM) i5-8250U CPU @ 1.60GHz", 4, 8, -1, "Intel", "Intel(R) UHD Graphics 620", gl,
				-1, 8192, 2048, new DisplayInfo(1920, 1080, 60, true), true, true, "Windows 10", "26.3", Set.of()));
		out.put("rtx-2060", hw("Intel(R) Core(TM) i5-10400F CPU @ 2.90GHz", 6, 12, 4300, "NVIDIA Corporation", "NVIDIA GeForce RTX 2060/PCIe/SSE2", gl,
				6144, 16384, 4096, new DisplayInfo(1920, 1080, 144, true), false, false, "Windows 10", "26.2", Set.of()));
		out.put("gtx-1080", hw("AMD Ryzen 5 3600 6-Core Processor", 6, 12, 4200, "NVIDIA Corporation", "NVIDIA GeForce GTX 1080/PCIe/SSE2", gl,
				8192, 16384, 4096, new DisplayInfo(2560, 1440, 144, true), false, false, "Windows 10", "26.3", Set.of()));
		out.put("rtx-4090", hw("AMD Ryzen 9 7950X3D 16-Core Processor", 16, 32, 5700, "NVIDIA Corporation", "NVIDIA GeForce RTX 4090/PCIe/SSE2", gl,
				24576, 65536, 8192, new DisplayInfo(3840, 2160, 120, true), false, false, "Windows 11", "26.2", Set.of()));
		out.put("gtx-1660-laptop", hw("Intel(R) Core(TM) i7-9750H CPU @ 2.60GHz", 6, 12, 4500, "NVIDIA Corporation",
				"NVIDIA GeForce GTX 1660 Ti with Max-Q Design/PCIe/SSE2", gl, 6144, 16384, 3072, new DisplayInfo(1920, 1080, 144, true),
				true, false, "Windows 11", "26.3", Set.of()));
		out.put("no-gpu-info", hw("unknown", -1, 4, -1, "", "", GraphicsBackend.UNKNOWN, -1, -1, 2048, new DisplayInfo(-1, -1, -1, false),
				false, false, "Linux", "26.2", Set.of()));
		out.put("apple-m1", hw("Apple M1", 8, 8, -1, "Apple", "Apple M1", gl, -1, 16384, 4096, new DisplayInfo(2560, 1600, 60, true),
				true, false, "Mac OS X", "26.3", Set.of()));
		out.put("amd-igpu-linux", hw("AMD Ryzen 5 5600G with Radeon Graphics", 6, 12, 4400, "AMD", "AMD Radeon(TM) Graphics", gl,
				-1, 16384, 3072, new DisplayInfo(1920, 1080, 60, true), false, false, "Linux", "26.2", Set.of()));
		out.put("llvmpipe", hw("Intel(R) Xeon(R) CPU E5-2680 v4 @ 2.40GHz", 2, 4, 2400, "Mesa", "llvmpipe (LLVM 15.0.7, 256 bits)", gl,
				-1, 8192, 2048, new DisplayInfo(1280, 720, 60, false), false, false, "Linux", "26.3", Set.of()));
		out.put("intel-arc", hw("13th Gen Intel(R) Core(TM) i7-13700K", 16, 24, 5400, "Intel", "Intel(R) Arc(TM) A770 Graphics", gl,
				16384, 32768, 6144, new DisplayInfo(2560, 1440, 165, true), false, false, "Windows 11", "26.2", Set.of()));
		out.put("rtx-3070-shaders-vulkan", hw("AMD Ryzen 7 5800X 8-Core Processor", 8, 16, 4700, "NVIDIA Corporation",
				"NVIDIA GeForce RTX 3070", GraphicsBackend.VULKAN, 8192, 32768, 8192, new DisplayInfo(2560, 1440, 165, true), false, false,
				"Windows 11", "26.3", Set.of("shaders-enabled", "backend-vulkan", "sodium-workaround:NVIDIA_THREADED_OPTIMIZATIONS_BROKEN")));
		return out;
	}

	private static Map<String, List<String>> modSets(Set<String> everyModId) {
		Map<String, List<String>> out = new LinkedHashMap<>();
		out.put("none", List.of());
		out.put("fabric-api", List.of("fabric-api"));
		out.put("sodium", List.of("sodium"));
		out.put("sodium+iris", List.of("sodium", "iris"));
		out.put("sodium+lithium+ferritecore", List.of("sodium", "lithium", "ferritecore"));
		out.put("sodium+nvidium", List.of("sodium", "nvidium"));
		out.put("sodium+iris+nvidium", List.of("sodium", "iris", "nvidium"));
		out.put("optifabric", List.of("optifabric"));
		out.put("indium+sodium", List.of("indium", "sodium"));
		out.put("distanthorizons+sodium+iris", List.of("distanthorizons", "sodium", "iris"));
		out.put("lambdynlights+sodium", List.of("lambdynlights", "sodium"));
		out.put("every tracked mod", List.copyOf(everyModId));
		return out;
	}

	private static Map<String, Map<String, String>> snapshots(Set<String> settingKeys, boolean sodium) {
		Map<String, String> zeros = new LinkedHashMap<>();
		Map<String, String> high = new LinkedHashMap<>();
		for (String key : settingKeys) {
			if (sodium || !key.startsWith("sodium.")) {
				zeros.put(key, "0");
				high.put(key, "64");
			}
		}
		Map<String, String> defaults = new LinkedHashMap<>(zeros);
		Stream.of("renderDistance=12", "simulationDistance=12", "maxFps=120", "enableVsync=true", "graphicsPreset=\"fancy\"",
						"particles=0", "biomeBlendRadius=2", "entityDistanceScaling=1.0", "inactivityFpsLimit=\"afk\"", "weatherRadius=10",
						"textureFiltering=0", "renderClouds=\"true\"", "prioritizeChunkUpdates=0", "improvedTransparency=false",
						"entityShadows=true", "cutoutLeaves=true")
				.map(pair -> pair.split("=", 2))
				.forEach(pair -> defaults.put("vanilla." + pair[0], pair[1]));
		return Map.of("zeros", zeros, "high", high, "defaults", defaults);
	}
}
