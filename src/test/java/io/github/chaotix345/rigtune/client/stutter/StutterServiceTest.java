package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.LogCapture;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
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

	static StutterService service(Path dir) throws ReflectiveOperationException {
		Field theUnsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		Object unsafe = theUnsafe.get(null);
		RealController controller = (RealController) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, RealController.class);
		Field settings = RealController.class.getDeclaredField("settings");
		settings.setAccessible(true);
		settings.set(controller, new ClientSettings());
		return new StutterService(controller, dir);
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

	// AC2S.14 (for 2B's RW-6): a finished benchmark run reports the CPU DH's world generation used during its sweeps; a
	// cancelled one reports nothing.
	@Test
	void aFinishedBenchmarkReportsItsDhWorldGenCpu(@TempDir Path dir) throws ReflectiveOperationException {
		StutterService service = service(dir);
		service.benchmarkStarted(null);
		service.benchmarkSweep(null, true);
		StutterRings rings = StutterMonitor.rings();
		assertNotNull(rings, "the sweep started the benchmark's capture");
		long[] sample = new long[StutterRings.SAMPLE_STRIDE];
		sample[StutterRings.S_TIME] = System.nanoTime();
		sample[StutterRings.S_WINDOW] = 250 * MS;
		sample[StutterRings.S_DH_WORLD_GEN] = 2 * 250 * MS;
		rings.sample(sample);
		service.benchmarkFinished(null, true);
		Double cores = service.lastBenchmarkDhWorldGenCores();
		assertNotNull(cores);
		assertTrue(cores > 0, "world generation ran during the sweep: " + cores);

		service.benchmarkStarted(null);
		service.benchmarkSweep(null, true);
		service.benchmarkFinished(null, false);
		assertNull(service.lastBenchmarkDhWorldGenCores(), "a cancelled run");
		assertNull(StutterMonitor.benchmark());
		assertEquals(0, StutterMonitor.retainedBytes());
	}
}
