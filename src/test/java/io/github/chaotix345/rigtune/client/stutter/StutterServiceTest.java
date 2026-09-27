package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.LogCapture;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// StutterService's own logic (docs/v0.5/SPEC.md 2S) without a running game: the controller is an unconstructed
// RealController (its constructor needs a game) holding only the settings, with the monitor off, and the Minecraft
// parameter is null (the paths tested never reach it with the monitor off). The captures are real: StutterCapture's
// rings, GC listener and sampler.
class StutterServiceTest {
	private static final long MS = 1_000_000L;

	@AfterEach
	void stopEverything() {
		StutterMonitor.Capture s = StutterMonitor.session();
		if (s != null) {
			StutterCapture.stop(s);
		}
		StutterMonitor.Capture b = StutterMonitor.benchmark();
		if (b != null) {
			StutterCapture.stop(b);
		}
	}

	// An io executor the test drains by hand, in order.
	static final class Queue implements Executor {
		final List<Runnable> tasks = new ArrayList<>();

		@Override
		public synchronized void execute(Runnable task) {
			tasks.add(task);
		}

		void runAll() {
			while (true) {
				Runnable next;
				synchronized (this) {
					if (tasks.isEmpty()) {
						return;
					}
					next = tasks.removeFirst();
				}
				next.run();
			}
		}
	}

	static StutterReport seeded() {
		return new StutterReport("2026-09-26T10:00:00Z", StutterReport.MONITOR, "26.2", "G1", 4096, 900, 812.5, 97000, 119.4, 61.2, null, null,
				new StutterReport.Spikes(9, 2, 1, 0), 1810.0, Map.of("gc", 0.44, "unknown", 0.56), Map.of(), List.of(), null, List.of(), true, true, 10);
	}

	static StutterService service(Path dir) throws ReflectiveOperationException {
		return service(dir, null);
	}

	static StutterService service(Path dir, Executor io) throws ReflectiveOperationException {
		Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object unsafe = theUnsafe.get(null);
		RealController controller = (RealController) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, RealController.class);
		Field settings = RealController.class.getDeclaredField("settings");
		settings.setAccessible(true);
		settings.set(controller, new ClientSettings());
		return io == null ? new StutterService(controller, dir) : new StutterService(controller, dir, io);
	}

	// AC2S.7 (SD-3): a session that isn't saved (too short around a benchmark run that was then cancelled) must not hide
	// the summaries already in stutter.json: the Stutter Doctor still shows the newest one.
	@Test
	void sd3AnUnsavedSessionStillShowsTheSavedSummary(@TempDir Path dir) throws ReflectiveOperationException {
		new StutterStore(dir).add(seeded());
		Queue io = new Queue();
		StutterService service = service(dir, io);
		StutterCapture.startSession();
		service.benchmarkStarted(null);
		service.benchmarkSweep(null, true);
		service.benchmarkFinished(null, false);
		io.runAll();
		assertEquals(1, new StutterStore(dir).sessions().size(), "the short session wasn't saved");
		service.view();
		io.runAll();
		assertNotNull(service.view().report(), "the saved summary is shown, not \"No sessions recorded yet\"");
		assertEquals("2026-09-26T10:00:00Z", service.view().report().startedAt());
	}

	// AC2S.8 (SD-4): Clear pressed while the saved summary's load is still queued keeps it cleared.
	@Test
	void sd4ClearWhileTheSavedLoadIsQueuedKeepsItCleared(@TempDir Path dir) throws ReflectiveOperationException {
		new StutterStore(dir).add(seeded());
		Queue io = new Queue();
		StutterService service = service(dir, io);
		service.view();
		service.clear();
		io.runAll();
		assertEquals(0, new StutterStore(dir).sessions().size(), "the file was cleared");
		assertNull(service.view().report(), "the cleared summary doesn't come back");
		assertEquals("", service.summary(), "Copy summary has nothing to copy");
	}

	// AC2S.1 (L1, review-10 R10-1): a benchmark run ends the running session; when copying its capture fails, the benchmark
	// still starts, the log says specifically that the session's summary was lost, no capture is left behind and the next
	// session starts normally.
	@Test
	void aFailingCopyLosesOnlyThatSummaryAndSaysSo(@TempDir Path dir) throws ReflectiveOperationException {
		StutterService service = service(dir);
		StutterCapture.startSession();
		Function<StutterMonitor.Capture, StutterCapture.Copy> copier = StutterCapture.copier;
		StutterCapture.copier = c -> {
			throw new IllegalStateException("copy failed (test)");
		};
		try (LogCapture log = new LogCapture()) {
			assertDoesNotThrow(() -> service.benchmarkStarted(null));
			assertEquals(1, log.lines().stream().filter(l -> l.contains("the running session's summary was lost")).count(), String.join(" | ", log.lines()));
		} finally {
			StutterCapture.copier = copier;
		}
		assertNull(StutterMonitor.session());
		assertNull(StutterMonitor.rings());
		assertFalse(StutterMonitor.active());
		assertFalse(StutterCapture.GC.active(), "the GC listener went with the capture");

		StutterMonitor.Capture next = StutterCapture.startSession();
		assertSame(next, StutterMonitor.session(), "the next session starts normally");
		assertTrue(StutterCapture.GC.active());
	}

	// docs/v0.5/SPEC.md 2B RW-15 (AC2B.9, the capture side), called as BenchmarkController does: a step whose settle timed
	// out gets benchmarkStepExcluded(true/false) instead of benchmarkSweep(true/false), and its frames stay out of the
	// benchmark's capture; the seam never starts or resumes the capture.
	@Test
	void anExcludedStepRecordsNoFrames(@TempDir Path dir) throws ReflectiveOperationException {
		Queue io = new Queue();
		StutterService service = service(dir, io);
		service.benchmarkStarted(null);
		service.benchmarkSweep(null, true);
		frames(100);
		service.benchmarkSweep(null, false);
		service.benchmarkStepExcluded(true);
		frames(300);
		service.benchmarkStepExcluded(false);
		assertTrue(StutterMonitor.benchmark().paused(), "false at the step's end doesn't resume the capture");
		frames(300);
		service.benchmarkSweep(null, true);
		frames(50);
		service.benchmarkSweep(null, false);
		service.benchmarkFinished(null, true);
		io.runAll();
		StutterReport report = service.lastBenchmark();
		assertNotNull(report);
		assertEquals(99 + 49, report.frames(), "the two recorded steps (each one's first frame spans the pause); the left-out step and the gap left out");

		// The first step left out: no capture yet, and the seam doesn't start one; the run ending inside such a step.
		service.benchmarkStarted(null);
		service.benchmarkStepExcluded(true);
		assertNull(StutterMonitor.benchmark(), "the seam doesn't start the capture");
		frames(100);
		service.benchmarkStepExcluded(false);
		service.benchmarkSweep(null, true);
		frames(40);
		service.benchmarkSweep(null, false);
		service.benchmarkStepExcluded(true);
		frames(100);
		service.benchmarkStepExcluded(false);
		service.benchmarkFinished(null, true);
		io.runAll();
		assertEquals(39, service.lastBenchmark().frames(), "only the settled step's sweep");
	}

	// A 1 ms sampler window with `cores` of DH world generation, all of it after the last call (it ends 1 ms from now).
	private static void worldGen(StutterRings rings, long cores) {
		long until = System.nanoTime() + MS;
		while (System.nanoTime() < until) {
			Thread.onSpinWait();
		}
		long[] sample = new long[StutterRings.SAMPLE_STRIDE];
		sample[StutterRings.S_TIME] = System.nanoTime();
		sample[StutterRings.S_WINDOW] = MS;
		sample[StutterRings.S_DH_WORLD_GEN] = cores * MS;
		rings.sample(sample);
	}

	// L1's guard in Clear: a failing copy doesn't reach the click handler, and Clear still starts a fresh session.
	@Test
	void clearSurvivesAFailingCopy(@TempDir Path dir) throws ReflectiveOperationException {
		Queue io = new Queue();
		StutterService service = service(dir, io);
		StutterMonitor.Capture first = StutterCapture.startSession();
		Function<StutterMonitor.Capture, StutterCapture.Copy> copier = StutterCapture.copier;
		StutterCapture.copier = c -> {
			throw new IllegalStateException("copy failed (test)");
		};
		try {
			assertDoesNotThrow(service::clear);
		} finally {
			StutterCapture.copier = copier;
		}
		assertNotNull(StutterMonitor.session());
		assertTrue(StutterMonitor.session() != first, "a fresh session");
		io.runAll();
	}

	private static void frames(int n) {
		for (int i = 0; i < n; i++) {
			StutterMonitor.onFrame(5_000_000L);
		}
	}

	// AC2S.14 (for 2B's RW-6): a finished benchmark run reports the CPU DH's world generation used during its sweeps; a
	// cancelled one reports nothing.
	@Test
	void aFinishedBenchmarkReportsItsDhWorldGenCpu(@TempDir Path dir) throws ReflectiveOperationException {
		Queue io = new Queue();
		StutterService service = service(dir, io);
		long started = System.nanoTime();
		service.benchmarkStarted(null);
		service.benchmarkSweep(null, true);
		StutterRings rings = StutterMonitor.rings();
		assertNotNull(rings, "the sweep started the benchmark's capture");
		worldGen(rings, 2);
		service.benchmarkSweep(null, false);
		worldGen(rings, 6);
		service.benchmarkStepExcluded(true);
		worldGen(rings, 8);
		service.benchmarkStepExcluded(false);
		service.benchmarkSweep(null, true);
		worldGen(rings, 1);
		service.benchmarkSweep(null, false);
		service.benchmarkFinished(null, true);
		Double cores = service.lastBenchmarkDhWorldGenCores();
		assertNotNull(cores);
		// The capture's own sampler adds a 250 ms window of its own after about half a second; a slower run can't be exact.
		org.junit.jupiter.api.Assumptions.assumeTrue(System.nanoTime() - started < 400 * MS, "the run took under 400 ms");
		assertEquals(1.5, cores, 1e-9, "the two sweeps' windows (2 and 1 cores), not the gap's (6) or the left-out step's (8)");

		service.benchmarkStarted(null);
		service.benchmarkSweep(null, true);
		service.benchmarkFinished(null, false);
		assertNull(service.lastBenchmarkDhWorldGenCores(), "a cancelled run");
		io.runAll();
		assertNull(StutterMonitor.benchmark());
		assertEquals(0, StutterMonitor.retainedBytes());
	}
}
