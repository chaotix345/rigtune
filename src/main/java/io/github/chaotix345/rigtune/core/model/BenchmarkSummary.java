package io.github.chaotix345.rigtune.core.model;

// The latest benchmark, as shown in the share report. renderDistance is the suggested (or measured) render distance.
public record BenchmarkSummary(String at, String mode, String scene, int targetFps, int renderDistance, double avgFps,
		double onePercentLowFps, boolean targetMet) {
}
