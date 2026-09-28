package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
