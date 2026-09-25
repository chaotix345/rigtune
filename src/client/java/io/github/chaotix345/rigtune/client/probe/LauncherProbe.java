package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.launcher.LauncherDetector;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

// Which launcher started the game (docs/v0.3/SPEC.md item 5). Reads only the property and environment names in
// LauncherSignals, runs on the worker pool (a game dir on a network share can stall), and never fails: anything
// unexpected is Unknown. The signals don't change while the game runs, so the detection runs once per session, like
// HardwareProbe's slow part; each caller waits for it at most TIMEOUT_MS, and a detection that finishes later is still
// used by the next rescan.
public final class LauncherProbe {
	public static final long TIMEOUT_MS = 3000;
	private static @Nullable CompletableFuture<LauncherInfo> detection;

	private LauncherProbe() {
	}

	public static synchronized CompletableFuture<LauncherInfo> probeAsync(Path gameDir) {
		if (detection == null) {
			detection = start(() -> LauncherDetector.detect(signals(System::getProperty, System::getenv, gameDir)), Probes.EXECUTOR);
		}
		return withTimeout(detection, TIMEOUT_MS);
	}

	/** Game tests only: the next probe detects again (after the test changed a signal). */
	public static synchronized void reset() {
		detection = null;
	}

	static synchronized @Nullable CompletableFuture<LauncherInfo> detection() {
		return detection;
	}

	static CompletableFuture<LauncherInfo> start(Supplier<LauncherInfo> detect, Executor executor) {
		try {
			return CompletableFuture.supplyAsync(detect, executor)
					.exceptionally(t -> LauncherInfo.UNKNOWN)
					.thenApply(info -> info == null ? LauncherInfo.UNKNOWN : info);
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(LauncherInfo.UNKNOWN);
		}
	}

	// A copy, so one caller's timeout never completes the shared detection.
	static CompletableFuture<LauncherInfo> withTimeout(CompletableFuture<LauncherInfo> detection, long timeoutMs) {
		return detection.copy().completeOnTimeout(LauncherInfo.UNKNOWN, timeoutMs, TimeUnit.MILLISECONDS);
	}

	static CompletableFuture<LauncherInfo> probeAsync(Supplier<LauncherInfo> detect, Executor executor, long timeoutMs) {
		return withTimeout(start(detect, executor), timeoutMs);
	}

	static LauncherSignals signals(Function<String, String> property, Function<String, String> env, Path gameDir) {
		return new LauncherSignals(read(LauncherSignals.PROPERTIES, property), read(LauncherSignals.ENV, env), gameDir);
	}

	private static Map<String, String> read(List<String> names, Function<String, String> source) {
		Map<String, String> values = new HashMap<>();
		for (String name : names) {
			String value = source.apply(name);
			if (value != null) {
				values.put(name, value);
			}
		}
		return values;
	}
}
