package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.4 for the real hook: StutterMonitor.onFrame with the phase timers, off and on, allocates nothing; the capture
// lifecycle and the phase-timing verdict (plan review S-M1).
class StutterMonitorTest {
	@AfterEach
	void stopEverything() {
		StutterMonitor.Capture s = StutterMonitor.session();
		if (s != null) {
			StutterMonitor.stop(s);
		}
		StutterMonitor.Capture b = StutterMonitor.benchmark();
		if (b != null) {
			StutterMonitor.stop(b);
		}
	}

	static long allocated() {
		return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
	}

	static void frames(int n) {
		for (int i = 0; i < n; i++) {
			StutterMonitor.packetsStart();
			StutterMonitor.chunkLoaded();
			StutterMonitor.packetsEnd();
			StutterMonitor.tickStart();
			StutterMonitor.tickEnd();
			StutterMonitor.renderStart();
			StutterMonitor.onFrame(7_000_000L);
		}
	}

	@Test
	void aMillionFramesAllocateNothingOffOrOn() {
		Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported());
		// Anything allocated per frame would be at least 16 MB over a million frames; the bound only absorbs the probe's own
		// few bytes.
		frames(1_000_000);
		long before = allocated();
		frames(1_000_000);
		long off = allocated() - before;
		assertTrue(off < 1024, "monitor off: " + off + " bytes");

		StutterMonitor.startSession(new StutterRings(0), System.nanoTime(), Instant.now());
		frames(1_000_000);
		before = allocated();
		frames(1_000_000);
		long on = allocated() - before;
		assertTrue(on < 1024, "monitor on: " + on + " bytes");
	}

	@Test
	void sessionAndBenchmarkShareTheRingsUntilTheLastStops() {
		assertFalse(StutterMonitor.active());
		assertEquals(0, StutterMonitor.retainedBytes());
		StutterRings rings = new StutterRings(0);
		StutterMonitor.Capture session = StutterMonitor.startSession(rings, 1, Instant.EPOCH);
		StutterMonitor.Capture bench = StutterMonitor.startBenchmark(rings, 2, Instant.EPOCH);
		assertTrue(StutterMonitor.active());
		assertTrue(bench.paused(), "the benchmark records only its sweeps");
		assertEquals(session.retainedBytes() + bench.retainedBytes() + rings.retainedBytes(), StutterMonitor.retainedBytes());
		assertFalse(StutterMonitor.stop(session), "the benchmark still uses the rings");
		assertTrue(StutterMonitor.active());
		assertTrue(StutterMonitor.stop(bench));
		assertFalse(StutterMonitor.active());
		assertNull(StutterMonitor.rings());
		assertEquals(0, StutterMonitor.retainedBytes(), "the buffers are released");
	}

	@Test
	void theFirstFrameAndTheFrameAfterAPauseAreExcluded() {
		StutterMonitor.Capture session = StutterMonitor.startSession(new StutterRings(0), System.nanoTime(), Instant.now());
		StutterMonitor.onFrame(7_000_000L);
		StutterMonitor.onFrame(7_000_000L);
		session.paused = true;
		StutterMonitor.onFrame(7_000_000L);
		session.paused = false;
		StutterMonitor.onFrame(900_000_000L);
		StutterMonitor.onFrame(7_000_000L);
		FrameRing.Snapshot s = session.snapshot();
		assertEquals(4, s.frames(), "the paused frame isn't recorded");
		assertEquals(2, s.excludedFrames());
		assertTrue(FrameRing.Snapshot.excluded(s.ends()[0]));
		assertFalse(FrameRing.Snapshot.excluded(s.ends()[1]));
		assertTrue(FrameRing.Snapshot.excluded(s.ends()[2]), "spans the pause");
		assertEquals(14_000_000L, s.gameplayNanos());
	}

	@Test
	void menusAndWorldLoadingAreExcluded() {
		StutterMonitor.Capture session = StutterMonitor.startSession(new StutterRings(0), System.nanoTime(), Instant.now());
		StutterMonitor.onFrame(7_000_000L);
		StutterMonitor.setExcluded(true);
		StutterMonitor.onFrame(7_000_000L);
		StutterMonitor.setExcluded(false);
		StutterMonitor.onFrame(7_000_000L);
		StutterMonitor.levelChanged(System.nanoTime());
		StutterMonitor.onFrame(7_000_000L);
		StutterMonitor.levelChanged(System.nanoTime() - StutterMonitor.LOADING_NANOS);
		StutterMonitor.onFrame(7_000_000L);
		FrameRing.Snapshot s = session.snapshot();
		assertEquals(5, s.frames());
		assertEquals(3, s.excludedFrames(), "the first frame, the menu frame and the world-loading frame");
		assertEquals(2, session.snapshot().gameplayFrames());
		long[] events = StutterMonitor.rings().snapshot().events();
		assertEquals(StutterRings.LEVEL_CHANGE, events[0]);
	}

	// S-M1: every required timer fired, and the limiter pair both halves or neither.
	@Test
	void phaseTimingNeedsCompletePairs() {
		StutterMonitor.startSession(new StutterRings(0), System.nanoTime(), Instant.now());
		frames(3);
		if ((StutterMonitor.phaseSeen() & StutterMonitor.LIMITER) == 0) {
			assertTrue(StutterMonitor.phaseTiming(), "the limiter never ran (uncapped): fine");
		}
		StutterMonitor.limiterStart();
		if ((StutterMonitor.phaseSeen() & StutterMonitor.LIMITER_END) == 0) {
			assertFalse(StutterMonitor.phaseTiming(), "half a pair");
		}
		StutterMonitor.limiterEnd();
		assertTrue(StutterMonitor.phaseTiming());
		assertEquals(StutterMonitor.REQUIRED | StutterMonitor.LIMITER, StutterMonitor.phaseSeen());
	}
}
