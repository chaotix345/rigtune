package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.stutter.StutterMonitor;
import io.github.chaotix345.rigtune.client.stutter.StutterMonitorAccess;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// docs/v0.4/SPEC.md 10 (AC10.1; F-L1): the per-frame hook (DebugScreenOverlayMixin -> FrameTimes.onFrame +
// StutterMonitor.onFrame) with the session monitor off and on, and with the monitor on plus the Stutter Doctor's phase
// timers (MinecraftFrameMixin), against tools/footprint-budgets.json: ns per call over 10 M hot calls, and bytes
// allocated. Allocation is counted over the first 10 M calls from cold as well as over the hot runs: C2's escape
// analysis removes an allocation that doesn't escape (a `new long[1]`), but the interpreter and C1 still make it on
// every call first, so it shows as hundreds of KB. The JVM itself allocates a few bytes on this thread while it compiles
// the loop (72 B in a bare JVM, 280 B in this test JVM, on Java 25), whatever the hook does: the cold count forgives
// JIT_NOISE_BYTES of that. At least one hot run must allocate exactly nothing.
class FrameHookBudgetTest {
	private static final int CALLS = 10_000_000;
	private static final int RUNS = 5;
	static final long JIT_NOISE_BYTES = 4096;

	@AfterEach
	void monitorOff() {
		StutterMonitorAccess.stopAll();
	}

	@Test
	void frameHookWithTheMonitorOff() throws IOException {
		assertFalse(StutterMonitor.active(), "no capture is on");
		measure("monitor off", "frameHookNsPerCallOff", "frameHookAllocBytesOff", FrameHookBudgetTest::frames);
	}

	// What DebugScreenOverlayMixin runs per frame while the session monitor records.
	@Test
	void frameHookWithTheMonitorOn() throws IOException {
		StutterMonitorAccess.startSession();
		assertTrue(StutterMonitor.active() && StutterMonitor.session() != null, "the session capture is on");
		measure("monitor on", "frameHookNsPerCallOn", "frameHookAllocBytesOn", FrameHookBudgetTest::monitoredFrames);
		assertTrue(StutterMonitor.session().snapshot().frames() > CALLS, "the capture recorded the frames");
	}

	// The same plus every phase-timer call of a frame with one tick, a chunk load and the frame-rate limiter (a capped
	// frame rate): the most calls a frame without extra ticks makes (MinecraftFrameMixin; S-M1). 8 System.nanoTime() reads
	// per frame, so this case has its own ceiling (400 ns, coordinator 2026-09-26; SPEC 10's 200 ns covers the two onFrame
	// calls). The uncapped frame (no limiter pair, 6 reads) is logged as a diagnostic; its allocation is gated too.
	@Test
	void frameHookWithTheMonitorOnAndThePhaseTimers() throws IOException {
		StutterMonitorAccess.startSession();
		assertTrue(StutterMonitor.active(), "the session capture is on");
		measure("monitor on with the phase timers", "frameHookNsPerCallOnPhases", "frameHookAllocBytesOnPhases", FrameHookBudgetTest::phasedFrames);
		assertTrue(StutterMonitor.phaseTiming(), "every phase timer ran");
		measure("monitor on with the phase timers, uncapped (diagnostic)", null, "frameHookAllocBytesOnPhases", FrameHookBudgetTest::uncappedFrames);
	}

	private static void measure(String label, @Nullable String nsKey, String bytesKey, IntConsumer hook) throws IOException {
		FootprintBudgets budgets = FootprintBudgets.load();
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(mx.isThreadAllocatedMemorySupported() && mx.isThreadAllocatedMemoryEnabled(), "no per-thread allocation counter");
		assertFalse(FrameTimes.recording(), "no benchmark is recording");
		// One call first: class initialisation (FrameTimes' recorder buffer) and linking the call sites allocate once.
		hook.accept(1);

		long before = mx.getCurrentThreadAllocatedBytes();
		hook.accept(CALLS);
		long cold = mx.getCurrentThreadAllocatedBytes() - before;

		long best = Long.MAX_VALUE;
		long hot = Long.MAX_VALUE;
		// Best of RUNS: on a busy 4-vCPU runner the loop can sit in C1 code for a while (5 ns/call once, 0.3-0.6 ns in C2).
		// The fewest bytes of any run: a recompile or deopt inside one run allocates a little on this thread by itself,
		// while an allocation in the hook shows in every run.
		for (int run = 0; run < RUNS; run++) {
			long allocatedBefore = mx.getCurrentThreadAllocatedBytes();
			long start = System.nanoTime();
			hook.accept(CALLS);
			best = Math.min(best, System.nanoTime() - start);
			hot = Math.min(hot, mx.getCurrentThreadAllocatedBytes() - allocatedBefore);
		}
		double nsPerCall = (double) best / CALLS;
		long allocated = Math.max(hot, Math.max(0, cold - JIT_NOISE_BYTES));
		System.out.printf(Locale.ROOT, "FrameHookBudgetTest: %s: %.3f ns/call (best of %d x %d hot calls); allocated %d B over %d calls "
				+ "from cold (%d B forgiven as JIT noise), %d B over the hot calls (fewest of any run)%n", label, nsPerCall, RUNS, CALLS, cold, CALLS,
				JIT_NOISE_BYTES, hot);
		budgets.enforce(budgets.check(nsKey == null ? Map.of(bytesKey, allocated) : Map.of(nsKey, nsPerCall, bytesKey, allocated)), System.out::println);
	}

	private static void frames(int calls) {
		for (int i = 0; i < calls; i++) {
			FrameTimes.onFrame(8_333_333L + (i & 1023));
		}
	}

	// A 60 ms frame every 1024 frames takes the capture's spike-candidate path too.
	private static long duration(int i) {
		return (i & 1023) == 0 ? 60_000_000L : 8_333_333L + (i & 1023);
	}

	private static void monitoredFrames(int calls) {
		for (int i = 0; i < calls; i++) {
			long d = duration(i);
			FrameTimes.onFrame(d);
			StutterMonitor.onFrame(d);
		}
	}

	private static void phasedFrames(int calls) {
		for (int i = 0; i < calls; i++) {
			StutterMonitor.packetsStart();
			StutterMonitor.chunkLoaded();
			StutterMonitor.packetsEnd();
			StutterMonitor.tickStart();
			StutterMonitor.tickEnd();
			StutterMonitor.renderStart();
			StutterMonitor.limiterStart();
			StutterMonitor.limiterEnd();
			long d = duration(i);
			FrameTimes.onFrame(d);
			StutterMonitor.onFrame(d);
		}
	}

	private static void uncappedFrames(int calls) {
		for (int i = 0; i < calls; i++) {
			StutterMonitor.packetsStart();
			StutterMonitor.chunkLoaded();
			StutterMonitor.packetsEnd();
			StutterMonitor.tickStart();
			StutterMonitor.tickEnd();
			StutterMonitor.renderStart();
			long d = duration(i);
			FrameTimes.onFrame(d);
			StutterMonitor.onFrame(d);
		}
	}
}
