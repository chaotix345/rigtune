package io.github.chaotix345.rigtune.core.benchmark;

import java.util.Arrays;

public record FrameStats(int frames, double avgFps, double onePercentLowFps, double p99FrameMs, double maxFrameMs) {
	private static final double NANOS_PER_MS = 1_000_000.0;

	public static FrameStats of(long[] frameTimesNanos) {
		int n = frameTimesNanos.length;
		if (n == 0) {
			return new FrameStats(0, 0, 0, 0, 0);
		}
		long[] sorted = frameTimesNanos.clone();
		Arrays.sort(sorted);

		long total = 0;
		for (long t : sorted) {
			total += t;
		}
		double avgFps = total > 0 ? n / (total / 1e9) : 0;

		int slowest = Math.max(1, n / 100);
		long slowTotal = 0;
		for (int i = n - slowest; i < n; i++) {
			slowTotal += sorted[i];
		}
		double slowMeanMs = slowTotal / NANOS_PER_MS / slowest;
		double onePercentLow = slowMeanMs > 0 ? 1000.0 / slowMeanMs : 0;

		int p99Index = (int) Math.max(0, (99L * n + 99) / 100 - 1);
		return new FrameStats(n, avgFps, onePercentLow, sorted[p99Index] / NANOS_PER_MS, sorted[n - 1] / NANOS_PER_MS);
	}
}
