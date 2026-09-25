package io.github.chaotix345.rigtune.core.benchmark;

import java.util.List;

// Every duration of a benchmark run (docs/v0.2/SPEC.md item 6 and its plan-review amendment M16). The full protocol
// measures render distance candidates and the repeats; the quick one measures simulation distance candidates and the
// cost reports. No step starts unless it can finish before the deadline.
public record Timing(int maxRdSteps, double sweepSeconds, double settleSeconds, double settleTimeoutSeconds,
		double warmupSeconds, double quickSeconds, double quickSettleTimeoutSeconds, int repeats, double deadlineSeconds) {
	public static final float DOWN_PITCH = 25f;
	public static final Timing DEFAULT = new Timing(6, 8.0, 2.0, 20.0, 1.5, 6.0, 2.0, 2, 300.0);

	public Protocol full() {
		return new Protocol(settleSeconds, settleTimeoutSeconds, warmupSeconds,
				List.of(new Protocol.Sweep(sweepSeconds, 0f), new Protocol.Sweep(sweepSeconds, DOWN_PITCH)));
	}

	public Protocol quick() {
		return new Protocol(0, quickSettleTimeoutSeconds, warmupSeconds, List.of(new Protocol.Sweep(quickSeconds, 0f)));
	}
}
