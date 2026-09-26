package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// docs/v0.4/SPEC.md 10 (AC10.1): the per-frame hook (DebugScreenOverlayMixin -> FrameTimes.onFrame, and
// StutterMonitor.onFrame once item 5 lands) against tools/footprint-budgets.json: ns per call over 10 M hot calls, and
// bytes allocated. Allocation is counted over the first 10 M calls from cold as well as over the hot runs: C2's escape
// analysis removes an allocation that doesn't escape (a `new long[1]`), but the interpreter and C1 still make it on
// every call first, so it shows as hundreds of KB. The JVM itself allocates a few bytes on this thread while it compiles
// the loop (72 B in a bare JVM, 280 B in this test JVM, on Java 25), whatever the hook does: the cold count forgives
// JIT_NOISE_BYTES of that. The hot runs must allocate exactly nothing.
class FrameHookBudgetTest {
	private static final int CALLS = 10_000_000;
	static final long JIT_NOISE_BYTES = 4096;

	@Test
	void frameHookWithTheMonitorOff() throws IOException {
		FootprintBudgets budgets = FootprintBudgets.load();
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		assumeTrue(mx.isThreadAllocatedMemorySupported() && mx.isThreadAllocatedMemoryEnabled(), "no per-thread allocation counter");
		assertFalse(FrameTimes.recording(), "no benchmark is recording");
		// One call first: FrameTimes' class initialisation (its recorder's buffer) and linking the call site allocate once.
		frames(1);

		long before = mx.getCurrentThreadAllocatedBytes();
		frames(CALLS);
		long cold = mx.getCurrentThreadAllocatedBytes() - before;

		long best = Long.MAX_VALUE;
		before = mx.getCurrentThreadAllocatedBytes();
		for (int run = 0; run < 3; run++) {
			long start = System.nanoTime();
			frames(CALLS);
			best = Math.min(best, System.nanoTime() - start);
		}
		long hot = mx.getCurrentThreadAllocatedBytes() - before;
		double nsPerCall = (double) best / CALLS;
		long allocated = Math.max(hot, Math.max(0, cold - JIT_NOISE_BYTES));
		System.out.printf(Locale.ROOT, "FrameHookBudgetTest: monitor off: %.3f ns/call (best of 3 x %d hot calls); allocated %d B over %d calls "
				+ "from cold (%d B forgiven as JIT noise), %d B over the hot calls%n", nsPerCall, CALLS, cold, CALLS, JIT_NOISE_BYTES, hot);
		budgets.enforce(budgets.check(Map.of("frameHookNsPerCallOff", nsPerCall, "frameHookAllocBytesOff", allocated)), System.out::println);
	}

	private static void frames(int calls) {
		for (int i = 0; i < calls; i++) {
			FrameTimes.onFrame(8_333_333L + (i & 1023));
		}
	}
}
