package io.github.chaotix345.rigtune.client.probe;

import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 2L (RW-16): how long vanilla's CrashReport.preload() took at this launch. Main.main runs it before any
// mod's init; it builds a crash report whose SystemReport asks OSHI for the paging file and the process, which waits
// seconds when Windows' performance counters are off. CrashReportMixin calls start() and end(): two clock reads into
// static fields, nothing allocated. Not measured (the mixin didn't apply, or preload hasn't run) is null. Only the first
// call counts: a dedicated server's Main in the same JVM (the game tests' in-process server) runs preload() again later
// (review-11 COMPAT-6).
public final class PreloadTimer {
	private static volatile long startNanos;
	private static volatile long endNanos;
	private static volatile boolean started;
	private static volatile boolean ended;

	private PreloadTimer() {
	}

	public static void start() {
		if (started) {
			return;
		}
		startNanos = System.nanoTime();
		started = true;
	}

	public static void end() {
		if (started && !ended) {
			endNanos = System.nanoTime();
			ended = true;
		}
	}

	public static @Nullable Long preloadMs() {
		return started && ended ? Math.max(0, (endNanos - startNanos) / 1_000_000) : null;
	}

	static void reset() {
		started = false;
		ended = false;
	}
}
