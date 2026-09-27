package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.launcher.InstanceEvidence;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

// Which launcher started the game (docs/v0.3/SPEC.md item 5). Reads only the property and environment names in
// LauncherSignals, runs on the worker pool (a game dir on a network share can stall), and never fails: anything
// unexpected is Unknown. The signals don't change while the game runs, so the detection runs once per session, like
// HardwareProbe's slow part; each caller waits for it at most TIMEOUT_MS, and a detection that finishes later is still
// used by the next rescan.
// v0.5 (docs/v0.5/SPEC.md 4a): the same session also lists <mods>/.index/ (InstanceEvidence), on its own, so the
// mod-files policy can use that evidence before detection answers. A probe answers once both have; at the cap it answers
// NOT_YET (PENDING for the policy, never the Unknown that would make it RIGTUNE). When a probe hit the cap, the answer
// that comes later goes to the late-answer listener, once per detection (onLateAnswer), so the report can follow it.
public final class LauncherProbe {
	public static final long TIMEOUT_MS = 3000;
	// Reads as Unknown everywhere (equals LauncherInfo.UNKNOWN); only its identity says "not answered yet".
	public static final LauncherInfo NOT_YET = new LauncherInfo(Launcher.UNKNOWN, null);
	private static @Nullable CompletableFuture<LauncherInfo> detection;
	private static @Nullable CompletableFuture<InstanceEvidence> evidence;
	private static @Nullable CompletableFuture<LauncherInfo> answered;
	// This detection: a probe answered NOT_YET; the listener was attached to its answer.
	private static boolean timedOut;
	private static boolean lateWatched;
	private static @Nullable Consumer<LauncherInfo> lateListener;

	private LauncherProbe() {
	}

	public static synchronized CompletableFuture<LauncherInfo> probeAsync(Path gameDir) {
		if (detection == null) {
			// The mods folder is resolved on the worker too (InstanceDirs touches the file system).
			begin(start(() -> LauncherDetector.detect(signals(System::getProperty, System::getenv, gameDir)), Probes.EXECUTOR),
					startListing(() -> InstanceEvidence.list(InstanceDirs.modsDir(gameDir)), Probes.EXECUTOR));
		}
		return probe(TIMEOUT_MS);
	}

	/** Game tests only: the next probe detects again (after the test changed a signal). */
	public static synchronized void reset() {
		detection = null;
		evidence = null;
		answered = null;
		timedOut = false;
		lateWatched = false;
	}

	// What the detection answered, or null while it hasn't (or before any probe): the mod-files policy's launcher.
	public static synchronized @Nullable LauncherInfo answer() {
		return detection == null ? null : detection.getNow(null);
	}

	// What the .index/ listing found, or null while it hasn't answered (or before any probe).
	public static synchronized @Nullable InstanceEvidence evidence() {
		return evidence == null ? null : evidence.getNow(null);
	}

	// The late-answer listener (the latest one set): once a probe of this detection has answered NOT_YET, it gets the
	// detection's answer when both it and the listing are in (at once if they already are), once per detection, never
	// after a reset(). A detection that answers within the cap never calls it.
	public static void onLateAnswer(@Nullable Consumer<LauncherInfo> listener) {
		synchronized (LauncherProbe.class) {
			lateListener = listener;
		}
		watchLate();
	}

	private static void watchLate() {
		CompletableFuture<LauncherInfo> watched;
		Consumer<LauncherInfo> listener;
		synchronized (LauncherProbe.class) {
			if (answered == null || !timedOut || lateWatched || lateListener == null) {
				return;
			}
			lateWatched = true;
			watched = answered;
			listener = lateListener;
		}
		watched.thenAccept(info -> {
			if (current(watched)) {
				listener.accept(info);
			}
		});
	}

	private static void timedOut(CompletableFuture<LauncherInfo> session) {
		synchronized (LauncherProbe.class) {
			if (answered != session) {
				return;
			}
			timedOut = true;
		}
		watchLate();
	}

	private static synchronized boolean current(CompletableFuture<LauncherInfo> watched) {
		return answered == watched;
	}

	static synchronized @Nullable CompletableFuture<LauncherInfo> detection() {
		return detection;
	}

	// A new session from these two (probeAsync, and the tests' own futures).
	static synchronized void begin(CompletableFuture<LauncherInfo> detected, CompletableFuture<InstanceEvidence> listed) {
		detection = detected;
		evidence = listed;
		answered = detected.thenCombine(listed, (info, ignored) -> info);
		timedOut = false;
		lateWatched = false;
	}

	static CompletableFuture<LauncherInfo> probe(long timeoutMs) {
		CompletableFuture<LauncherInfo> session;
		synchronized (LauncherProbe.class) {
			session = answered;
		}
		if (session == null) {
			return CompletableFuture.completedFuture(NOT_YET);
		}
		return withTimeout(session, timeoutMs).thenApply(info -> {
			if (info == NOT_YET) {
				timedOut(session);
			}
			return info;
		});
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

	static CompletableFuture<InstanceEvidence> startListing(Supplier<InstanceEvidence> list, Executor executor) {
		try {
			return CompletableFuture.supplyAsync(list, executor)
					.exceptionally(t -> InstanceEvidence.NONE)
					.thenApply(found -> found == null ? InstanceEvidence.NONE : found);
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(InstanceEvidence.NONE);
		}
	}

	// A copy, so one caller's timeout never completes the shared detection.
	static CompletableFuture<LauncherInfo> withTimeout(CompletableFuture<LauncherInfo> detection, long timeoutMs) {
		return detection.copy().completeOnTimeout(NOT_YET, timeoutMs, TimeUnit.MILLISECONDS);
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
