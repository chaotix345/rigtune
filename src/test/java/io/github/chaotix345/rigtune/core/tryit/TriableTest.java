package io.github.chaotix345.rigtune.core.tryit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Difference;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;
import io.github.chaotix345.rigtune.core.tryit.Triable.Refusal;
import io.github.chaotix345.rigtune.core.tryit.TryIt.Kind;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md AC6.1 (unit part), docs/research/v0.5/feature-try-it.md §2.2: what can be tried, in SPEC 6's order.
// Every vanilla key RigTune may change and every setting key the rules name has a row in KEYS below, so a new rule key
// fails keysInTheRulesAreAllDecided until someone decides how it's tried.
class TriableTest {
	private static final String RD = "vanilla.renderDistance";
	private static final String SD = "vanilla.simulationDistance";
	private static final String SODIUM = "sodium.performance.chunk_build_defer_mode";

	// A key's decision: tried NOW or RESTART (refusal null), or refused; the context differences it allows; whether the
	// benchmark world's content may hide its effect.
	private record Expect(@Nullable Kind kind, @Nullable Refusal refusal, Set<Difference> allowed, boolean sceneContent) {
	}

	private static Expect now() {
		return new Expect(Kind.NOW, null, Set.of(), false);
	}

	private static Expect nowContent() {
		return new Expect(Kind.NOW, null, Set.of(), true);
	}

	private static Expect restart() {
		return new Expect(Kind.RESTART, null, Set.of(), false);
	}

	private static Expect refused(Refusal refusal) {
		return new Expect(null, refusal, Set.of(), false);
	}

	private static final Map<String, Expect> KEYS = new LinkedHashMap<>();

	static {
		KEYS.put(RD, new Expect(Kind.NOW, null, EnumSet.of(Difference.RENDER_DISTANCE), false));
		KEYS.put(SD, new Expect(Kind.NOW, null, EnumSet.of(Difference.SIMULATION_DISTANCE), true));
		KEYS.put("vanilla.entityDistanceScaling", nowContent());
		KEYS.put("vanilla.graphicsPreset", refused(Refusal.PRESET));
		KEYS.put("vanilla.maxFps", refused(Refusal.UNMEASURABLE));
		KEYS.put("vanilla.enableVsync", refused(Refusal.UNMEASURABLE));
		KEYS.put("vanilla.inactivityFpsLimit", refused(Refusal.UNMEASURABLE));
		KEYS.put("vanilla.particles", nowContent());
		KEYS.put("vanilla.biomeBlendRadius", now());
		KEYS.put("vanilla.weatherRadius", nowContent());
		KEYS.put("vanilla.textureFiltering", now());
		KEYS.put("vanilla.renderClouds", now());
		KEYS.put("vanilla.prioritizeChunkUpdates", now());
		KEYS.put("vanilla.improvedTransparency", now());
		KEYS.put("vanilla.entityShadows", nowContent());
		KEYS.put("vanilla.cutoutLeaves", now());
		KEYS.put("sodium.performance.use_fog_occlusion", restart());
		KEYS.put("sodium.performance.use_block_face_culling", restart());
		KEYS.put("sodium.performance.use_entity_culling", restart());
		KEYS.put("sodium.performance.animate_only_visible_textures", restart());
		KEYS.put("sodium.performance.chunk_builder_threads", restart());
		KEYS.put(SODIUM, restart());
		KEYS.put("sodium.performance.quad_splitting_mode", restart());
		KEYS.put("dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", restart());
		KEYS.put("dh.client.advanced.graphics.quality.verticalQuality", restart());
		KEYS.put("dh.client.advanced.graphics.quality.horizontalQuality", restart());
		KEYS.put("dh.client.advanced.graphics.quality.maxHorizontalResolution", restart());
		KEYS.put("dh.common.multiThreading.numberOfThreads", restart());
		KEYS.put("dh.client.advanced.debugging.rendererMode", new Expect(Kind.RESTART, null, EnumSet.of(Difference.DISTANT_HORIZONS), false));
		KEYS.put("iris.maxShadowRenderDistance", restart());
		KEYS.put("iris.enableShaders", new Expect(Kind.RESTART, null, EnumSet.of(Difference.SHADERS, Difference.SHADER_PACK), false));
	}

	// Nothing in the way: in a singleplayer world, every config file there.
	private static final Triable.Context OK = new Triable.Context(key -> true, true, false, false, false, scene -> false, false, true, true, true);

	private static Recommendation set(String key) {
		return rec(new Action.SetSetting(key, "1", "2"));
	}

	private static Recommendation rec(Action action) {
		return new Recommendation("r", Category.SETTING, Impact.MEDIUM, "t", "r", action, true);
	}

	private static Triable.Context with(Predicate<String> files, boolean inWorld, boolean remote, boolean pending, boolean busy,
			Predicate<Scene> unavailable, boolean open, boolean journal, boolean benchmarks, boolean store) {
		return new Triable.Context(files, inWorld, remote, pending, busy, unavailable, open, journal, benchmarks, store);
	}

	private static @Nullable Refusal refusal(Recommendation rec, Triable.Context context) {
		return Triable.check(rec, context).refusal();
	}

	@Test
	void everyDecidedKeyIsClassifiedAsTheTableSays() {
		for (Map.Entry<String, Expect> e : KEYS.entrySet()) {
			String key = e.getKey();
			Expect expect = e.getValue();
			Triable.Result result = Triable.check(set(key), OK);
			assertEquals(expect.kind(), result.kind(), key);
			assertEquals(expect.refusal(), result.refusal(), key);
			assertEquals(expect.allowed(), Triable.allowed(key), key);
			assertEquals(expect.sceneContent(), Triable.sceneContent(key), key);
		}
	}

	@Test
	void everyVanillaKeyRigTuneMayChangeIsDecided() {
		assertEquals(new TreeSet<>(SettingKeys.VANILLA_ALLOWED), new TreeSet<>(KEYS.keySet().stream().filter(k -> k.startsWith("vanilla.")).toList()));
	}

	@Test
	void keysInTheRulesAreAllDecided() throws IOException {
		for (String file : List.of("rules/rules-v2.json", "src/main/resources/rigtune/rules-v2.json")) {
			Set<String> keys = new TreeSet<>();
			JsonObject rules = JsonParser.parseString(Files.readString(RepoFiles.resolve(file), StandardCharsets.UTF_8)).getAsJsonObject();
			settingKeys(rules, keys);
			if (rules.get("settingLabels") instanceof JsonObject labels) {
				keys.addAll(labels.keySet());
			}
			assertTrue(keys.size() >= 30, file + ": " + keys);
			for (String key : keys) {
				assertTrue(KEYS.containsKey(key), file + " names " + key + ": decide how it's tried (Triable) and add it to KEYS");
			}
		}
	}

	// Every "key" member at any depth: settings, profile templates, stutter fixes.
	private static void settingKeys(JsonElement element, Set<String> out) {
		if (element instanceof JsonObject o) {
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				if ("key".equals(e.getKey()) && e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
					out.add(e.getValue().getAsString());
				}
				settingKeys(e.getValue(), out);
			}
		} else if (element != null && element.isJsonArray()) {
			element.getAsJsonArray().forEach(child -> settingKeys(child, out));
		}
	}

	@Test
	void modChangesAndAdviceCantBeTried() {
		assertEquals(Refusal.KIND, refusal(rec(new Action.None()), OK));
		assertEquals(Refusal.KIND, refusal(rec(new Action.AddMod("sodium", "AANobbMI", "Sodium")), OK));
		assertEquals(Refusal.KIND, refusal(rec(new Action.UpdateMod("sodium", Path.of("mods", "sodium.jar"),
				new UpdateInfo("sodium", "AANobbMI", "0.9.1", "v1", "0.9.2", new ModFile("https://cdn.modrinth.com/x", "sodium.jar", "0", 1)))), OK));
		assertEquals(Refusal.KIND, refusal(rec(new Action.DisableMod("sodium", Path.of("mods", "sodium.jar"))), OK));
	}

	@Test
	void keysRigTuneCantChangeAreRefused() {
		assertEquals(Refusal.UNSUPPORTED, refusal(set("vanilla.fov"), OK));
		assertEquals(Refusal.UNSUPPORTED, refusal(set("sodium.../x"), OK));
		assertEquals(Refusal.UNSUPPORTED, refusal(set("minecraft.renderDistance"), OK));
		assertEquals(Refusal.UNSUPPORTED, refusal(set("sodium."), OK));
	}

	@Test
	void aConfigKeyWithoutItsFileIsRefused() {
		Triable.Context noDh = with(key -> !key.startsWith("dh."), true, false, false, false, scene -> false, false, true, true, true);
		assertEquals(Refusal.NO_FILE, refusal(set("dh.common.multiThreading.numberOfThreads"), noDh));
		assertEquals(Kind.RESTART, Triable.check(set(SODIUM), noDh).kind());
		assertNull(refusal(set(SODIUM), noDh));
	}

	@Test
	void simulationDistanceOnARemoteServerIsRefusedInTheCurrentScene() {
		Triable.Context remote = with(key -> true, true, true, false, false, scene -> false, false, true, true, true);
		assertEquals(Refusal.REMOTE_SD, refusal(set(SD), remote));
		assertEquals(Refusal.REMOTE_SD, Triable.check(set(SD), remote, Scene.CURRENT).refusal());
		assertNull(refusal(set(RD), remote));
		assertNull(Triable.check(set(SD), OK).refusal(), "singleplayer");
	}

	@Test
	void aRestartTryIsRefusedWhileAnythingIsStaged() {
		Triable.Context pending = with(key -> true, false, false, true, false, scene -> false, false, true, true, true);
		assertEquals(Refusal.PENDING, refusal(set(SODIUM), pending));
		assertNull(refusal(set(RD), pending), "a vanilla change applies now: the staged ones don't take effect before the after run");
	}

	@Test
	void theSharedChecksComeAfterTheKeysOwn() {
		Triable.Context busy = with(key -> true, true, false, false, true, scene -> false, false, true, true, true);
		assertEquals(Refusal.BUSY, refusal(set(RD), busy));
		Triable.Context unavailable = with(key -> true, true, false, false, false, scene -> true, false, true, true, true);
		assertEquals(Refusal.SCENE, refusal(set(RD), unavailable));
		Triable.Context open = with(key -> true, true, false, false, false, scene -> false, true, true, true, true);
		assertEquals(Refusal.OPEN, refusal(set(RD), open));
		Triable.Context journal = with(key -> true, true, false, false, false, scene -> false, false, false, true, true);
		assertEquals(Refusal.HISTORY, refusal(set(RD), journal));
		Triable.Context benchmarks = with(key -> true, true, false, false, false, scene -> false, false, true, false, true);
		assertEquals(Refusal.STORAGE, refusal(set(RD), benchmarks));
		Triable.Context store = with(key -> true, true, false, false, false, scene -> false, false, true, true, false);
		assertEquals(Refusal.STORAGE, refusal(set(RD), store));
	}

	@Test
	void theOrderIsSpec6s() {
		Triable.Context everything = with(key -> false, true, true, true, true, scene -> true, true, false, false, false);
		assertEquals(Refusal.KIND, refusal(rec(new Action.DisableMod("sodium", Path.of("mods", "sodium.jar"))), everything));
		assertEquals(Refusal.UNMEASURABLE, refusal(set("vanilla.maxFps"), everything));
		assertEquals(Refusal.PRESET, refusal(set("vanilla.graphicsPreset"), everything));
		assertEquals(Refusal.NO_FILE, refusal(set(SODIUM), everything));
		assertEquals(Refusal.REMOTE_SD, refusal(set(SD), everything));
		Triable.Context files = with(key -> true, true, true, true, true, scene -> true, true, false, false, false);
		assertEquals(Refusal.PENDING, refusal(set(SODIUM), files));
		assertEquals(Refusal.BUSY, refusal(set(RD), files));
		Triable.Context idle = with(key -> true, true, true, true, false, scene -> true, true, false, false, false);
		assertEquals(Refusal.SCENE, refusal(set(RD), idle));
		Triable.Context here = with(key -> true, true, true, true, false, scene -> false, true, false, false, false);
		assertEquals(Refusal.OPEN, refusal(set(RD), here));
		Triable.Context closed = with(key -> true, true, true, true, false, scene -> false, false, false, false, false);
		assertEquals(Refusal.HISTORY, refusal(set(RD), closed));
	}

	@Test
	void scenes() {
		assertEquals(List.of(Scene.CURRENT, Scene.BENCHMARK_WORLD), Triable.scenes(Kind.NOW));
		assertEquals(List.of(Scene.BENCHMARK_WORLD), Triable.scenes(Kind.RESTART));
		assertEquals(Scene.CURRENT, Triable.defaultScene(Kind.NOW, true));
		assertEquals(Scene.BENCHMARK_WORLD, Triable.defaultScene(Kind.NOW, false));
		assertEquals(Scene.BENCHMARK_WORLD, Triable.defaultScene(Kind.RESTART, true));

		Triable.Context title = with(key -> true, false, false, false, false, scene -> scene == Scene.CURRENT, false, true, true, true);
		assertEquals(Scene.BENCHMARK_WORLD, Triable.check(set(RD), title).scene(), "the title screen: the benchmark world");
		assertNull(Triable.check(set(RD), title).refusal());
		assertEquals(Refusal.SCENE, Triable.check(set(RD), title, Scene.CURRENT).refusal(), "the scene asked for is checked");
		assertEquals(Scene.CURRENT, Triable.check(set(RD), OK).scene(), "in a world: here");
		assertEquals(Scene.BENCHMARK_WORLD, Triable.check(set(RD), OK, Scene.BENCHMARK_WORLD).scene());
		assertEquals(Scene.BENCHMARK_WORLD, Triable.check(set(SODIUM), OK, Scene.CURRENT).scene(), "a restart try is always in the benchmark world");
		Triable.Context worldOnly = with(key -> true, true, false, false, false, scene -> scene == Scene.BENCHMARK_WORLD, false, true, true, true);
		assertEquals(Refusal.SCENE, refusal(set(SODIUM), worldOnly), "in a world, the benchmark world can't open");
		assertNull(Triable.check(set("vanilla.maxFps"), OK).scene(), "a refused key has no scene");
	}

	@Test
	void exactlyOneTickedItem() {
		assertEquals(Refusal.ONE, Triable.selection(List.of(), OK).refusal());
		assertEquals(Refusal.ONE, Triable.selection(List.of(set(RD), set(SD)), OK).refusal());
		assertEquals(Kind.NOW, Triable.selection(List.of(set(RD)), OK).kind());
		assertEquals(Refusal.UNMEASURABLE, Triable.selection(List.of(set("vanilla.maxFps")), OK).refusal());
	}

	@Test
	void theLiftedKeysAreTheOnesEveryRunUncaps() {
		assertEquals(Set.of("vanilla.maxFps", "vanilla.enableVsync", "vanilla.inactivityFpsLimit"), Triable.LIFTED);
	}
}
