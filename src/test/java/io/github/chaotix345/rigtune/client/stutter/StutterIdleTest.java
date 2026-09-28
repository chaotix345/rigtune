package io.github.chaotix345.rigtune.client.stutter;

import com.mojang.blaze3d.platform.FramerateLimitTracker.FramerateThrottleReason;
import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.StutterAnalyzer;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// v0.5 RW-17 (real world 2026-09-28: 17.4 h of AFK-throttled idling saved as gameplay): while the game throttles its frame
// rate (vanilla's AFK or minimised limit, or Dynamic FPS lowering the limit in effect) the capture's frames are excluded
// like a menu's, and the idle time is counted apart.
class StutterIdleTest {
	private static final long MS = 1_000_000L;
	private static final long S = 1_000 * MS;

	@AfterEach
	void reset() {
		StutterMonitor.setIdle(false, System.nanoTime());
		StutterMonitor.setExcluded(false);
		StutterMonitorAccess.stopAll();
	}

	@Test
	void whatCountsAsThrottled() {
		assertTrue(StutterHooks.throttled(FramerateThrottleReason.LONG_AFK, 10, 120, true));
		assertTrue(StutterHooks.throttled(FramerateThrottleReason.SHORT_AFK, 30, 30, true), "a reason, even with the limit unchanged");
		assertTrue(StutterHooks.throttled(FramerateThrottleReason.WINDOW_ICONIFIED, 10, 260, false));
		assertFalse(StutterHooks.throttled(FramerateThrottleReason.NONE, 120, 120, true));
		assertFalse(StutterHooks.throttled(FramerateThrottleReason.NONE, 260, 260, true), "unlimited");
		assertTrue(StutterHooks.throttled(FramerateThrottleReason.NONE, 15, 120, true), "a lower limit in effect (Dynamic FPS)");
	}

	// The coordinator's case: 10 minutes of LONG_AFK frames (10 FPS) add no gameplay and no spike, and count as idle.
	@Test
	void tenMinutesOfLongAfkAddNoGameplayAndNoSpikes() {
		long t0 = 1_000 * S;
		StutterMonitor.Capture session = StutterMonitor.startSession(new StutterRings(0), t0, Instant.parse("2026-09-28T00:00:00Z"));
		for (int i = 0; i < 300; i++) {
			StutterMonitor.onFrame(8 * MS);
		}
		long before = session.snapshot().gameplayFrames();
		// What the tick hook does: throttled -> idle and excluded.
		boolean idle = StutterHooks.throttled(FramerateThrottleReason.LONG_AFK, 10, 120, true);
		StutterMonitor.setIdle(idle, t0 + 10 * S);
		StutterMonitor.setExcluded(idle);
		for (int i = 0; i < 6_000; i++) {
			StutterMonitor.onFrame(i % 500 == 0 ? 400 * MS : 100 * MS);
		}
		FrameRing.Snapshot frames = session.snapshot();
		assertEquals(before, frames.gameplayFrames(), "no gameplay frame while idle");
		assertEquals(10 * 60 * S, session.idleNanos(t0 + 610 * S));
		StutterMonitor.setIdle(false, t0 + 610 * S);
		assertEquals(10 * 60 * S, session.idleNanos(t0 + 700 * S), "the stretch closed when the throttle ended");

		StutterReport report = StutterAnalyzer.analyze(new StutterAnalyzer.Input(frames, new StutterRings(0).snapshot(), t0, t0 + 700 * S,
				Instant.parse("2026-09-28T00:00:00Z"), StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, false, false, true,
				session.idleNanos(t0 + 700 * S))).report();
		assertEquals(0, report.spikes().total(), "the 400 ms idle frames aren't spikes");
		assertTrue(report.gameplaySeconds() < 5, "only the 300 frames before: " + report.gameplaySeconds());
		assertEquals(600.0, report.idleSeconds());
	}

	@Test
	void aCaptureStartedWhileIdleCountsFromItsStart() {
		long t0 = 2_000 * S;
		StutterMonitor.setIdle(true, t0 - 50 * S);
		StutterMonitor.Capture session = StutterMonitor.startSession(new StutterRings(0), t0, Instant.EPOCH);
		assertEquals(30 * S, session.idleNanos(t0 + 30 * S));
		StutterMonitorAccess.stopAll();
		StutterMonitor.setIdle(false, t0 + 40 * S);
		StutterMonitor.Capture next = StutterMonitor.startSession(new StutterRings(0), t0 + 50 * S, Instant.EPOCH);
		assertEquals(0, next.idleNanos(t0 + 90 * S));
	}
}
