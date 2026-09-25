package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

// One run in config/rigtune/benchmarks.json (docs/research/v0.2/benchmark.md §7 plus the RigTune version, mode and
// scene). mode and scene are the BenchmarkRequest enum names. phase: "before"/"after" for a Measure pair (pairId
// links them), otherwise "single". knobs: the value the run chose (or measured) and the original, with the stats
// measured at the chosen value. result: the headline numbers (the repeats of the chosen settings). costs: the
// quick-protocol baseline against the same settings with Distant Horizons rendering or shaders off.
public record BenchmarkRecord(String id, String createdAt, String rigtuneVersion, String mcVersion, String mode, String scene,
		String phase, @Nullable String pairId, int targetFps, boolean targetMet, Map<String, KnobResult> knobs,
		@Nullable Result result, Map<String, Cost> costs, @Nullable World world, boolean deadlineHit) {
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

	// Gson leaves absent maps null.
	public BenchmarkRecord {
		knobs = knobs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(knobs));
		costs = costs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(costs));
	}
}
