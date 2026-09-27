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
// v0.5 (docs/v0.5/SPEC.md 4a): the same session also lists <mods>/.index/ (InstanceEvidence), as one task of its own, so
// the mod-files policy can use that evidence before detection answers. A probe waits for the detection alone (a slow
// listing never turns an answered detection into a timeout); at the cap it answers NOT_YET (PENDING for the policy, never
// the Unknown that would make it RIGTUNE). When a probe answered before both were in, the full answer goes to the
// late-answer listener once they are, once per detection and off the render thread (onLateAnswer), so the report can
// follow it; record() keeps a stale NOT_YET from overwriting an answer already in, and what it recorded is the one launcher
// the policy, its advice and Undo read (recorded(), review H1).
public final class LauncherProbe {
	public static final long TIMEOUT_MS = 3000;
	// Reads as Unknown everywhere (equals LauncherInfo.UNKNOWN); only its identity says "not answered yet".
	public static final LauncherInfo NOT_YET = new LauncherInfo(Launcher.UNKNOWN, null);
	private static @Nullable CompletableFuture<LauncherInfo> detection;
	private static @Nullable CompletableFuture<InstanceEvidence> evidence;
	private static @Nullable CompletableFuture<LauncherInfo> answered;
	// This detection: a probe answered before the detection and the listing were both in; the listener was attached.
	private static boolean late;
	private static boolean lateWatched;
	private static @Nullable Consumer<LauncherInfo> lateListener;
	// Where the late answer is handed on: Probes.EXECUTOR, never the render thread (tests set their own).
	private static @Nullable Executor lateExecutor;
	// What RealController recorded last (record()), NOT_YET until an answer is.
	private static LauncherInfo recorded = NOT_YET;

	private LauncherProbe() {
	}

	public static synchronized CompletableFuture<LauncherInfo> probeAsync(Path gameDir) {
		if (detection == null) {
			// The mods folder is resolved on the worker too (InstanceDirs touches the file system).
			begin(start(() -> LauncherDetector.detect(signals(System::getProperty, System::getenv, gameDir)), Probes.EXECUTOR),
					startListing(() -> InstanceDirs.modsDir(gameDir), Probes.EXECUTOR));
		}
		return probe(TIMEOUT_MS);
	}

	/** Game tests only: the next probe detects again (after the test changed a signal). */
	public static synchronized void reset() {
		detection = null;
		evidence = null;
		answered = null;
		late = false;
		lateWatched = false;
		recorded = NOT_YET;
	}

	// What the detection answered, or null while it hasn't (or before any probe): the mod-files policy's launcher.
	public static synchronized @Nullable LauncherInfo answer() {
		return detection == null ? null : detection.getNow(null);
	}

	// What the .index/ listing found, or null while it hasn't answered (or before any probe).
	public static synchronized @Nullable InstanceEvidence evidence() {
		return evidence == null ? null : evidence.getNow(null);
	}

	// Records a probe's answer (RealController.launcherDetected) and returns what was recorded: a NOT_YET gives way to an
	// answer already in, so a stale timeout never overwrites a newer record.
	public static synchronized LauncherInfo record(LauncherInfo detected) {
		LauncherInfo answer = answer();
		recorded = detected != NOT_YET || answer == null ? detected : answer;
		return recorded;
	}

	// The recorded launcher as the mod-files policy, its advice and Undo's launcher-managed skip take it (one source, the
	// same value RealController.launcher() shows): null until an answer is recorded.
	public static synchronized @Nullable LauncherInfo recorded() {
		return detected(recorded);
	}

	// A recorded launcher as the mod-files policy takes it: null while detection hasn't answered (NOT_YET).
	public static @Nullable LauncherInfo detected(LauncherInfo recorded) {
		return recorded == NOT_YET ? null : recorded;
	}

	// The late-answer listener (the latest one set): once a probe of this detection answered before the detection and the
	// listing were both in, it gets the detection's answer when they are (at once if they already are), on Probes.EXECUTOR,
	// once per detection, never after a reset(). A detection whose probe had everything in never calls it.
	public static void onLateAnswer(@Nullable Consumer<LauncherInfo> listener) {
		synchronized (LauncherProbe.class) {
			lateListener = listener;
		}
		watchLate();
	}

	private static void watchLate() {
		CompletableFuture<LauncherInfo> watched;
		Consumer<LauncherInfo> listener;
		Executor executor;
		synchronized (LauncherProbe.class) {
			if (answered == null || !late || lateWatched || lateListener == null) {
				return;
			}
			lateWatched = true;
			watched = answered;
			listener = lateListener;
			executor = lateExecutor != null ? lateExecutor : Probes.EXECUTOR;
		}
		watched.thenAcceptAsync(info -> {
			if (current(watched)) {
				listener.accept(info);
			}
		}, executor);
	}

	private static void late(CompletableFuture<LauncherInfo> session) {
		synchronized (LauncherProbe.class) {
			if (answered != session) {
				return;
			}
			late = true;
		}
		watchLate();
	}

	/** Tests only: where the late answer is handed on (null: Probes.EXECUTOR). */
	static synchronized void lateExecutor(@Nullable Executor executor) {
		lateExecutor = executor;
	}

	private static synchronized boolean current(CompletableFuture<LauncherInfo> watched) {
		return answered == watched;
	}

	static synchronized @Nullable CompletableFuture<LauncherInfo> detection() {
		return detection;
	}

	// A new session from these two (probeAsync, and the tests' own futures). allOf, not thenCombine, so no lambda type on
	// the caller's (render) thread names InstanceEvidence.
	static synchronized void begin(CompletableFuture<LauncherInfo> detected, CompletableFuture<InstanceEvidence> listed) {
		detection = detected;
		evidence = listed;
		answered = CompletableFuture.allOf(detected, listed).thenApply(ignored -> detected.join());
		late = false;
		lateWatched = false;
	}

	// The detection with the cap (NOT_YET at it); a probe that answers before the listing is in counts as late too.
	static CompletableFuture<LauncherInfo> probe(long timeoutMs) {
		CompletableFuture<LauncherInfo> detected;
		CompletableFuture<LauncherInfo> session;
		synchronized (LauncherProbe.class) {
			detected = detection;
			session = answered;
		}
		if (detected == null || session == null) {
			return CompletableFuture.completedFuture(NOT_YET);
		}
		return withTimeout(detected, timeoutMs).thenApply(info -> {
			if (!session.isDone()) {
				late(session);
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

	// The whole listing as one task on the executor, never the caller's thread (X4: the file system and InstanceEvidence are
	// touched there only); anything unexpected, or an executor that refuses the task, is no evidence.
	static CompletableFuture<InstanceEvidence> startListing(Supplier<@Nullable Path> modsDir, Executor executor) {
		CompletableFuture<InstanceEvidence> listing = new CompletableFuture<>();
		try {
			executor.execute(() -> listing.complete(listed(modsDir)));
		} catch (RuntimeException e) {
			listing.complete(InstanceEvidence.NONE);
		}
		return listing;
	}

	private static InstanceEvidence listed(Supplier<@Nullable Path> modsDir) {
		try {
			return InstanceEvidence.list(modsDir.get());
		} catch (Throwable t) {
			return InstanceEvidence.NONE;
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
