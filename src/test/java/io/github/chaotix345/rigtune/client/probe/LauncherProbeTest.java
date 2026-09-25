package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

// C-M1: the probe runs off the render thread with a timeout, and any failure is Unknown; only the named signals are read.
class LauncherProbeTest {
	private static LauncherInfo await(java.util.concurrent.CompletableFuture<LauncherInfo> future) throws Exception {
		return future.get(5, TimeUnit.SECONDS);
	}

	@Test
	void theDetectorsResult() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			LauncherInfo modrinth = LauncherInfo.of(Launcher.MODRINTH_APP);
			assertEquals(modrinth, await(LauncherProbe.probeAsync(() -> modrinth, executor, 1000)));
			assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.probeAsync(() -> null, executor, 1000)));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void aStalledDetectorTimesOutAsUnknown() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch never = new CountDownLatch(1);
		try {
			LauncherInfo info = await(LauncherProbe.probeAsync(() -> {
				try {
					never.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return LauncherInfo.of(Launcher.PRISM);
			}, executor, 100));
			assertEquals(LauncherInfo.UNKNOWN, info);
		} finally {
			never.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void anyFailureIsUnknown() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.probeAsync(() -> {
				throw new IllegalStateException("boom");
			}, executor, 1000)));
			assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.probeAsync(() -> {
				throw new StackOverflowError();
			}, executor, 1000)));
		} finally {
			executor.shutdownNow();
		}
		assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.probeAsync(() -> LauncherInfo.of(Launcher.PRISM), command -> {
			throw new RejectedExecutionException("shut down");
		}, 1000)));
	}

	@Test
	void onlyTheNamedPropertiesAndVariablesAreRead() {
		List<String> askedProperties = new ArrayList<>();
		List<String> askedEnv = new ArrayList<>();
		Path gameDir = Path.of("game");
		LauncherSignals signals = LauncherProbe.signals(name -> {
			askedProperties.add(name);
			return name.equals(LauncherSignals.BRAND) ? "theseus" : null;
		}, name -> {
			askedEnv.add(name);
			return name.equals(LauncherSignals.INST_NAME) ? "Pack" : null;
		}, gameDir);
		assertEquals(LauncherSignals.PROPERTIES, askedProperties);
		assertEquals(LauncherSignals.ENV, askedEnv);
		assertEquals(Map.of(LauncherSignals.BRAND, "theseus"), signals.properties());
		assertEquals(Map.of(LauncherSignals.INST_NAME, "Pack"), signals.env());
		assertEquals(gameDir, signals.gameDir());
	}
}
