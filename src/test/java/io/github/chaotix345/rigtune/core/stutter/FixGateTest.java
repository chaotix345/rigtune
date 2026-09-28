package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.3 (FixGateTest): the client floor rules can't lower. Each failing condition alone blocks with its reason.
class FixGateTest {
	static StutterReport report(String source, double gameplaySeconds, int hitches) {
		return new StutterReport("2026-10-02T08:59:12Z", source, "26.2", "G1", 4096, gameplaySeconds + 20, gameplaySeconds, 50_000, 120, 60, null, null,
				new StutterReport.Spikes(hitches, 0, 0, 0), 40.0 * hitches, Map.of("chunkBuild", 0.5), Map.of(), List.of(), null, List.of(), true, true,
				hitches);
	}

	@Test
	void theFloorHoldsAtEightHitchesAndFiveMinutes() {
		assertEquals(8, FixGate.MIN_HITCHES);
		assertEquals(300, FixGate.MIN_GAMEPLAY_SECONDS);
		assertNull(FixGate.check(report(StutterReport.MONITOR, 300, 8), false, false, true));
		assertNull(FixGate.check(report(StutterReport.MONITOR, 1200, 40), false, false, true));
	}

	@Test
	void eachConditionAloneBlocks() {
		assertEquals(FixOffer.Reason.LENGTH, FixGate.check(report(StutterReport.MONITOR, 300, 7), false, false, true));
		assertEquals(FixOffer.Reason.LENGTH, FixGate.check(report(StutterReport.MONITOR, 299.9, 8), false, false, true));
		assertEquals(FixOffer.Reason.BENCHMARK, FixGate.check(report(StutterReport.BENCHMARK, 600, 30), false, false, true));
		assertEquals(FixOffer.Reason.BENCHMARK, FixGate.check(report("something-else", 600, 30), false, false, true));
		assertEquals(FixOffer.Reason.BUSY, FixGate.check(report(StutterReport.MONITOR, 600, 30), false, true, true));
		assertEquals(FixOffer.Reason.STORE, FixGate.check(report(StutterReport.MONITOR, 600, 30), false, false, false));
		// WS-B's M4 rule for C20: a session around a benchmark run or while Distant Horizons generated terrain can't be a
		// comparison's before side.
		assertEquals(FixOffer.Reason.EXCLUDED, FixGate.check(report(StutterReport.MONITOR, 600, 30), true, false, true));
	}

	static StutterReport idle(StutterReport r, double idleSeconds) {
		return new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), r.sessionSeconds(), r.gameplaySeconds(), r.frames(),
				r.avgFps(), r.onePercentLowFps(), r.histogramCounts(), r.histogramTimeMs(), r.spikes(), r.lostMs(), r.causes(), r.tags(), r.worst(), r.facts(),
				r.advice(), r.enoughData(), r.phaseTiming(), r.hitches(), r.settingsAtStart(), r.settingsAtEnd(), idleSeconds);
	}

	// RW-17 for C20 (the coordinator): a session the game throttled (idle) for longer than it was played can't be a
	// comparison's before side; after excluded, before the store.
	@Test
	void aMostlyIdleSessionIsNoBeforeSide() {
		assertEquals(FixOffer.Reason.IDLE, FixGate.check(idle(report(StutterReport.MONITOR, 600, 30), 600.5), false, false, true));
		assertNull(FixGate.check(idle(report(StutterReport.MONITOR, 600, 30), 600), false, false, true));
		assertEquals(FixOffer.Reason.EXCLUDED, FixGate.check(idle(report(StutterReport.MONITOR, 600, 30), 900), true, false, true));
		assertEquals(FixOffer.Reason.IDLE, FixGate.check(idle(report(StutterReport.MONITOR, 10, 1), 900), false, true, false));
	}

	// The real capture behind RW-17 (SPEC.md, "real world 2026-09-28"): 19 h in the world, 17.4 h of it throttled AFK. Saved
	// before RW-17 as 67,209 s of gameplay with 96 hitches, it passed the floor; with its idle time counted apart (62,640 s
	// idle, 4,569 s played) it's no before side. A session without idleSeconds (0.4's, older) counts as no idle.
	@Test
	void theRealAfkCaptureIsNoBeforeSide() {
		assertEquals(FixOffer.Reason.IDLE, FixGate.check(idle(report(StutterReport.MONITOR, 4_569, 96), 62_640), false, false, true));
		assertTrue(FixGate.idle(idle(report(StutterReport.MONITOR, 4_569, 96), 62_640)));
		assertNull(FixGate.check(report(StutterReport.MONITOR, 67_209, 96), false, false, true), "saved before RW-17: no idle known");
		assertFalse(FixGate.idle(report(StutterReport.MONITOR, 67_209, 96)));
	}

	// review-11 STUTTER-3: a setup that changed during the session (the client's check, or RW-11's start and end settings)
	// blocks after IDLE and before the store; STUTTER-6: LENGTH goes by the outcome the comparison would take.
	@Test
	void aChangedSetupAndTheOutcomesLength() {
		StutterReport r = report(StutterReport.MONITOR, 600, 30);
		assertEquals(FixOffer.Reason.CHANGED, FixGate.check(r, FixGate.outcome(r), false, true, true, false));
		assertEquals(FixOffer.Reason.CHANGED, FixGate.check(r.withSettings(Map.of(StutterReport.SHADERS, "true"), Map.of(StutterReport.SHADERS, "false")),
				false, false, true));
		assertNull(FixGate.check(r.withSettings(Map.of(StutterReport.SHADERS, "true"), Map.of(StutterReport.SHADERS, "true")), false, false, true));
		assertEquals(FixOffer.Reason.IDLE, FixGate.check(idle(r, 700), FixGate.outcome(r), false, true, false, true));
		assertEquals(FixOffer.Reason.LENGTH, FixGate.check(r, new SessionOutcome(1, 600, 7, 100, 10, 0.7, 1), false, false, false, true));
		assertEquals(FixOffer.Reason.LENGTH, FixGate.check(r, new SessionOutcome(1, 299, 30, 100, 5, 6, 1), false, false, false, true));
	}

	// When several fail, the first of benchmark, excluded, store, busy, length.
	@Test
	void theOrderOfReasons() {
		assertEquals(FixOffer.Reason.BENCHMARK, FixGate.check(report(StutterReport.BENCHMARK, 10, 1), true, true, false));
		assertEquals(FixOffer.Reason.EXCLUDED, FixGate.check(report(StutterReport.MONITOR, 10, 1), true, true, false));
		assertEquals(FixOffer.Reason.BENCHMARK, FixGate.check(report(StutterReport.BENCHMARK, 10, 1), false, true, false));
		assertEquals(FixOffer.Reason.STORE, FixGate.check(report(StutterReport.MONITOR, 10, 1), false, true, false));
		assertEquals(FixOffer.Reason.BUSY, FixGate.check(report(StutterReport.MONITOR, 10, 1), false, true, true));
	}
}
