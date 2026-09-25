package io.github.chaotix345.rigtune.v020.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

// Cross-run statistics (docs/research/v0.2/benchmark.md §3, §6): the repeats of one configuration, their coefficient
// of variation, and whether a before/after gain stands out from that noise.
public final class BenchmarkMath {
	public static final double NOISY_CV = 0.05;

	public record Aggregate(double avgFps, double onePercentLowFps, double p99FrameMs, int repeats, @Nullable Double cv) {
	}

	public record Gain(double lowPercent, double avgPercent, boolean significant) {
	}

	private BenchmarkMath() {
	}

	// Sample standard deviation (n - 1) over the mean.
	public static @Nullable Double cv(double... values) {
		int n = values.length;
		if (n < 2) {
			return null;
		}
		double sum = 0;
		for (double v : values) {
			sum += v;
		}
		double mean = sum / n;
		if (mean <= 0) {
			return null;
		}
		double squares = 0;
		for (double v : values) {
			squares += (v - mean) * (v - mean);
		}
		return Math.sqrt(squares / (n - 1)) / mean;
	}

	// Means of the repeats; the CV is of their 1% lows, the headline number.
	public static @Nullable Aggregate aggregate(List<FrameStats> repeats) {
		if (repeats.isEmpty()) {
			return null;
		}
		double avg = 0;
		double p99 = 0;
		double[] lows = new double[repeats.size()];
		for (int i = 0; i < repeats.size(); i++) {
			FrameStats s = repeats.get(i);
			avg += s.avgFps();
			p99 += s.p99FrameMs();
			lows[i] = s.onePercentLowFps();
		}
		double low = 0;
		for (double l : lows) {
			low += l;
		}
		int n = repeats.size();
		return new Aggregate(avg / n, low / n, p99 / n, n, cv(lows));
	}

	public static boolean noisy(@Nullable Double cv) {
		return cv != null && cv > NOISY_CV;
	}

	public static double gainPercent(double before, double after) {
		return before > 0 ? (after - before) / before * 100 : 0;
	}

	// A gain counts only when it is at least twice the larger CV; a side without a CV counts as NOISY_CV.
	public static Gain gain(Aggregate before, Aggregate after) {
		double low = gainPercent(before.onePercentLowFps(), after.onePercentLowFps());
		double avg = gainPercent(before.avgFps(), after.avgFps());
		double floor = 2 * Math.max(cvOrDefault(before.cv()), cvOrDefault(after.cv())) * 100;
		return new Gain(low, avg, Math.abs(low) >= floor - 1e-9);
	}

	private static double cvOrDefault(@Nullable Double cv) {
		return cv == null ? NOISY_CV : cv;
	}

	public static String percent(double value) {
		double rounded = Math.round(value * 10) / 10.0;
		if (rounded == 0) {
			rounded = 0;
		}
		return (rounded >= 0 ? "+" : "") + String.format(Locale.ROOT, "%.1f%%", rounded);
	}
}
