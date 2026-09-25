package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkMathTest {
	private static FrameStats stats(double avg, double low, double p99) {
		return new FrameStats(1000, avg, low, p99, p99 * 2);
	}

	@Test
	void cvOfTwoValuesUsesSampleStddev() {
		// mean 105, sample stddev |110 - 100| / sqrt(2) = 7.0711
		assertEquals(7.0710678 / 105, BenchmarkMath.cv(100, 110), 1e-6);
	}

	@Test
	void cvNeedsTwoValues() {
		assertNull(BenchmarkMath.cv(100));
		assertNull(BenchmarkMath.cv());
	}

	@Test
	void cvOfZeroMeanIsNull() {
		assertNull(BenchmarkMath.cv(0, 0));
	}

	@Test
	void cvOfIdenticalValuesIsZero() {
		assertEquals(0.0, BenchmarkMath.cv(120, 120, 120));
	}

	@Test
	void aggregateAveragesRepeats() {
		BenchmarkMath.Aggregate a = BenchmarkMath.aggregate(List.of(stats(200, 100, 9), stats(220, 110, 11)));
		assertNotNull(a);
		assertEquals(210, a.avgFps(), 1e-9);
		assertEquals(105, a.onePercentLowFps(), 1e-9);
		assertEquals(10, a.p99FrameMs(), 1e-9);
		assertEquals(2, a.repeats());
		assertEquals(BenchmarkMath.cv(100, 110), a.cv());
	}

	@Test
	void aggregateOfOneHasNoCv() {
		BenchmarkMath.Aggregate a = BenchmarkMath.aggregate(List.of(stats(200, 100, 9)));
		assertNotNull(a);
		assertEquals(1, a.repeats());
		assertNull(a.cv());
	}

	@Test
	void aggregateOfNothingIsNull() {
		assertNull(BenchmarkMath.aggregate(List.of()));
	}

	@Test
	void noisyAboveFivePercent() {
		assertFalse(BenchmarkMath.noisy(0.05));
		assertTrue(BenchmarkMath.noisy(0.0501));
		assertFalse(BenchmarkMath.noisy(null));
	}

	@Test
	void gainPercent() {
		assertEquals(12.0, BenchmarkMath.gainPercent(100, 112), 1e-9);
		assertEquals(-25.0, BenchmarkMath.gainPercent(200, 150), 1e-9);
	}

	@Test
	void gainPercentOfZeroBeforeIsZero() {
		assertEquals(0.0, BenchmarkMath.gainPercent(0, 50));
	}

	private static BenchmarkMath.Aggregate agg(double avg, double low, Double cv) {
		return new BenchmarkMath.Aggregate(avg, low, 5, cv == null ? 1 : 2, cv);
	}

	@Test
	void gainBelowNoiseFloorIsNotSignificant() {
		// floor = 2 x max(0.03, 0.02) = 6%
		BenchmarkMath.Gain gain = BenchmarkMath.gain(agg(200, 100, 0.03), agg(210, 105, 0.02));
		assertEquals(5.0, gain.lowPercent(), 1e-9);
		assertEquals(5.0, gain.avgPercent(), 1e-9);
		assertFalse(gain.significant());
	}

	@Test
	void gainAtNoiseFloorIsSignificant() {
		assertTrue(BenchmarkMath.gain(agg(200, 100, 0.03), agg(200, 106, 0.02)).significant());
	}

	@Test
	void missingCvCountsAsFivePercent() {
		assertFalse(BenchmarkMath.gain(agg(200, 100, null), agg(200, 109, 0.01)).significant());
		assertTrue(BenchmarkMath.gain(agg(200, 100, null), agg(200, 110, 0.01)).significant());
	}

	@Test
	void negativeGainCanBeSignificant() {
		BenchmarkMath.Gain gain = BenchmarkMath.gain(agg(200, 100, 0.01), agg(180, 80, 0.01));
		assertEquals(-20.0, gain.lowPercent(), 1e-9);
		assertEquals(-10.0, gain.avgPercent(), 1e-9);
		assertTrue(gain.significant());
	}

	@Test
	void percentFormatting() {
		assertEquals("+12.3%", BenchmarkMath.percent(12.34));
		assertEquals("-3.0%", BenchmarkMath.percent(-2.96));
		assertEquals("+0.0%", BenchmarkMath.percent(-0.04));
		assertEquals("+1234.5%", BenchmarkMath.percent(1234.5));
	}
}
