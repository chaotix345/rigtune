package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThrottleTest {
	private static final int UNCAPPED = 260;

	@Test
	void focusedUncappedRunIsNotThrottled() {
		Throttle throttle = new Throttle();
		for (int i = 0; i < 100; i++) {
			throttle.sample(true, UNCAPPED);
		}
		assertFalse(throttle.throttled(UNCAPPED, true));
		assertFalse(throttle.throttled(UNCAPPED, false));
	}

	// Phase 5 (d2): Dynamic FPS in an unfocused window, frame limit 15, about 1 FPS measured.
	@Test
	void aFrameLimitBelowUncappedIsThrottled() {
		Throttle throttle = new Throttle();
		throttle.sample(true, UNCAPPED);
		throttle.sample(true, 15);
		throttle.sample(true, UNCAPPED);
		assertTrue(throttle.throttled(UNCAPPED, false));
	}

	@Test
	void anInactiveWindowIsThrottledOnlyWhenAModSlowsUnfocusedWindows() {
		Throttle throttle = new Throttle();
		throttle.sample(true, UNCAPPED);
		throttle.sample(false, UNCAPPED);
		assertTrue(throttle.throttled(UNCAPPED, true));
		assertFalse(throttle.throttled(UNCAPPED, false));
	}

	@Test
	void resetStartsAFreshStep() {
		Throttle throttle = new Throttle();
		throttle.sample(false, 15);
		throttle.reset();
		throttle.sample(true, UNCAPPED);
		assertFalse(throttle.throttled(UNCAPPED, true));
	}

	@Test
	void noSamplesIsNotThrottled() {
		assertFalse(new Throttle().throttled(UNCAPPED, true));
	}
}
