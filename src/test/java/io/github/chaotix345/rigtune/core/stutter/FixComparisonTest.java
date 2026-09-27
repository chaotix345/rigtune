package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// AC5.7 (unit): the exact binomial CDF, the dispersion guard, the rounding rule and the verdicts. The expected values come
// from an exact reference (fractions, no floating point; docs/v0.5/design/ws-s2.md "The comparison, exactly").
class FixComparisonTest {
	private static void close(double expected, double actual) {
		assertEquals(expected, actual, Math.abs(expected) * 1e-9 + 1e-15);
	}

	// A side from its hitch count per 60-s bin.
	static SessionOutcome side(int hitches, double gameplay, double lostMs, int... bins) {
		int sum = 0;
		for (int b : bins) {
			sum += b;
		}
		assertEquals(hitches, sum);
		double mean = bins.length == 0 ? 0 : (double) sum / bins.length;
		double m2 = 0;
		for (int b : bins) {
			m2 += (b - mean) * (b - mean);
		}
		return new SessionOutcome(1, gameplay, hitches, lostMs, bins.length, mean, bins.length > 1 ? m2 / (bins.length - 1) : 0);
	}

	@Test
	void theBinomialCdfMatchesExactValues() {
		close(0.0317840576171875, FixComparison.binomialCdf(5, 19, 0.5));
		close(0.04936857335269451, FixComparison.binomialCdf(10, 30, 0.5));
		close(0.16225028884317033, FixComparison.binomialCdf(1, 5, 383.0 / 735));
		close(0.00390625, FixComparison.binomialCdf(0, 8, 0.5));
		close(1.5161507257275237e-07, FixComparison.binomialCdf(2, 18, 352.0 / 492));
		close(0.0008652680424881588, FixComparison.binomialCdf(450, 1000, 0.5));
		close(0.8572137667933241, FixComparison.binomialCdf(3, 2000, 0.001));
		close(0.9546114398644328, FixComparison.binomialCdf(60, 200, 0.25));
	}

	@Test
	void theCdfsEdges() {
		assertEquals(0, FixComparison.binomialCdf(-1, 10, 0.5));
		assertEquals(1, FixComparison.binomialCdf(10, 10, 0.5));
		assertEquals(1, FixComparison.binomialCdf(0, 0, 0.5));
		assertEquals(1, FixComparison.binomialCdf(0, 10, 0));
		assertEquals(0, FixComparison.binomialCdf(9, 10, 1));
		// Large n stays finite and in range (log space).
		double p = FixComparison.binomialCdf(50_000, 100_000, 0.5);
		assertEquals(0.5, p, 0.01);
	}

	@Test
	void effectiveCountsRoundHalvesUp() {
		assertEquals(3, FixComparison.effective(5, 2.0));
		assertEquals(2, FixComparison.effective(3, 2.0));
		assertEquals(1, FixComparison.effective(1, 2.0));
		assertEquals(0, FixComparison.effective(1, 2.5));
		assertEquals(7, FixComparison.effective(7, 1.0));
		assertEquals(0, FixComparison.effective(0, 1.0));
	}

	// Two still controls: a naive ratio would call it a 77 % improvement.
	@Test
	void aVersusA3IsNoClearChange() {
		FixComparison.Verdict v = FixComparison.compare(side(4, 352, 235, 1, 1, 0, 1, 0, 1), side(1, 383, 24, 1, 0, 0, 0, 0, 0, 0));
		assertEquals(FixComparison.Kind.SAME, v.kind());
		close(1, v.phi());
		close(0.16225028884317033, v.pLess());
		close(0.9748071893812105, v.pMore());
		close(4 * 60.0 / 352, v.beforePerMinute());
		close(60.0 / 383, v.afterPerMinute());
	}

	// With A's real hitch times (three right after the start) the guard widens it further; still no clear change.
	@Test
	void aWithItsRealHitchTimesVersusA3() {
		FixComparison.Verdict v = FixComparison.compare(side(4, 352, 235, 3, 0, 1, 0, 0, 0), side(1, 383, 24, 1, 0, 0, 0, 0, 0, 0));
		assertEquals(FixComparison.Kind.SAME, v.kind());
		close(1.9359307359307358, v.phi());
		close(0.4683861039255815, v.pLess());
	}

	@Test
	void bThenAIsLess() {
		FixComparison.Verdict v = FixComparison.compare(side(28, 140, 1507, 12, 12, 4), side(4, 352, 235, 1, 1, 0, 1, 0, 1));
		assertEquals(FixComparison.Kind.LESS, v.kind());
		close(1.7678571428571428, v.phi());
		close(1.5161507257275237e-07, v.pLess());
		close(12, v.beforePerMinute());
	}

	// C4: 14 hitches in one post-teleport burst. Its own dispersion is 14; pooled with the after side it's 10.37, and the
	// effective counts (1 vs 0) can't tell anything apart. Without the guard one burst would read as "less".
	@Test
	void c4sSingleBurstIsNoClearChangeAndLessOnlyWithTheGuardOff() {
		SessionOutcome c4 = side(14, 131, 533, 0, 0, 14);
		SessionOutcome after = side(5, 131, 150, 2, 2, 1);
		close(14, FixComparison.dispersion(c4));
		FixComparison.Verdict v = FixComparison.compare(c4, after);
		assertEquals(FixComparison.Kind.SAME, v.kind());
		close(10.368421052631579, v.phi());
		close(0.5, v.pLess());
		close(1.0, v.pMore());
		FixComparison.Verdict naive = FixComparison.compare(c4, after, false);
		assertEquals(FixComparison.Kind.LESS, naive.kind());
		close(1, naive.phi());
		close(0.0317840576171875, naive.pLess());
	}

	// sf §1.3: a perfect fix at the floor of 8 hitches is the smallest one the test can tell from chance.
	@Test
	void eightToZeroIsLess() {
		FixComparison.Verdict v = FixComparison.compare(side(8, 300, 400, 2, 2, 1, 2, 1), side(0, 300, 0, 0, 0, 0, 0, 0));
		assertEquals(FixComparison.Kind.LESS, v.kind());
		close(1, v.phi());
		close(0.00390625, v.pLess());
		assertEquals(0, v.afterPerMinute());
	}

	@Test
	void tenToTwentyIsMore() {
		FixComparison.Verdict v = FixComparison.compare(side(10, 300, 500, 2, 2, 2, 2, 2), side(20, 300, 1000, 4, 4, 4, 4, 4));
		assertEquals(FixComparison.Kind.MORE, v.kind());
		close(0.04936857335269451, v.pMore());
		close(0.9786130273714662, v.pLess());
		close(2, v.beforePerMinute());
		close(4, v.afterPerMinute());
	}

	// Fewer hitches but more milliseconds lost a minute: never "less".
	@Test
	void fewerHitchesButMoreLostTimeIsNoClearChange() {
		FixComparison.Verdict v = FixComparison.compare(side(20, 600, 1000, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
				side(4, 600, 3000, 1, 0, 1, 0, 1, 0, 1, 0, 0, 0));
		assertEquals(FixComparison.Kind.SAME, v.kind());
		close(0.000771939754486084, v.pLess());
		close(100, v.lostBeforePerMinute());
		close(300, v.lostAfterPerMinute());
	}

	@Test
	void zeroOrOneBinMeansNoDispersion() {
		close(1, FixComparison.dispersion(side(10, 50, 300, 10), side(0, 50, 0, 0)));
		close(1, FixComparison.dispersion(side(10, 50, 300, 10), SessionOutcome.NONE));
		close(1, FixComparison.dispersion(SessionOutcome.NONE, SessionOutcome.NONE));
		// Two bins aren't enough to estimate a variance either.
		close(1, FixComparison.dispersion(side(10, 100, 300, 0, 10), side(0, 100, 0, 0, 0)));
		FixComparison.Verdict v = FixComparison.compare(side(10, 50, 300, 10), side(0, 50, 0, 0));
		assertEquals(FixComparison.Kind.LESS, v.kind());
		close(0.0009765625, v.pLess());
	}

	@Test
	void noHitchesOnEitherSideIsNoClearChange() {
		FixComparison.Verdict v = FixComparison.compare(side(0, 300, 0, 0, 0, 0, 0, 0), side(0, 300, 0, 0, 0, 0, 0, 0));
		assertEquals(FixComparison.Kind.SAME, v.kind());
		assertEquals(1, v.pLess());
		assertEquals(1, v.pMore());
	}

	@Test
	void noGameplayOnASideDecidesNothing() {
		FixComparison.Verdict v = FixComparison.compare(side(10, 300, 500, 2, 2, 2, 2, 2), SessionOutcome.NONE);
		assertEquals(FixComparison.Kind.SAME, v.kind());
		assertEquals(1, v.pLess());
		assertEquals(1, v.pMore());
	}

	// n is capped at 100,000 (both counts scaled): the answer keeps its direction and stays a probability.
	@Test
	void hugeCountsAreCapped() {
		FixComparison.Verdict v = FixComparison.compare(new SessionOutcome(1, 36_000, 150_000, 1e6, 1, 150_000, 0),
				new SessionOutcome(1, 36_000, 50_000, 3e5, 1, 50_000, 0));
		assertEquals(FixComparison.Kind.LESS, v.kind());
		assertEquals(0, v.pLess(), 1e-12);
	}

	@Test
	void kindIdsRoundTrip() {
		for (FixComparison.Kind kind : FixComparison.Kind.values()) {
			assertEquals(kind, FixComparison.Kind.of(kind.id()));
		}
		assertEquals("less", FixComparison.Kind.LESS.id());
		assertEquals("same", FixComparison.Kind.SAME.id());
		assertEquals("more", FixComparison.Kind.MORE.id());
		assertEquals(null, FixComparison.Kind.of("better"));
	}
}
