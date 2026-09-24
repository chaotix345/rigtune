package io.github.chaotix345.rigtune.core.benchmark;

import java.util.List;

public record PlannerResult(int bestRd, boolean targetMet, int bestEffortRd, List<Measurement> measurements, String reason) {
	public record Measurement(int rd, FrameStats stats, boolean passed) {
	}

	public PlannerResult {
		measurements = List.copyOf(measurements);
	}

	public int suggestedRd() {
		return targetMet ? bestRd : bestEffortRd;
	}
}
