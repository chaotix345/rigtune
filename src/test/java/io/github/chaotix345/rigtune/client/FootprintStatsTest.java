package io.github.chaotix345.rigtune.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 10: FootprintStats keeps the launch's own timings (a game test calls onPreLaunch() again later), and
// times the CLIENT_STARTED handler even when it throws. Nothing else in the unit tests touches these statics.
class FootprintStatsTest {
	@Test
	void onlyTheFirstInitCallCounts() throws InterruptedException {
		long start = FootprintStats.initStart();
		FootprintStats.initEnd(start);
		FootprintStats.Snapshot first = FootprintStats.snapshot();
		assertTrue(first.initWallNs() >= 0, "wall time recorded: " + first);
		assertTrue(first.initCpuNs() >= 0, "CPU time recorded (HotSpot supports thread CPU time): " + first);
		assertTrue(first.mxInitNs() >= 0, "the ThreadMXBean's first-call cost is kept apart: " + first);

		long again = FootprintStats.initStart();
		Thread.sleep(30);
		FootprintStats.initEnd(again);
		assertEquals(first.initWallNs(), FootprintStats.snapshot().initWallNs());
		assertEquals(first.initCpuNs(), FootprintStats.snapshot().initCpuNs());
	}

	@Test
	void theClientStartedHandlerIsTimedEvenWhenItThrows() {
		AtomicBoolean ran = new AtomicBoolean();
		assertThrows(IllegalStateException.class, () -> FootprintStats.clientStarted(() -> {
			ran.set(true);
			throw new IllegalStateException("start failed");
		}));
		assertTrue(ran.get());
		assertTrue(FootprintStats.snapshot().clientStartedWallNs() >= 0);
	}

	@Test
	void theWindowTotalIsNullUntilMeasuredAndSumsThreads() {
		assertEquals(null, new FootprintStats.Snapshot(0, 0, 0, 0, 0, 0, 0, null).windowCpuTotalNs());
		assertEquals(30L, new FootprintStats.Snapshot(0, 0, 0, 0, 0, 0, 0,
				java.util.Map.of("RigTune worker", 10L, "RigTune rules", 20L)).windowCpuTotalNs());
	}
}
