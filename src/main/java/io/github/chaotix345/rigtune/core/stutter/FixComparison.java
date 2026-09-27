package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

// docs/v0.5/SPEC.md 5 (C20, amendment SPEC-25): a stutter fix's before/after comparison, outcomes only. Hitches cluster (a
// teleport gives a dozen in 10 s), so a plain Poisson test would overclaim; this is a quasi-Poisson one:
// - dispersion φ = max(1, pooled variance / pooled mean of the hitches per 60-s bin), over the sides with at least
//   MIN_BINS bins (1 when neither has them), one φ for both sides;
// - effective counts h/φ rounded to the nearest integer, halves up; n = their sum, capped at MAX_N;
// - a one-sided exact binomial test of the after count with p0 = after gameplay / total gameplay.
// LESS: after rate <= 2/3 of before, pLess <= ALPHA and lost ms per minute not higher; MORE: after rate >= 3/2 of before
// and pMore <= ALPHA; SAME ("no clear change") otherwise. Rates are per minute of gameplay, from the raw counts. The exact
// expected values are in docs/v0.5/design/ws-s2.md.
public final class FixComparison {
	public static final double ALPHA = 0.05;
	public static final int MIN_BINS = 3;
	public static final int MAX_N = 100_000;

	public enum Kind {
		LESS, SAME, MORE;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static @Nullable Kind of(@Nullable String id) {
			for (Kind kind : values()) {
				if (kind.id().equals(id)) {
					return kind;
				}
			}
			return null;
		}
	}

	// Both rates always go with the kind (SPEC X3: numbers next to every verdict).
	public record Verdict(Kind kind, double beforePerMinute, double afterPerMinute, double lostBeforePerMinute, double lostAfterPerMinute, double phi,
			double pLess, double pMore) {
	}

	private FixComparison() {
	}

	public static Verdict compare(SessionOutcome before, SessionOutcome after) {
		return compare(before, after, true);
	}

	// guard false: φ = 1 (the plain Poisson test the dispersion guard corrects; for the tests).
	static Verdict compare(SessionOutcome before, SessionOutcome after, boolean guard) {
		double beforeRate = perMinute(before.hitches(), before.gameplaySeconds());
		double afterRate = perMinute(after.hitches(), after.gameplaySeconds());
		double lostBefore = perMinute(before.lostMs(), before.gameplaySeconds());
		double lostAfter = perMinute(after.lostMs(), after.gameplaySeconds());
		double phi = guard ? dispersion(before, after) : 1;
		if (before.gameplaySeconds() <= 0 || after.gameplaySeconds() <= 0) {
			return new Verdict(Kind.SAME, beforeRate, afterRate, lostBefore, lostAfter, phi, 1, 1);
		}
		int e0 = effective(before.hitches(), phi);
		int e1 = effective(after.hitches(), phi);
		long total = (long) e0 + e1;
		if (total > MAX_N) {
			e1 = (int) Math.round((double) e1 * MAX_N / total);
			e0 = MAX_N - e1;
		}
		int n = e0 + e1;
		double gameplay = before.gameplaySeconds() + after.gameplaySeconds();
		double logP = Math.log(after.gameplaySeconds() / gameplay);
		double logQ = Math.log(before.gameplaySeconds() / gameplay);
		double pLess = cdf(e1, n, logP, logQ);
		// P(X >= e1) = P(n - X <= n - e1), n - X ~ Bin(n, 1 - p0): the upper tail without cancellation.
		double pMore = cdf(n - e1, n, logQ, logP);
		Kind kind = Kind.SAME;
		if (afterRate * 3 <= beforeRate * 2 && pLess <= ALPHA && lostAfter <= lostBefore) {
			kind = Kind.LESS;
		} else if (afterRate * 2 >= beforeRate * 3 && pMore <= ALPHA) {
			kind = Kind.MORE;
		}
		return new Verdict(kind, beforeRate, afterRate, lostBefore, lostAfter, phi, pLess, pMore);
	}

	private static double perMinute(double count, double gameplaySeconds) {
		return gameplaySeconds > 0 ? count * 60 / gameplaySeconds : 0;
	}

	public static double dispersion(SessionOutcome before, SessionOutcome after) {
		double m2 = 0;
		int df = 0;
		double sum = 0;
		int bins = 0;
		for (SessionOutcome side : List.of(before, after)) {
			if (side.bins() >= MIN_BINS) {
				m2 += side.m2();
				df += side.bins() - 1;
				sum += side.binMean() * side.bins();
				bins += side.bins();
			}
		}
		if (df == 0 || sum <= 0) {
			return 1;
		}
		return Math.max(1, m2 / df / (sum / bins));
	}

	// One side's own dispersion (variance / mean of its bins), under the same rules.
	static double dispersion(SessionOutcome side) {
		return dispersion(side, SessionOutcome.NONE);
	}

	// h/φ to the nearest integer, halves up.
	static int effective(int hitches, double phi) {
		return (int) Math.floor(hitches / phi + 0.5);
	}

	// P(X <= k) for X ~ Bin(n, p), exact up to floating point: the pmf in log space from pmf(0) = (1-p)^n by the ratio
	// pmf(i+1)/pmf(i) = (n-i)/(i+1) * p/(1-p), summed with log-sum-exp (O(k); n is at most MAX_N here).
	public static double binomialCdf(int k, int n, double p) {
		if (k < 0) {
			return 0;
		}
		if (k >= n || p <= 0) {
			return 1;
		}
		if (p >= 1) {
			return 0;
		}
		return cdf(k, n, Math.log(p), Math.log1p(-p));
	}

	// logP, logQ: log(p) and log(1 - p), each computed directly (no 1 - p rounding).
	private static double cdf(int k, int n, double logP, double logQ) {
		if (k < 0) {
			return 0;
		}
		if (k >= n || logP == Double.NEGATIVE_INFINITY) {
			return 1;
		}
		if (logQ == Double.NEGATIVE_INFINITY) {
			return 0;
		}
		double term = n * logQ;
		double sum = term;
		for (int i = 0; i < k; i++) {
			term += Math.log(n - i) - Math.log(i + 1) + logP - logQ;
			double hi = Math.max(sum, term);
			sum = hi + Math.log1p(Math.exp(Math.min(sum, term) - hi));
		}
		return Math.min(1, Math.exp(sum));
	}
}
