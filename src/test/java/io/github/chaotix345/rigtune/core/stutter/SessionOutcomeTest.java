package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 5: a session's outcome for the comparison (hitches per 60 s of wall time, by each hitch's start).
class SessionOutcomeTest {
	private static final long S = StutterAnalyzer.SECOND;

	private static void close(double expected, double actual) {
		assertEquals(expected, actual, 1e-9);
	}

	@Test
	void hitchesAreBinnedPerMinuteOfWallTimeWithThePartialLastBin() {
		// 131 s: bins [0-60), [60-120), [120-131]: one hitch, none, two.
		SessionOutcome o = SessionOutcome.of(125, 131, 300, new long[]{10 * S, 121 * S, 130 * S});
		assertEquals(1, o.sessions());
		assertEquals(3, o.hitches());
		assertEquals(3, o.bins());
		close(1, o.binMean());
		close(1, o.binVariance());
		close(125, o.gameplaySeconds());
		close(300, o.lostMs());
	}

	@Test
	void oneBinHasNoVarianceAndAHitchPastTheEndStaysInTheLastBin() {
		SessionOutcome o = SessionOutcome.of(40, 50, 90, new long[]{20 * S, 55 * S});
		assertEquals(1, o.bins());
		close(2, o.binMean());
		close(0, o.binVariance());
		SessionOutcome empty = SessionOutcome.of(0, 0, 0, new long[0]);
		assertEquals(1, empty.bins());
		assertEquals(0, empty.hitches());
	}

	// The after side accumulates: two sessions' pooled statistics equal those of all their bins together.
	@Test
	void plusEqualsTheConcatenatedBins() {
		SessionOutcome a = FixComparisonTest.side(6, 170, 200, 3, 1, 2);
		SessionOutcome b = FixComparisonTest.side(9, 230, 500, 0, 4, 2, 3);
		SessionOutcome all = FixComparisonTest.side(15, 400, 700, 3, 1, 2, 0, 4, 2, 3);
		SessionOutcome sum = a.plus(b);
		assertEquals(2, sum.sessions());
		assertEquals(15, sum.hitches());
		assertEquals(7, sum.bins());
		close(400, sum.gameplaySeconds());
		close(700, sum.lostMs());
		close(all.binMean(), sum.binMean());
		close(all.binVariance(), sum.binVariance());
		assertEquals(a, a.plus(SessionOutcome.NONE));
		assertEquals(b, SessionOutcome.NONE.plus(b));
	}

	// Values from a hand-edited file can't reach the maths as negatives or NaN.
	@Test
	void junkValuesReadAsZero() {
		SessionOutcome o = new SessionOutcome(-1, Double.NaN, -4, -3, -2, Double.POSITIVE_INFINITY, -1);
		assertEquals(SessionOutcome.NONE, o);
	}

	// From an analysis: its hitches (spikes less than 100 ms apart count once), gameplay and lost time.
	@Test
	void fromAnAnalysis() {
		long start = 1_000 * S;
		List<Attributor.Attribution> attributions = new ArrayList<>();
		for (long end : new long[]{start + 5 * S, start + 5 * S + 50 * StutterAnalyzer.MS, start + 70 * S}) {
			SpikeDetector.Spike spike = new SpikeDetector.Spike(end, 40 * StutterAnalyzer.MS, 10 * StutterAnalyzer.MS);
			attributions.add(new Attributor.Attribution(spike, Map.of(), List.of(), Set.of(), spike.lost()));
		}
		StutterReport report = new StutterReport(Instant.EPOCH.toString(), StutterReport.MONITOR, "26.2", "G1", 4096, 90, 80, 8000, 100, 50, null, null,
				null, 90, Map.of(), Map.of(), List.of(), null, List.of(), false, true, 2);
		StutterAnalyzer.Result result = new StutterAnalyzer.Result(report, null, attributions, null);
		SessionOutcome o = SessionOutcome.of(result, start);
		assertEquals(2, o.hitches());
		assertEquals(2, o.bins());
		close(1, o.binMean());
		close(0, o.binVariance());
		close(80, o.gameplaySeconds());
		close(90, o.lostMs());
	}

	// A spike right after a settings change (RW-11's settingsChanged tag) is the change's, on either side: left out of the
	// hitches and the lost time.
	@Test
	void spikesAfterASettingsChangeAreLeftOut() {
		long start = 1_000 * S;
		List<Attributor.Attribution> attributions = new ArrayList<>();
		for (long end : new long[]{start + 5 * S, start + 30 * S, start + 70 * S}) {
			SpikeDetector.Spike spike = new SpikeDetector.Spike(end, 40 * StutterAnalyzer.MS, 10 * StutterAnalyzer.MS);
			Set<String> tags = end == start + 30 * S ? Set.of(Attributor.SETTINGS_CHANGED) : Set.of();
			attributions.add(new Attributor.Attribution(spike, Map.of(), List.of(), tags, spike.lost()));
		}
		StutterReport report = new StutterReport(Instant.EPOCH.toString(), StutterReport.MONITOR, "26.2", "G1", 4096, 90, 80, 8000, 100, 50, null, null,
				null, 90, Map.of(), Map.of(), List.of(), null, List.of(), false, true, 3);
		SessionOutcome o = SessionOutcome.of(new StutterAnalyzer.Result(report, null, attributions, null), start);
		assertEquals(2, o.hitches());
		close(60, o.lostMs());
		close(80, o.gameplaySeconds());
	}
}
