package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettleCheckTest {
	private static final Protocol PROTOCOL = new Protocol(2.0, 20.0, 1.5, List.of(new Protocol.Sweep(8, 0f)));
	private static final SettleCheck.Count ALL_THERE = new SettleCheck.Count(377, 0);

	@Test
	void theAreaIsACircleOfRadiusRdMinusOne() {
		// The research probe's 377 chunks within 11 of the camera (docs/research/v0.3/benchmark.md §1.2).
		assertEquals(377, SettleCheck.count(11, 0, 0, (x, z) -> true).inRange());
		assertEquals(1, SettleCheck.count(0, 5, -3, (x, z) -> true).inRange());
		assertEquals(5, SettleCheck.count(1, 0, 0, (x, z) -> true).inRange());
	}

	@Test
	void onlyMissingChunksInsideTheCircleCount() {
		Set<Long> missing = Set.of(key(100 + 11, 200), key(100 + 8, 200 + 8), key(100 + 7, 200 + 7));
		SettleCheck.Count count = SettleCheck.count(11, 100, 200, (x, z) -> !missing.contains(key(x, z)));
		// (8, 8) is outside the circle (128 > 121); (11, 0) and (7, 7) are inside.
		assertEquals(377, count.inRange());
		assertEquals(2, count.missing());
	}

	@Test
	void settlesAfterTheMinimumAndTenReadyTicks() {
		SettleCheck check = new SettleCheck(PROTOCOL, 11);
		for (int i = 0; i < 9; i++) {
			assertEquals(Optional.empty(), check.tick(2.0 + i * 0.05, ALL_THERE, true));
		}
		SettleCheck.Result result = check.tick(2.5, ALL_THERE, true).orElseThrow();
		assertFalse(result.timedOut());
		assertEquals(11, result.radius());
		assertEquals(377, result.inRange());
		assertEquals(0, result.missing());
		assertEquals(2.5, result.seconds());
		assertTrue(result.complete());
	}

	@Test
	void readyTicksBeforeTheMinimumCountButDontEndIt() {
		SettleCheck check = new SettleCheck(PROTOCOL, 11);
		for (int i = 0; i < 20; i++) {
			assertEquals(Optional.empty(), check.tick(i * 0.05, ALL_THERE, true));
		}
		assertTrue(check.tick(2.0, ALL_THERE, true).isPresent());
	}

	@Test
	void aMissingChunkOrUnbuiltSectionsRestartTheStreak() {
		SettleCheck check = new SettleCheck(PROTOCOL, 11);
		for (int i = 0; i < 9; i++) {
			check.tick(3.0, ALL_THERE, true);
		}
		assertEquals(Optional.empty(), check.tick(3.0, new SettleCheck.Count(377, 1), true));
		for (int i = 0; i < 9; i++) {
			assertEquals(Optional.empty(), check.tick(3.0, ALL_THERE, true));
		}
		assertEquals(Optional.empty(), check.tick(3.0, ALL_THERE, false));
		for (int i = 0; i < 9; i++) {
			assertEquals(Optional.empty(), check.tick(3.0, ALL_THERE, true));
		}
		assertTrue(check.tick(3.0, ALL_THERE, true).isPresent());
	}

	@Test
	void sectionsReadyAloneNeverSettles() {
		// The research's stall: Sodium reported the terrain complete while 210 of 377 chunks hadn't arrived.
		SettleCheck check = new SettleCheck(PROTOCOL, 11);
		for (int i = 0; i < 100; i++) {
			assertEquals(Optional.empty(), check.tick(2.0 + i * 0.1, new SettleCheck.Count(377, 210), true));
		}
	}

	@Test
	void timesOutWithTheLastMissingCount() {
		SettleCheck check = new SettleCheck(PROTOCOL, 11);
		check.tick(5.0, new SettleCheck.Count(377, 50), true);
		SettleCheck.Result result = check.tick(20.0, new SettleCheck.Count(377, 8), true).orElseThrow();
		assertTrue(result.timedOut());
		assertEquals(8, result.missing());
		assertEquals(20.0, result.seconds());
	}

	@Test
	void aTimedOutStepIsCompleteOnlyWithAtMostTwoPercentMissing() {
		assertTrue(new SettleCheck.Result(11, 377, 0, 20, true).complete());
		assertTrue(new SettleCheck.Result(11, 377, 7, 20, true).complete());
		assertFalse(new SettleCheck.Result(11, 377, 8, 20, true).complete());
		assertTrue(new SettleCheck.Result(11, 377, 0, 3, false).complete());
	}

	@Test
	void aQuickProtocolWithoutMinimumSettlesOnTheTenthReadyTick() {
		SettleCheck check = new SettleCheck(new Protocol(0, 2.0, 1.5, List.of(new Protocol.Sweep(6, 0f))), 4);
		for (int i = 0; i < 9; i++) {
			assertEquals(Optional.empty(), check.tick(i * 0.05, new SettleCheck.Count(49, 0), true));
		}
		assertTrue(check.tick(0.45, new SettleCheck.Count(49, 0), true).isPresent());
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}
}
