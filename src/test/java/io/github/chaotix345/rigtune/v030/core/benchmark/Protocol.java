package io.github.chaotix345.rigtune.v030.core.benchmark;

import java.util.List;

// How one configuration is measured: wait for the chunk sections to compile (at least settleMinSeconds, at most
// settleTimeoutSeconds), sweep the camera for warmupSeconds without recording, then record the sweeps in order.
public record Protocol(double settleMinSeconds, double settleTimeoutSeconds, double warmupSeconds, List<Sweep> sweeps) {
	public record Sweep(double seconds, float pitch) {
	}

	public Protocol {
		sweeps = List.copyOf(sweeps);
	}

	public double measuredSeconds() {
		return sweeps.stream().mapToDouble(Sweep::seconds).sum();
	}

	public double worstCaseSeconds() {
		return Math.max(settleMinSeconds, settleTimeoutSeconds) + warmupSeconds + measuredSeconds();
	}
}
