package io.github.chaotix345.rigtune.core.model;

import org.jspecify.annotations.Nullable;

// The latest benchmark, as shown in the share report. renderDistance is the suggested (or measured) render distance.
// v0.4 (docs/v0.4/SPEC.md 7, optional): conditions, the run's context in English ("RD 12 · SD 8 · 2560×1440 · shaders
// off"); rerun, what no longer matches it ("Needs a rerun (changed since: resolution)"), null when nothing changed.
public record BenchmarkSummary(String at, String mode, String scene, int targetFps, int renderDistance, double avgFps,
		double onePercentLowFps, boolean targetMet, @Nullable String conditions, @Nullable String rerun) {
	public BenchmarkSummary(String at, String mode, String scene, int targetFps, int renderDistance, double avgFps, double onePercentLowFps,
			boolean targetMet) {
		this(at, mode, scene, targetFps, renderDistance, avgFps, onePercentLowFps, targetMet, null, null);
	}

	public BenchmarkSummary withContext(@Nullable String newConditions, @Nullable String newRerun) {
		return new BenchmarkSummary(at, mode, scene, targetFps, renderDistance, avgFps, onePercentLowFps, targetMet, newConditions, newRerun);
	}
}
