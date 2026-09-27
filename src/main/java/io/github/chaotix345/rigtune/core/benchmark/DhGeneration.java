package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md RW-6 (detect, name and exclude; no pause): whether Distant Horizons generated terrain during a
// benchmark's recorded sweeps, from the benchmark capture's "DH world generation" CPU (WS-S's sampler bucket: the
// core-equivalents its threads used, averaged over the sweeps). The run's Context.dhGenerating; such a run is named on the
// result screen and left out of the trend.
public final class DhGeneration {
	// UNVERIFIED starting value (AC2B.5's real run on the dev PC checks it): half a core of world generation, sustained over
	// the sweeps. The real first run that raised RW-6 had 8 world-generation threads busy on a 16-core CPU.
	public static final double MIN_CORES = 0.5;

	private DhGeneration() {
	}

	// Null (the field left out) without Distant Horizons, or when nothing was sampled; false: below the threshold, which
	// includes no "DH-World Gen" thread seen at all (the prefix is UNVERIFIED outside DH 3.3.2).
	public static @Nullable Boolean generating(@Nullable Double worldGenCores, boolean dhLoaded) {
		if (!dhLoaded || worldGenCores == null) {
			return null;
		}
		return worldGenCores >= MIN_CORES;
	}
}
