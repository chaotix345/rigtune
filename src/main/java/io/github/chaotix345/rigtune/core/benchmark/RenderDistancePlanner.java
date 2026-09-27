package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.PlannerResult.Measurement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

public final class RenderDistancePlanner {
	private static final int NO_FAIL = Integer.MAX_VALUE;

	private final int minRd;
	private final int maxRd;
	private final int startRd;
	private final double targetFps;
	private final int maxSteps;
	private final List<Measurement> measurements = new ArrayList<>();
	// Distances measured again after an incomplete step (RW-5: once each).
	private final Set<Integer> remeasured = new HashSet<>();

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
		if (measurements.isEmpty()) {
			return OptionalInt.of(startRd);
		}
		OptionalInt search = measurements.size() < maxSteps ? search() : OptionalInt.empty();
		return search.isPresent() ? search : remeasure();
	}

	// docs/v0.5/SPEC.md RW-5: a distance whose terrain hadn't arrived (incomplete) bounds the search like a fail while
	// the lower steps are measured, but it is neither a pass nor a fail.
	private OptionalInt search() {
		int fail = lowestFail();
		int pass = highestPassBelow(fail);
		int bound = Math.min(fail, lowestUnmeasured(pass, fail));
		if (bound == NO_FAIL) {
			if (pass >= maxRd) {
				return OptionalInt.empty();
			}
			// Everything so far passed, so measurements.size() counts the upward steps taken: +2, +4, +8...
			long step = 1L << Math.min(measurements.size(), 30);
			return OptionalInt.of((int) Math.min(maxRd, pass + step));
		}
		if (bound - pass <= 1) {
			return OptionalInt.empty();
		}
		return OptionalInt.of(pass + (bound - pass) / 2);
	}

	// RW-5: once the search is done, the lowest unmeasured distance that could still change the answer is measured again,
	// once, outside maxSteps (the lower steps have loaded most of its terrain by then). The session's deadline decides
	// whether it still fits.
	private OptionalInt remeasure() {
		int fail = lowestFail();
		int rd = lowestUnmeasured(highestPassBelow(fail), fail);
		return rd == NO_FAIL || remeasured.contains(rd) ? OptionalInt.empty() : OptionalInt.of(rd);
	}

	public void record(int rd, FrameStats stats) {
		record(rd, stats, true);
	}

	// complete = false: the step was measured before its terrain arrived, so it can't count as a pass (it would
	// overstate what the machine sustains at this render distance) nor, since RW-5, as a fail (the missing terrain was
	// still being generated: it says nothing about rendering it).
	public void record(int rd, FrameStats stats, boolean complete) {
		Measurement m = new Measurement(rd, stats, complete && stats.onePercentLowFps() >= targetFps, complete);
		for (int i = 0; i < measurements.size(); i++) {
			if (measurements.get(i).rd() == rd) {
				if (!measurements.get(i).complete()) {
					remeasured.add(rd);
				}
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

	// The best 1% low among the complete steps (all steps when none is complete).
	private int bestEffort() {
		List<Measurement> candidates = measurements.stream().filter(Measurement::complete).toList();
		if (candidates.isEmpty()) {
			candidates = measurements;
		}
		Measurement best = candidates.getFirst();
		for (Measurement m : candidates) {
			double low = m.stats().onePercentLowFps();
			double bestLow = best.stats().onePercentLowFps();
			if (low > bestLow || (low == bestLow && m.rd() > best.rd())) {
				best = m;
			}
		}
		return best.rd();
	}

	private String reason(boolean met, int pass, int fail) {
		if (measurements.size() < maxSteps && search().isPresent()) {
			return "In progress";
		}
		int unmeasured = lowestUnmeasured(pass, fail);
		String notMeasured = unmeasured == NO_FAIL ? "" : "; " + unmeasured + " couldn't be measured (its terrain hadn't loaded)";
		if (!met) {
			return (fail == minRd ? "Even the minimum render distance (" + minRd + ") misses the target"
					: "Step limit reached without meeting the target") + notMeasured;
		}
		if (fail == NO_FAIL && pass >= maxRd) {
			return "Target met at the maximum render distance (" + maxRd + ")";
		}
		if (unmeasured != NO_FAIL) {
			return pass + " meets the target" + notMeasured;
		}
		if (fail != NO_FAIL && fail - pass <= 1) {
			return "Converged: " + pass + " meets the target, " + fail + " does not";
		}
		return "Step limit reached";
	}

	// The lowest incomplete distance above `pass` and below `fail` (it could still change the answer), or NO_FAIL.
	private int lowestUnmeasured(int pass, int fail) {
		int out = NO_FAIL;
		for (Measurement m : measurements) {
			if (!m.complete() && m.rd() > pass && m.rd() < fail) {
				out = Math.min(out, m.rd());
			}
		}
		return out;
	}

	// The lowest complete step that missed the target.
	private int lowestFail() {
		int fail = NO_FAIL;
		for (Measurement m : measurements) {
			if (m.complete() && !m.passed()) {
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
