package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.stutter.StutterMonitor;
import io.github.chaotix345.rigtune.client.stutter.StutterMonitorAccess;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
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
// every call first, so it shows as hundreds of KB (a `new long[1]` showed 129-382 KB). The JVM itself allocates a little
// on this thread while it compiles the loop (72 B in a bare JVM, 280-960 B in this test JVM, on Java 25; about 1 KB of
// bookkeeping once on CI), whatever the hook does: the cold count forgives JIT_NOISE_BYTES of that, the same 64 KiB bound
// as the other allocation tests (StutterMonitorTest, FrameRingAllocationTest). At least one hot run must allocate nothing.
//
// v0.5 (docs/v0.5/design/ws-ci.md): the ns per call above is a gross-regression backstop (min(ceiling, 4 x max observed)).
// The monitor-on cases are also timed as RATIO_BLOCKS interleaved triples: the frames, twice as many frames, and a fixed
// reference workload (pure Java; for the phase case plus the same 8 System.nanoTime() reads, so the runner's clock cost
// cancels). frameHookOnVsReference and frameHookOnPhasesVsReference are the median ratios, gated like FootprintGameTest's
// tickHookOnVsReference, with the same self-check: the doubled work's ratio must exceed the limit on every run.
class FrameHookBudgetTest {
	private static final int CALLS = 10_000_000;
	private static final int RUNS = 5;
	static final long JIT_NOISE_BYTES = 64 * 1024;
	private static final int RATIO_BLOCKS = 48;
	private static final int WARM_UP_ROUNDS = 300;
	private static final int WARM_UP_CALLS = 1_000;
	private static final long JIT_QUIET_MS = 100;
	private static final long JIT_QUIET_CAP_MS = 10_000;
	private static final long[] REFERENCE = new long[256];
	private static long referenceState = 0x9E3779B97F4A7C15L;
	private static long referenceSink;

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
		ratioGate("monitor on", "frameHookOnVsReference", FrameHookBudgetTest::monitoredFrames, FrameHookBudgetTest::reference, 200_000);
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
		ratioGate("monitor on with the phase timers", "frameHookOnPhasesVsReference", FrameHookBudgetTest::phasedFrames,
				FrameHookBudgetTest::clockReference, 20_000);
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

	// See the class comment. Prints the numbers; enforces the ratio's budget (when the budgets file has one) and the self-check.
	private static void ratioGate(String label, String key, IntConsumer hook, IntConsumer reference, int calls) throws IOException {
		FootprintBudgets budgets = FootprintBudgets.load();
		CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
		for (int round = 0; round < WARM_UP_ROUNDS; round++) {
			hook.accept(WARM_UP_CALLS);
			reference.accept(WARM_UP_CALLS);
		}
		long waitStart = System.nanoTime();
		long quietSince = waitStart;
		long compiled = jit.getTotalCompilationTime();
		while (System.nanoTime() - quietSince < JIT_QUIET_MS * 1_000_000L && System.nanoTime() - waitStart < JIT_QUIET_CAP_MS * 1_000_000L) {
			hook.accept(WARM_UP_CALLS);
			reference.accept(WARM_UP_CALLS);
			long now = jit.getTotalCompilationTime();
			if (now != compiled) {
				compiled = now;
				quietSince = System.nanoTime();
			}
		}
		long quietWaitMs = (System.nanoTime() - waitStart) / 1_000_000;
		double[] once = new double[RATIO_BLOCKS];
		double[] twice = new double[RATIO_BLOCKS];
		double[] ref = new double[RATIO_BLOCKS];
		long jitBefore = jit.getTotalCompilationTime();
		for (int block = 0; block < RATIO_BLOCKS; block++) {
			long t0 = System.nanoTime();
			hook.accept(calls);
			long t1 = System.nanoTime();
			hook.accept(2 * calls);
			long t2 = System.nanoTime();
			reference.accept(calls);
			long t3 = System.nanoTime();
			once[block] = (t1 - t0) / (double) calls;
			twice[block] = (t2 - t1) / (double) calls;
			ref[block] = (t3 - t2) / (double) calls;
		}
		double[] ratio = new double[RATIO_BLOCKS];
		double[] twinRatio = new double[RATIO_BLOCKS];
		for (int block = 0; block < RATIO_BLOCKS; block++) {
			ratio[block] = once[block] / ref[block];
			twinRatio[block] = twice[block] / ref[block];
		}
		double vsReference = median(ratio);
		double twin = median(twinRatio);
		System.out.printf(Locale.ROOT, "FrameHookBudgetTest: %s: %s %.3f (twice the work %.3f); median %.3f ns/call, reference %.3f ns, "
				+ "%d blocks of %d calls, JIT quiet after %d ms, %d ms compiling during the blocks%n", label, key, vsReference, twin, median(once),
				median(ref), RATIO_BLOCKS, calls, quietWaitMs, jit.getTotalCompilationTime() - jitBefore);
		budgets.enforce(budgets.check(Map.of(key, vsReference)), System.out::println);
		FootprintBudgets.Budget budget = budgets.budgets().get(key);
		if (budget != null && twin <= budget.limit() && budgets.mode() == FootprintBudgets.Mode.FAIL) {
			throw new AssertionError(String.format(Locale.ROOT, "footprint gate %s can't see a 2x regression on this runner: twice the work measured %.3f, "
					+ "not above the limit %s", key, twin, budget.limit()));
		}
	}

	private static double median(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int mid = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
	}

	// A fixed pure-Java workload (loads, stores, a branch) per call, the runner's yardstick (as FootprintGameTest's).
	private static void reference(int calls) {
		long x = referenceState;
		long sum = 0;
		for (int i = 0; i < calls; i++) {
			for (int k = 0; k < 6; k++) {
				x ^= x << 13;
				x ^= x >>> 7;
				x ^= x << 17;
				int slot = (int) (x & 255);
				if ((x & 1) == 0) {
					REFERENCE[slot] += x;
				} else {
					sum += REFERENCE[slot];
				}
			}
			x += i;
		}
		referenceState = x;
		referenceSink += sum;
	}

	// The same plus the 8 System.nanoTime() reads a phase-timed frame makes.
	private static void clockReference(int calls) {
		long clock = 0;
		for (int i = 0; i < calls; i++) {
			for (int read = 0; read < 8; read++) {
				clock += System.nanoTime();
			}
		}
		referenceSink += clock;
		reference(calls);
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
