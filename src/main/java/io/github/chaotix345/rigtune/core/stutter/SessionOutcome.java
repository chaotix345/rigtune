package io.github.chaotix345.rigtune.core.stutter;

import java.util.ArrayList;
import java.util.List;

// docs/v0.5/SPEC.md 5 (C20): what a stutter fix's before/after comparison takes from play sessions, outcomes only (never
// cause shares, never the 1 % low): gameplay, hitches, the spikes' lost time, and the hitches per 60 s of wall time from
// the capture's start (by each hitch's start; a partial last bin counts) as their count, mean and sample variance, for
// FixComparison's dispersion guard. sessions: how many sessions it sums (the after side accumulates with plus()). Values
// read from stutter-fixes.json can be anything, so a negative or non-finite one reads as 0.
public record SessionOutcome(int sessions, double gameplaySeconds, int hitches, double lostMs, int bins, double binMean, double binVariance) {
	public static final long BIN_NANOS = 60 * StutterAnalyzer.SECOND;
	// review-12 R12STUTTER-1: a session capture's first minutes (its world join's chunk streaming, or the reload of an
	// immediate fix's restart) never count, on either side; the monitor marks the frame ring there (FrameRing.markGameplayAt).
	public static final long SETTLE_NANOS = 180 * StutterAnalyzer.SECOND;
	public static final SessionOutcome NONE = new SessionOutcome(0, 0, 0, 0, 0, 0, 0);

	public SessionOutcome {
		sessions = Math.max(0, sessions);
		gameplaySeconds = sane(gameplaySeconds);
		hitches = Math.max(0, hitches);
		lostMs = sane(lostMs);
		bins = Math.max(0, bins);
		binMean = sane(binMean);
		binVariance = bins > 1 ? sane(binVariance) : 0;
	}

	private static double sane(double value) {
		return Double.isFinite(value) && value > 0 ? value : 0;
	}

	// One session: its gameplay and wall-clock length, the spikes' lost time, and each hitch's start in nanoseconds since
	// the capture began.
	public static SessionOutcome of(double gameplaySeconds, double sessionSeconds, double lostMs, long[] hitchStarts) {
		int bins = Math.max(1, (int) Math.ceil(sane(sessionSeconds) / 60));
		int[] counts = new int[bins];
		for (long start : hitchStarts) {
			counts[(int) Math.clamp(start / BIN_NANOS, 0, bins - 1)]++;
		}
		double mean = (double) hitchStarts.length / bins;
		double m2 = 0;
		for (int count : counts) {
			m2 += (count - mean) * (count - mean);
		}
		return new SessionOutcome(1, gameplaySeconds, hitchStarts.length, lostMs, bins, mean, bins > 1 ? m2 / (bins - 1) : 0);
	}

	// A finished analysis of a capture that started at startNanos. A spike right after a settings change (the settingsChanged
	// tag, RW-11) is the change's, not play's: it is left out of the hitches and the lost time, on both sides alike. The
	// spikes, the gameplay and the bins all come from the analysis' compared span (StutterAnalyzer.Compared: after the
	// settle span, where the rings still know every spike); a hand-built result without one counts the whole capture.
	public static SessionOutcome of(StutterAnalyzer.Result result, long startNanos) {
		StutterAnalyzer.Compared covered = result.compared();
		long from = covered == null ? startNanos : covered.fromNanos();
		List<SpikeDetector.Spike> spikes = new ArrayList<>();
		long lostNanos = 0;
		for (Attributor.Attribution a : result.attributions()) {
			if (!a.tags().contains(Attributor.SETTINGS_CHANGED) && (covered == null || a.spike().end() > from)) {
				spikes.add(a.spike());
				lostNanos += Math.max(0, a.spike().lost());
			}
		}
		List<SpikeDetector.Hitch> hitches = SpikeDetector.hitches(spikes);
		long[] starts = new long[hitches.size()];
		for (int i = 0; i < starts.length; i++) {
			starts[i] = hitches.get(i).start() - from;
		}
		StutterReport report = result.report();
		return covered == null ? of(report.gameplaySeconds(), report.sessionSeconds(), lostNanos / 1e6, starts)
				: of(covered.gameplaySeconds(), covered.seconds(), lostNanos / 1e6, starts);
	}

	// Both sides' sessions together; the bins' statistics pooled as if all their bins were one list (Chan et al.).
	public SessionOutcome plus(SessionOutcome other) {
		int n = bins + other.bins;
		double mean = bins == 0 ? other.binMean : binMean;
		double variance = bins == 0 ? other.binVariance : binVariance;
		if (bins > 0 && other.bins > 0) {
			double delta = other.binMean - binMean;
			mean = binMean + delta * other.bins / n;
			variance = (m2() + other.m2() + delta * delta * bins * other.bins / n) / (n - 1);
		}
		return new SessionOutcome(sessions + other.sessions, gameplaySeconds + other.gameplaySeconds, hitches + other.hitches, lostMs + other.lostMs, n,
				mean, variance);
	}

	// The bins' sum of squared deviations from their mean.
	double m2() {
		return bins > 1 ? binVariance * (bins - 1) : 0;
	}
}
