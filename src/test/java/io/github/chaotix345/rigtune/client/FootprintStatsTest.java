package io.github.chaotix345.rigtune.client;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 10: FootprintStats keeps the launch's own timings (a game test calls onPreLaunch() again later), and
// times the CLIENT_STARTED handler even when it throws. Only this test and V05ServicesTest (the startup window and its
// flag) touch these statics; a test that needs the launch's first calls resets them first.
class FootprintStatsTest {
	@Test
	void onlyTheFirstInitCallCounts() throws InterruptedException {
		FootprintStats.resetStartupForTests();
		long start = FootprintStats.initStart();
		FootprintStats.initEnd(start);
		FootprintStats.Snapshot first = FootprintStats.snapshot();
		assertTrue(first.initWallNs() >= 0, "wall time recorded: " + first);
		assertTrue(first.initCpuNs() >= 0, "CPU time recorded (HotSpot supports thread CPU time): " + first);
		assertTrue(first.mxInitNs() >= 0, "the ThreadMXBean's first-call cost is kept apart: " + first);
		assertTrue(first.initCpuNs() <= first.initWallNs(), "one thread's CPU never exceeds its wall time: " + first);

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
	void singleThreadCpuIsClampedToWallTime() {
		assertEquals(140_000_000L, FootprintStats.singleThreadCpu(156_250_000L, 140_000_000L));
		assertEquals(100_000_000L, FootprintStats.singleThreadCpu(100_000_000L, 140_000_000L));
		assertEquals(FootprintStats.UNSET, FootprintStats.singleThreadCpu(FootprintStats.UNSET, 140_000_000L));
	}

	@Test
	void theWindowTotalIsNullUntilMeasuredAndSumsThreads() {
		assertEquals(null, new FootprintStats.Snapshot(0, 0, 0, 0, 0, 0, 0, null).windowCpuTotalNs());
		assertEquals(30L, new FootprintStats.Snapshot(0, 0, 0, 0, 0, 0, 0,
				java.util.Map.of("RigTune worker", 10L, "RigTune rules", 20L)).windowCpuTotalNs());
	}

	// docs/v0.5/SPEC.md X4.3, AC-X.2 (plan review PLAN-1): the flag is set only by a lazy resolution on the render thread
	// while it runs preLaunch, onInitializeClient or the CLIENT_STARTED handler, so the executor tasks the handler submits
	// can never race it. The test's own thread stands in for the render thread.
	@Test
	void aWorkerResolvingInsideTheWindowLeavesTheFlagUnset() throws Exception {
		FootprintStats.clearRenderThreadResolve();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			FootprintStats.clientStarted(() -> CompletableFuture.runAsync(() -> FootprintStats.lazyResolved("worker"), worker).join());
		} finally {
			worker.shutdownNow();
		}
		assertNull(FootprintStats.renderThreadResolve());
	}

	@Test
	void theRenderThreadInsideTheWindowSetsIt() {
		FootprintStats.clearRenderThreadResolve();
		FootprintStats.clientStarted(() -> {
			assertTrue(FootprintStats.inStartupWindow());
			FootprintStats.lazyResolved("holder");
		});
		assertEquals("holder during the CLIENT_STARTED handler", FootprintStats.renderThreadResolve());
		FootprintStats.lazyResolved("later");
		assertEquals("holder during the CLIENT_STARTED handler", FootprintStats.renderThreadResolve(), "the first resolution is kept");
		FootprintStats.clearRenderThreadResolve();
	}

	@Test
	void theRenderThreadAfterTheWindowLeavesItUnset() {
		FootprintStats.clearRenderThreadResolve();
		FootprintStats.clientStarted(() -> {
		});
		assertFalse(FootprintStats.inStartupWindow());
		FootprintStats.lazyResolved("holder");
		assertNull(FootprintStats.renderThreadResolve());
	}

	@Test
	void theLaunchsPreLaunchAndInitAreWindowsLaterCallsAreNot() {
		FootprintStats.resetStartupForTests();
		long pre = FootprintStats.preLaunchStart();
		FootprintStats.lazyResolved("holder");
		FootprintStats.preLaunchEnd(pre);
		assertEquals("holder during preLaunch", FootprintStats.renderThreadResolve());
		FootprintStats.clearRenderThreadResolve();
		long init = FootprintStats.initStart();
		FootprintStats.lazyResolved("holder");
		FootprintStats.initEnd(init);
		assertFalse(FootprintStats.inStartupWindow());
		assertEquals("holder during onInitializeClient", FootprintStats.renderThreadResolve());
		FootprintStats.clearRenderThreadResolve();

		// A game test calls onPreLaunch() again later: no window then.
		long again = FootprintStats.preLaunchStart();
		assertFalse(FootprintStats.inStartupWindow());
		FootprintStats.lazyResolved("holder");
		FootprintStats.preLaunchEnd(again);
		long initAgain = FootprintStats.initStart();
		FootprintStats.lazyResolved("holder");
		FootprintStats.initEnd(initAgain);
		assertNull(FootprintStats.renderThreadResolve());
	}
}
