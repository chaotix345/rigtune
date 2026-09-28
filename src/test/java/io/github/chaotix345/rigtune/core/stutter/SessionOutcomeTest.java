package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	// The same play (per 200 frames: one 80 ms spike, nineteen 28 ms near-spikes, which are candidates but not spikes, the
	// rest 16 ms) for `frames` frames, captured into a ring of the given sizes.
	private static SessionOutcome play(int frames, int frameCapacity, int candidateCapacity) {
		long t0 = 50 * S;
		FrameRing ring = new FrameRing(frameCapacity, candidateCapacity);
		long now = t0;
		for (int i = 0; i < frames; i++) {
			long d = i % 200 == 199 ? 80 * StutterAnalyzer.MS : i % 10 == 9 ? 28 * StutterAnalyzer.MS : 16 * StutterAnalyzer.MS;
			now += d;
			ring.frame(now, d, false, 300_000, StutterAnalyzer.MS, d - 2 * StutterAnalyzer.MS, 0);
		}
		StutterAnalyzer.Result r = StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), new StutterRings(10 * S).snapshot(), t0, now,
				Instant.parse("2026-09-26T10:00:00Z"), StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, true, false));
		return SessionOutcome.of(r, t0);
	}

	// A capture of `frames` frames of 16 ms, an 80 ms spike where `spike` says, into rings of the given sizes; the settle
	// mark `markSeconds` after the start (none when negative), as the session monitor sets it.
	private static SessionOutcome capture(int frames, int frameCapacity, int candidateCapacity, java.util.function.IntPredicate spike, double markSeconds) {
		long t0 = 50 * S;
		FrameRing ring = new FrameRing(frameCapacity, candidateCapacity);
		if (markSeconds >= 0) {
			ring.markGameplayAt(t0 + (long) (markSeconds * S));
		}
		long now = t0;
		for (int i = 0; i < frames; i++) {
			long d = spike.test(i) ? 80 * StutterAnalyzer.MS : 16 * StutterAnalyzer.MS;
			now += d;
			ring.frame(now, d, false, 300_000, StutterAnalyzer.MS, d - 2 * StutterAnalyzer.MS, 0);
		}
		StutterAnalyzer.Result r = StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), new StutterRings(10 * S).snapshot(), t0, now,
				Instant.parse("2026-09-26T10:00:00Z"), StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, true, false));
		return SessionOutcome.of(r, t0);
	}

	// review-12 R12STUTTER-1: a capture's first minutes (a world join's chunk streaming) never count, on either side. The
	// before session joined (a burst of a spike every 30 frames for 3 minutes, then a spike every 200); the after session,
	// restarted mid-world by an immediate fix, plays the same steady ~17 hitches a minute from its start: no clear change.
	@Test
	void aJoinBurstNeverCounts() {
		int burstFrames = (int) (180 * S / (16 * StutterAnalyzer.MS)) - 1000;
		SessionOutcome joined = capture(48_000, 1 << 17, 4096, i -> i < burstFrames ? i % 30 == 29 : i % 200 == 199, SessionOutcome.SETTLE_NANOS / 1e9);
		SessionOutcome restarted = capture(48_000, 1 << 17, 4096, i -> i % 200 == 199, SessionOutcome.SETTLE_NANOS / 1e9);
		assertEquals(FixComparison.Kind.SAME, FixComparison.compare(joined, restarted).kind(), joined + " vs " + restarted);
		double ratio = perMinute(restarted) / perMinute(joined);
		assertTrue(ratio > 0.9 && ratio < 1.1, "the same steady rate: " + perMinute(joined) + " vs " + perMinute(restarted));
		assertTrue(joined.gameplaySeconds() < 48_000 * 0.0165 - 170, "the first 3 minutes left out: " + joined.gameplaySeconds());
	}

	// review-12 R12STUTTER-2: once both rings wrapped, the spikes are known back to the oldest candidate the ring still holds
	// (its gameplay stamp says how much play follows it), not only over the frame ring's window: 64 candidates of a spike
	// every 200 frames reach back ~12,800 frames where the frame ring holds 1,024.
	@Test
	void theComparedPlayReachesBackToTheOldestCandidate() {
		SessionOutcome o = capture(20_000, 1024, 64, i -> i % 200 == 199, -1);
		SessionOutcome whole = capture(20_000, 1 << 15, 4096, i -> i % 200 == 199, -1);
		assertTrue(o.gameplaySeconds() > 150, "the play back to the oldest held candidate: " + o);
		double ratio = perMinute(o) / perMinute(whole);
		assertTrue(ratio > 0.9 && ratio < 1.1, perMinute(o) + " vs " + perMinute(whole));
	}

	private static double perMinute(SessionOutcome o) {
		return o.hitches() * 60 / o.gameplaySeconds();
	}

	// review-11 STUTTER-2: hitches are known only where the rings still cover the capture. Once both the frame ring and the
	// candidate ring wrapped, the outcome counts the frame ring's window alone, over that window's gameplay: the same play
	// gives the same rate, however much of it the rings kept.
	@Test
	void theRateCountsOnlyTheCoveredWindow() {
		SessionOutcome whole = play(20_000, 1 << 15, 4096);
		SessionOutcome wrapped = play(20_000, 2048, 16);
		assertEquals(100, whole.hitches());
		double ratio = perMinute(wrapped) / perMinute(whole);
		assertTrue(ratio > 0.8 && ratio < 1.25, "the same rate: " + perMinute(wrapped) + " vs " + perMinute(whole) + " (" + wrapped + ")");
		assertTrue(wrapped.gameplaySeconds() < 40, "the frame ring's window: " + wrapped.gameplaySeconds());
		assertEquals(FixComparison.Kind.SAME, FixComparison.compare(whole, wrapped).kind());
	}
}
