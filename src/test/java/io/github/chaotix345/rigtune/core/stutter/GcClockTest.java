package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.3 (GcClockTest): the recorded G1 and ZGC probe runs (research §1; src/test/resources/stutter/gc-*.txt) → the
// calibrated offset is close to the -Xlog:gc truth, and each mapped System.gc() pause lies inside its call window.
class GcClockTest {
	private static final long MS = 1_000_000L;
	// An arbitrary nanoTime origin: nothing may depend on it.
	private static final long ANCHOR = 703_475_933_120_000L;

	record Notification(String bean, String action, String cause, long startMs, long endMs, double receivedUptimeMs) {
	}

	record Run(List<Notification> notifications, List<double[]> truth, List<double[]> windows) {
		double truthOffset() {
			return truth.stream().mapToDouble(t -> t[1] - t[0]).min().orElseThrow();
		}
	}

	static Run load(String name) throws IOException {
		List<Notification> n = new ArrayList<>();
		List<double[]> truth = new ArrayList<>();
		List<double[]> windows = new ArrayList<>();
		try (InputStream in = GcClockTest.class.getResourceAsStream("/stutter/" + name)) {
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				String[] p = line.strip().split("\\|");
				switch (p[0]) {
					case "N" -> n.add(new Notification(p[1], p[2], p[3], Long.parseLong(p[4]), Long.parseLong(p[5]), Double.parseDouble(p[6])));
					case "T" -> truth.add(new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2])});
					case "W" -> windows.add(new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2])});
					default -> {
					}
				}
			}
		}
		return new Run(n, truth, windows);
	}

	static GcClock replay(Run run) {
		GcClock clock = new GcClock(ANCHOR);
		for (Notification n : run.notifications()) {
			clock.observe(ANCHOR + Math.round(n.receivedUptimeMs() * MS), n.endMs());
		}
		return clock;
	}

	@Test
	void zgcOffsetIsWithinAThirdOfAMillisecondOfTheLog() throws IOException {
		Run run = load("gc-zgc.txt");
		GcClock clock = replay(run);
		assertTrue(clock.calibrated());
		assertEquals(18.674, run.truthOffset(), 0.001);
		assertEquals(run.truthOffset(), clock.offsetMs(), 0.3);
		assertTrue(clock.offsetMs() >= run.truthOffset() - 1.0, "never earlier than the truth by more than the ms truncation");
	}

	// The only G1 run whose receive times were kept has 21 notifications; its estimate is 0.32 ms above the -Xlog value
	// (the research's 300-pause G1 runs measured +0.08 and +0.23 ms but kept only the estimates). docs/v0.4/design/ws-s.md.
	@Test
	void g1OffsetIsWithinTheTruncationGuardOfTheLog() throws IOException {
		Run run = load("gc-g1.txt");
		GcClock clock = replay(run);
		assertEquals(17.038, run.truthOffset(), 0.001);
		assertEquals(run.truthOffset(), clock.offsetMs(), 0.35);
		assertTrue(clock.offsetMs() >= run.truthOffset() - 1.0);
	}

	// research §1.4's cross-check: every System.gc() pause maps inside the window the call ran in (start within 1 ms before
	// the call, end within 0.5 ms after it), for both collectors; ZGC reports several pause phases per call.
	@Test
	void systemGcPausesMapInsideTheirCallWindows() throws IOException {
		for (String name : List.of("gc-g1.txt", "gc-zgc.txt")) {
			Run run = load(name);
			GcClock.Calibration c = replay(run).calibration();
			int checked = 0;
			for (Notification n : run.notifications()) {
				int flags = GcKind.classify(n.bean(), n.action(), n.cause());
				if ((flags & GcKind.EXPLICIT) == 0 || !GcKind.pause(flags)) {
					continue;
				}
				double start = (c.pauseStart(n.startMs()) - ANCHOR) / (double) MS;
				double end = (c.pauseEnd(n.endMs()) - ANCHOR) / (double) MS - 1;
				double[] window = run.windows().stream().filter(w -> start >= w[0] - 1.0 && start <= w[1]).findFirst().orElse(null);
				assertTrue(window != null, name + ": no call window for " + n);
				assertTrue(end <= window[1] + 0.5, name + ": " + n + " ends after its call window");
				checked++;
			}
			assertEquals(name.equals("gc-g1.txt") ? 3 : 24, checked, name);
		}
	}

	@Test
	void uncalibratedUntilTheFirstNotification() {
		GcClock clock = new GcClock(ANCHOR);
		assertFalse(clock.calibrated());
		assertFalse(clock.calibration().calibrated());
		clock.observe(ANCHOR + 130 * MS, 110);
		assertTrue(clock.calibration().calibrated());
		assertEquals(20.0, clock.offsetMs(), 1e-9);
		clock.observe(ANCHOR + 150 * MS, 131);
		assertEquals(19.0, clock.offsetMs(), 1e-9, "the minimum wins");
		clock.observe(ANCHOR + 400 * MS, 131);
		assertEquals(19.0, clock.offsetMs(), 1e-9, "a late delivery changes nothing");
	}

	// The pause interval carries the +1 ms truncation guard at its end only.
	@Test
	void pauseIntervalHasTheTruncationGuard() {
		GcClock.Calibration c = new GcClock.Calibration(ANCHOR, 17.5);
		assertEquals(ANCHOR + 217_500_000L, c.pauseStart(200));
		assertEquals(ANCHOR + 219_500_000L, c.pauseEnd(201));
	}

	@Test
	void anchorWaitsForTheUptimeToTickOver() {
		long[] uptime = {100};
		long[] nanos = {5_000_000_000L};
		int[] calls = {0};
		long anchor = GcClock.anchor(() -> ++calls[0] < 5 ? uptime[0] : uptime[0] + 1, () -> nanos[0]);
		assertEquals(5_000_000_000L - 101 * MS, anchor);
		long frozen = GcClock.anchor(() -> 7, () -> 9 * MS);
		assertEquals(2 * MS, frozen, "a clock that never ticks still returns");
	}
}
