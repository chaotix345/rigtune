package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Difference;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.tryit.TryIt.Kind;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

// What can be tried (docs/v0.5/SPEC.md 6, docs/research/v0.5/feature-try-it.md §2.2), checked in SPEC 6's order: only a
// setting; not one every benchmark run lifts (the frame-rate cap, VSync, the inactivity limit) nor the graphics preset
// (it rewrites many options); other vanilla keys RigTune may change apply at once (NOW); Sodium, Distant Horizons and
// Iris keys with their config file are staged for the next restart (RESTART); simulation distance isn't tried on a
// remote server (the server decides it); a restart try waits until nothing else is staged (it would take effect at the
// same restart). Then the shared busy check (C8, the client's Busy), the benchmark's own check for the scene, another
// open try, and whether History, benchmarks.json and tryit.json can take what the try writes. Mod-file changes are never
// tried, whatever the launcher policy (§2.2.1). Pure: the client passes what it knows in a Context.
public final class Triable {
	// Every benchmark run lifts these (uncapped, VSync off, no inactivity limit), so before and after measure the same.
	public static final Set<String> LIFTED = Set.of("vanilla.maxFps", "vanilla.enableVsync", "vanilla.inactivityFpsLimit");
	public static final String PRESET = "vanilla.graphicsPreset";
	public static final String SIMULATION_DISTANCE = "vanilla.simulationDistance";

	// The context differences a key's own change makes, so they don't withhold the verdict (TryItVerdict). DH saves
	// renderingEnabled as rendererMode (DhCompat), and the context's dhRendering reads it.
	private static final Map<String, Set<Difference>> ALLOWED = Map.of(
			"vanilla.renderDistance", EnumSet.of(Difference.RENDER_DISTANCE),
			SIMULATION_DISTANCE, EnumSet.of(Difference.SIMULATION_DISTANCE),
			"iris.enableShaders", EnumSet.of(Difference.SHADERS, Difference.SHADER_PACK),
			"dh.client.advanced.debugging.rendererMode", EnumSet.of(Difference.DISTANT_HORIZONS));

	// Keys whose effect the benchmark world may hide: it has no mobs, clear weather and no random ticks.
	private static final Set<String> SCENE_CONTENT = Set.of("vanilla.particles", "vanilla.entityDistanceScaling", "vanilla.entityShadows",
			"vanilla.weatherRadius", SIMULATION_DISTANCE);

	// In check order. ONE: not exactly one item ticked (Preview). BUSY and SCENE: the client shows the shared busy check's
	// and the benchmark's own text.
	public enum Refusal {
		ONE, KIND, UNMEASURABLE, PRESET, UNSUPPORTED, NO_FILE, REMOTE_SD, PENDING, BUSY, SCENE, OPEN, HISTORY, STORAGE
	}

	// hasConfigFile: a Sodium/DH/Iris key's config file is known (ConfigTargets.forKey). inWorld: a world is open.
	// remoteServer: connected to a server other than this game's own. pending: changes are staged for the next restart.
	// busy: the shared busy check (Busy.refusal) refused. sceneUnavailable: the benchmark can't start in that scene now.
	// tryOpen: tryit.json has an open try. journalWritable: history.json can take the entry (and Revert's undo).
	// benchmarksReadable: benchmarks.json can take the runs. storeWritable: tryit.json can be written.
	public record Context(Predicate<String> hasConfigFile, boolean inWorld, boolean remoteServer, boolean pending, boolean busy,
			Predicate<Scene> sceneUnavailable, boolean tryOpen, boolean journalWritable, boolean benchmarksReadable, boolean storeWritable) {
	}

	// kind: how the key is tried (null when the key itself is refused). scene: where it would be measured (null then too).
	public record Result(@Nullable Kind kind, @Nullable Scene scene, @Nullable Refusal refusal) {
		public boolean ok() {
			return refusal == null;
		}
	}

	private Triable() {
	}

	// Preview's button: exactly one ticked item, which can be tried.
	public static Result selection(List<Recommendation> selected, Context context) {
		return selected.size() != 1 ? new Result(null, null, Refusal.ONE) : check(selected.getFirst(), context);
	}

	public static Result check(Recommendation rec, Context context) {
		return check(rec, context, null);
	}

	// scene: the one asked for (TryItScreen's choice), or null for the default. A restart try is always measured in the
	// benchmark world.
	public static Result check(Recommendation rec, Context context, @Nullable Scene scene) {
		if (!(rec.action() instanceof Action.SetSetting set)) {
			return new Result(null, null, Refusal.KIND);
		}
		String key = set.key();
		Result own = classify(key, context.hasConfigFile());
		if (own.refusal() != null) {
			return own;
		}
		Kind kind = own.kind();
		Scene where = kind == Kind.RESTART || scene == null ? defaultScene(kind, context.inWorld()) : scene;
		Refusal refusal = null;
		if (SIMULATION_DISTANCE.equals(key) && where == Scene.CURRENT && context.remoteServer()) {
			refusal = Refusal.REMOTE_SD;
		} else if (kind == Kind.RESTART && context.pending()) {
			refusal = Refusal.PENDING;
		} else if (context.busy()) {
			refusal = Refusal.BUSY;
		} else if (context.sceneUnavailable().test(where)) {
			refusal = Refusal.SCENE;
		} else if (context.tryOpen()) {
			refusal = Refusal.OPEN;
		} else if (!context.journalWritable()) {
			refusal = Refusal.HISTORY;
		} else if (!context.benchmarksReadable() || !context.storeWritable()) {
			refusal = Refusal.STORAGE;
		}
		return new Result(kind, where, refusal);
	}

	// The key alone: its kind, or why it can't be tried whatever the game's state.
	private static Result classify(String key, Predicate<String> hasConfigFile) {
		if (LIFTED.contains(key)) {
			return new Result(null, null, Refusal.UNMEASURABLE);
		}
		if (PRESET.equals(key)) {
			return new Result(null, null, Refusal.PRESET);
		}
		if (!SettingKeys.changeable(key)) {
			return new Result(null, null, Refusal.UNSUPPORTED);
		}
		if (key.startsWith(SettingKeys.VANILLA_PREFIX)) {
			return new Result(Kind.NOW, null, null);
		}
		return hasConfigFile.test(key) ? new Result(Kind.RESTART, null, null) : new Result(null, null, Refusal.NO_FILE);
	}

	// NOW: here or in the benchmark world; RESTART: the benchmark world only (the current scene can't be matched across a
	// restart: another spot, time, weather or server).
	public static List<Scene> scenes(Kind kind) {
		return kind == Kind.NOW ? List.of(Scene.CURRENT, Scene.BENCHMARK_WORLD) : List.of(Scene.BENCHMARK_WORLD);
	}

	public static Scene defaultScene(Kind kind, boolean inWorld) {
		return kind == Kind.NOW && inWorld ? Scene.CURRENT : Scene.BENCHMARK_WORLD;
	}

	public static Set<Difference> allowed(String key) {
		return ALLOWED.getOrDefault(key, Set.of());
	}

	public static boolean sceneContent(String key) {
		return SCENE_CONTENT.contains(key);
	}
}
