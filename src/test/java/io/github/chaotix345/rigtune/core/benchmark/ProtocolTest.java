package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolTest {
	@Test
	void defaultFullWorstCaseIs37point5() {
		assertEquals(37.5, Timing.DEFAULT.full().worstCaseSeconds(), 1e-9);
		assertEquals(16.0, Timing.DEFAULT.full().measuredSeconds(), 1e-9);
	}

	@Test
	void defaultQuickWorstCaseIs9point5() {
		assertEquals(9.5, Timing.DEFAULT.quick().worstCaseSeconds(), 1e-9);
		assertEquals(6.0, Timing.DEFAULT.quick().measuredSeconds(), 1e-9);
	}

	@Test
	void fullHasLevelThenDownSweeps() {
		Protocol full = Timing.DEFAULT.full();
		assertEquals(List.of(new Protocol.Sweep(8.0, 0f), new Protocol.Sweep(8.0, 25f)), full.sweeps());
		assertEquals(2.0, full.settleMinSeconds());
		assertEquals(20.0, full.settleTimeoutSeconds());
		assertEquals(1.5, full.warmupSeconds());
	}

	@Test
	void quickHasNoSettleMinimum() {
		Protocol quick = Timing.DEFAULT.quick();
		assertEquals(0.0, quick.settleMinSeconds());
		assertEquals(2.0, quick.settleTimeoutSeconds());
		assertEquals(1.5, quick.warmupSeconds());
		assertEquals(List.of(new Protocol.Sweep(6.0, 0f)), quick.sweeps());
	}

	@Test
	void defaultDeadlineAndRepeats() {
		assertEquals(300.0, Timing.DEFAULT.deadlineSeconds());
		assertEquals(2, Timing.DEFAULT.repeats());
		assertEquals(6, Timing.DEFAULT.maxRdSteps());
	}
}
