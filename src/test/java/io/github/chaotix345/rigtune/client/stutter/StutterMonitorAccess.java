package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.core.stutter.StutterRings;

import java.time.Instant;

// Test-only: starts and stops a session capture on StutterMonitor without StutterCapture's GC listener and sampler (the
// rings and the render-thread hot path only), for tests outside this package (FrameHookBudgetTest, docs/v0.4/SPEC.md 10).
public final class StutterMonitorAccess {
	private StutterMonitorAccess() {
	}

	public static void startSession() {
		StutterMonitor.startSession(new StutterRings(0), System.nanoTime(), Instant.now());
	}

	public static void stopAll() {
		StutterMonitor.Capture s = StutterMonitor.session();
		if (s != null) {
			StutterMonitor.stop(s);
		}
		StutterMonitor.Capture b = StutterMonitor.benchmark();
		if (b != null) {
			StutterMonitor.stop(b);
		}
	}
}
