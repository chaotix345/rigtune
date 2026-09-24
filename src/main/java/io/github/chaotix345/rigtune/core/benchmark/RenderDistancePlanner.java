package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.PlannerResult.Measurement;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public final class RenderDistancePlanner {
	private static final int NO_FAIL = Integer.MAX_VALUE;

	private final int minRd;
	private final int maxRd;
	private final int startRd;
	private final double targetFps;
	private final int maxSteps;
	private final List<Measurement> measurements = new ArrayList<>();

	public RenderDistancePlanner(int minRd, int maxRd, int startRd, double targetFps, int maxSteps) {
		if (minRd > maxRd) {
			throw new IllegalArgumentException("minRd " + minRd + " > maxRd " + maxRd);
		}
		if (maxSteps < 1) {
			throw new IllegalArgumentException("maxSteps must be at least 1");
		}
		this.minRd = minRd;
		this.maxRd = maxRd;
		this.startRd = Math.clamp(startRd, minRd, maxRd);
		this.targetFps = targetFps;
		this.maxSteps = maxSteps;
	}

	public OptionalInt next() {
		if (measurements.size() >= maxSteps) {
			return OptionalInt.empty();
		}
		if (measurements.isEmpty()) {
			return OptionalInt.of(startRd);
		}
		int fail = lowestFail();
		int pass = highestPassBelow(fail);
		if (fail == NO_FAIL) {
			if (pass >= maxRd) {
				return OptionalInt.empty();
			}
			// Everything so far passed, so measurements.size() counts the upward steps taken: +2, +4, +8...
			long step = 1L << Math.min(measurements.size(), 30);
			return OptionalInt.of((int) Math.min(maxRd, pass + step));
		}
		if (fail - pass <= 1) {
			return OptionalInt.empty();
		}
		return OptionalInt.of(pass + (fail - pass) / 2);
	}

	public void record(int rd, FrameStats stats) {
		Measurement m = new Measurement(rd, stats, stats.onePercentLowFps() >= targetFps);
		for (int i = 0; i < measurements.size(); i++) {
			if (measurements.get(i).rd() == rd) {
				measurements.set(i, m);
				return;
			}
		}
		measurements.add(m);
	}

	public boolean done() {
		return next().isEmpty();
	}

	public PlannerResult result() {
		if (measurements.isEmpty()) {
			return new PlannerResult(minRd, false, minRd, measurements, "No measurements yet");
		}
		int fail = lowestFail();
		int pass = highestPassBelow(fail);
		boolean met = pass >= minRd;
		return new PlannerResult(met ? pass : minRd, met, bestEffort(), measurements, reason(met, pass, fail));
	}

	private int bestEffort() {
		Measurement best = measurements.getFirst();
		for (Measurement m : measurements) {
			double low = m.stats().onePercentLowFps();
			double bestLow = best.stats().onePercentLowFps();
			if (low > bestLow || (low == bestLow && m.rd() > best.rd())) {
				best = m;
			}
		}
		return best.rd();
	}

	private String reason(boolean met, int pass, int fail) {
		if (!done()) {
			return "In progress";
		}
		if (!met) {
			return fail == minRd
					? "Even the minimum render distance (" + minRd + ") misses the target"
					: "Step limit reached without meeting the target";
		}
		if (fail == NO_FAIL && pass >= maxRd) {
			return "Target met at the maximum render distance (" + maxRd + ")";
		}
		if (fail != NO_FAIL && fail - pass <= 1) {
			return "Converged: " + pass + " meets the target, " + fail + " does not";
		}
		return "Step limit reached";
	}

	private int lowestFail() {
		int fail = NO_FAIL;
		for (Measurement m : measurements) {
			if (!m.passed()) {
				fail = Math.min(fail, m.rd());
			}
		}
		return fail;
	}

	// Passes above the lowest fail are treated as noise so the answer stays conservative.
	private int highestPassBelow(int fail) {
		int pass = minRd - 1;
		for (Measurement m : measurements) {
			if (m.passed() && m.rd() < fail) {
				pass = Math.max(pass, m.rd());
			}
		}
		return pass;
	}
}
