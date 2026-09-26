package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// One run in config/rigtune/benchmarks.json (docs/research/v0.2/benchmark.md §7 plus the RigTune version, mode and
// scene). mode and scene are the BenchmarkRequest enum names. phase: "before"/"after" for a Measure pair (pairId
// links them), otherwise "single". knobs: the value the run chose (or measured) and the original, with the stats
// measured at the chosen value. result: the headline numbers (the repeats of the chosen settings). costs: the
// quick-protocol baseline against the same settings with Distant Horizons rendering or shaders off. notMeasured: why a
// cost report that applied has no numbers ("deadline" or "failed: <message>"), by the same keys as costs. context: what
// else shaped the numbers (0.3.0 on; absent from older runs, and dropped if 0.2.x rewrites the file, which ignores it).
public record BenchmarkRecord(String id, String createdAt, String rigtuneVersion, String mcVersion, String mode, String scene,
		String phase, @Nullable String pairId, int targetFps, boolean targetMet, Map<String, KnobResult> knobs,
		@Nullable Result result, Map<String, Cost> costs, Map<String, String> notMeasured, @Nullable World world, boolean deadlineHit,
		@Nullable Context context) {
	public static final String BEFORE = "before";
	public static final String AFTER = "after";
	public static final String SINGLE = "single";
	public static final String RENDER_DISTANCE = "renderDistance";
	public static final String SIMULATION_DISTANCE = "simulationDistance";
	public static final String DISTANT_HORIZONS = "distantHorizons";
	public static final String SHADERS = "shaders";

	public record KnobResult(int value, int original, @Nullable Double avgFps, @Nullable Double onePercentLowFps,
			@Nullable Double p99FrameMs) {
	}

	public record Result(double avgFps, double onePercentLowFps, double p99FrameMs, int repeats, @Nullable Double cv) {
	}

	public record Cost(double baselineAvgFps, double baselineOnePercentLowFps, double offAvgFps, double offOnePercentLowFps) {
		public double lowGainPercent() {
			return BenchmarkMath.gainPercent(baselineOnePercentLowFps, offOnePercentLowFps);
		}

		public double avgGainPercent() {
			return BenchmarkMath.gainPercent(baselineAvgFps, offAvgFps);
		}
	}

	public record World(String levelId, long seed) {
	}

	// docs/v0.3/SPEC.md 8: whether Distant Horizons rendered and a shader pack was in use at the start, the pack's file
	// name, the framebuffer size, fullscreen, and the benchmark protocol version. Runs are only comparable when these
	// match; mods, drivers and other settings are left out on purpose (their effect is what a comparison looks for).
	// 0.4.0 on (docs/v0.4/SPEC.md 7, optional): modSetHash, SHA-256 over the sorted (mod id, version) pairs of the loaded
	// mods; journalCursor, the id of the newest history.json entry at the time of the run. Null in older runs, and dropped
	// if 0.3.x rewrites the file.
	public record Context(boolean dhRendering, boolean shaders, @Nullable String shaderPack, int width, int height, boolean fullscreen,
			int protocol, @Nullable String modSetHash, @Nullable String journalCursor) {
		public static final int PROTOCOL = 1;

		public Context(boolean dhRendering, boolean shaders, @Nullable String shaderPack, int width, int height, boolean fullscreen,
				int protocol) {
			this(dhRendering, shaders, shaderPack, width, height, fullscreen, protocol, null, null);
		}

		public Context withModSet(@Nullable String newModSetHash, @Nullable String newJournalCursor) {
			return new Context(dhRendering, shaders, shaderPack, width, height, fullscreen, protocol, newModSetHash, newJournalCursor);
		}
	}

	public BenchmarkRecord(String id, String createdAt, String rigtuneVersion, String mcVersion, String mode, String scene,
			String phase, @Nullable String pairId, int targetFps, boolean targetMet, Map<String, KnobResult> knobs,
			@Nullable Result result, Map<String, Cost> costs, Map<String, String> notMeasured, @Nullable World world, boolean deadlineHit) {
		this(id, createdAt, rigtuneVersion, mcVersion, mode, scene, phase, pairId, targetFps, targetMet, knobs, result, costs, notMeasured, world,
				deadlineHit, null);
	}

	// Gson leaves absent maps null.
	public BenchmarkRecord {
		knobs = knobs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(knobs));
		costs = costs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(costs));
		notMeasured = notMeasured == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(notMeasured));
	}
}
