package io.github.chaotix345.rigtune.core.benchmark;

import java.util.List;

public record PlannerResult(int bestRd, boolean targetMet, int bestEffortRd, List<Measurement> measurements, String reason) {
	// complete: the step's terrain had arrived (SettleCheck.Result.complete()); an incomplete step never passes.
	public record Measurement(int rd, FrameStats stats, boolean passed, boolean complete) {
		public Measurement(int rd, FrameStats stats, boolean passed) {
			this(rd, stats, passed, true);
		}
	}

	public PlannerResult {
		measurements = List.copyOf(measurements);
	}

	public int suggestedRd() {
		return targetMet ? bestRd : bestEffortRd;
	}
}
