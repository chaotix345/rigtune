package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #42: rebuilds asked for while a benchmark has its knobs applied wait, and one runs after its restore.
class RunRebuildsTest {
	@Test
	void aRebuildDuringARunWaitsForItsRestore() {
		AtomicBoolean running = new AtomicBoolean(true);
		RunRebuilds rebuilds = new RunRebuilds(running::get);
		assertTrue(rebuilds.defer());
		assertTrue(rebuilds.defer());
		running.set(false);
		assertTrue(rebuilds.release(), "one rebuild after the restore");
		assertFalse(rebuilds.release(), "only once");
	}

	@Test
	void withoutARunNothingWaits() {
		RunRebuilds rebuilds = new RunRebuilds(() -> false);
		assertFalse(rebuilds.defer());
		assertFalse(rebuilds.release(), "a run that no rebuild waited for asks for none");
	}

	@AfterEach
	void noHook() throws ReflectiveOperationException {
		RunRebuilds.onRestored(null);
		pendingWorld(null);
	}

	// BenchmarkController.finish() calls restored() after the restore: it runs RealController's hook, and a hook that
	// throws can't break the rest of finish().
	@Test
	void restoredRunsTheHookAndSwallowsItsFailure() {
		AtomicInteger rebuilt = new AtomicInteger();
		RunRebuilds rebuilds = new RunRebuilds(() -> true);
		rebuilds.defer();
		RunRebuilds.onRestored(() -> {
			if (rebuilds.release()) {
				rebuilt.incrementAndGet();
			}
		});
		RunRebuilds.restored();
		RunRebuilds.restored();
		assertEquals(1, rebuilt.get());
		RunRebuilds.onRestored(() -> {
			throw new IllegalStateException("boom");
		});
		assertDoesNotThrow(RunRebuilds::restored);
		RunRebuilds.onRestored(null);
		assertDoesNotThrow(RunRebuilds::restored);
	}

	// PR #60 review: a benchmark world that is still loading changes no setting and can end without finish() (it fails to
	// load, begin() refuses or throws, or cancel()), so it must not hold rebuilds: RealController holds on measuring().
	@Test
	void aLoadingBenchmarkWorldDoesntHoldRebuilds() throws ReflectiveOperationException {
		pendingWorld(new BenchmarkRequest(BenchmarkRequest.Mode.TUNE, BenchmarkRequest.Scene.BENCHMARK_WORLD, null));
		assertTrue(BenchmarkController.running(), "the menu still counts it as running");
		assertFalse(BenchmarkController.measuring());
		assertFalse(new RunRebuilds(BenchmarkController::measuring).defer());
		BenchmarkController.cancel();
		assertFalse(BenchmarkController.running());
	}

	private static void pendingWorld(BenchmarkRequest request) throws ReflectiveOperationException {
		Field field = BenchmarkController.class.getDeclaredField("pendingWorld");
		field.setAccessible(true);
		Object pending = null;
		if (request != null) {
			Class<?> type = field.getType();
			Constructor<?> make = type.getDeclaredConstructor(BenchmarkRequest.class, BenchmarkController.Config.class);
			make.setAccessible(true);
			pending = make.newInstance(request, BenchmarkController.Config.DEFAULT);
		}
		field.set(null, pending);
	}
}
