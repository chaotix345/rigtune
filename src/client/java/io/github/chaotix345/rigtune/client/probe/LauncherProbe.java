package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.launcher.LauncherDetector;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;

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
// LauncherSignals, runs on the worker pool (a game dir on a network share can stall) with a timeout, and never fails:
// anything unexpected is Unknown.
public final class LauncherProbe {
	public static final long TIMEOUT_MS = 3000;

	private LauncherProbe() {
	}

	public static CompletableFuture<LauncherInfo> probeAsync(Path gameDir) {
		return probeAsync(() -> LauncherDetector.detect(signals(System::getProperty, System::getenv, gameDir)), Probes.EXECUTOR, TIMEOUT_MS);
	}

	static CompletableFuture<LauncherInfo> probeAsync(Supplier<LauncherInfo> detect, Executor executor, long timeoutMs) {
		CompletableFuture<LauncherInfo> future;
		try {
			future = CompletableFuture.supplyAsync(detect, executor);
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(LauncherInfo.UNKNOWN);
		}
		return future.completeOnTimeout(LauncherInfo.UNKNOWN, timeoutMs, TimeUnit.MILLISECONDS)
				.exceptionally(t -> LauncherInfo.UNKNOWN)
				.thenApply(info -> info == null ? LauncherInfo.UNKNOWN : info);
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
