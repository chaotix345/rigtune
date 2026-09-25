package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.List;

// What a benchmark session found. chosen: the tuned knobs (TUNE) or the measured ones (MEASURE); DH and shaders in it
// are always the original values. result: the repeats of chosen, or its best other measurement when no repeat ran.
// A cost is the quick-protocol baseline of chosen against the same with the feature off.
public record SessionResult(BenchmarkRequest.Mode mode, Knobs original, Knobs chosen, double targetFps,
		PlannerResult renderDistance, List<Measured> measurements, BenchmarkMath.@Nullable Aggregate result,
		@Nullable Cost dhCost, @Nullable Cost shaderCost, boolean deadlineHit) {
	public record Measured(Step step, FrameStats stats) {
	}

	public record Cost(double baselineLow, double baselineAvg, double offLow, double offAvg) {
		public double lowGainPercent() {
			return BenchmarkMath.gainPercent(baselineLow, offLow);
		}

		public double avgGainPercent() {
			return BenchmarkMath.gainPercent(baselineAvg, offAvg);
		}
	}

	public SessionResult {
		measurements = List.copyOf(measurements);
	}

	public boolean targetMet() {
		if (mode == BenchmarkRequest.Mode.TUNE) {
			return renderDistance.targetMet();
		}
		return result != null && result.onePercentLowFps() >= targetFps;
	}
}
