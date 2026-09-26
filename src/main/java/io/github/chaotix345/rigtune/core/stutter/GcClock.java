package io.github.chaotix345.rigtune.core.stutter;

import java.util.function.LongSupplier;

// Maps GcInfo start/end times onto System.nanoTime() (docs/research/v0.4/stutter.md §1.4). GcInfo times are milliseconds
// since the VM finished initialising, RuntimeMXBean.getUptime() milliseconds since os::init: the two differ by the VM
// start-up time (17-28 ms, different every run), which no public API reports. So:
// 1. On enable, anchor the uptime clock on nanoTime (`anchor`: wait for the uptime to tick over, then nanosAtUptimeZero =
//    nanoTime - uptime; both are QueryPerformanceCounter on Windows, so there is no drift).
// 2. Each notification's receive time on the uptime clock minus its endMs is at least the offset (the pause ended before
//    the notification was sent); the minimum over the session converges on it (and absorbs endMs's truncation).
// 3. A pause is [start + offset, end + offset + 1 ms] (the +1 ms covers the truncation of endMs).
// The receive time itself is never used as the pause time: delivery lagged by up to 96 ms under CPU load.
// Not thread-safe: GcRing guards it.
public final class GcClock {
	private static final long MS = 1_000_000L;
	private static final int MAX_SPINS = 1_000_000;

	private final long nanosAtUptimeZero;
	private double offsetMs = Double.POSITIVE_INFINITY;

	public GcClock(long nanosAtUptimeZero) {
		this.nanosAtUptimeZero = nanosAtUptimeZero;
	}

	// nanoTime at uptime 0, taken right after the uptime clock ticks over (a bounded spin: the uptime has 1 ms steps).
	public static long anchor(LongSupplier uptimeMs, LongSupplier nanoTime) {
		long start = uptimeMs.getAsLong();
		long uptime = start;
		for (int i = 0; i < MAX_SPINS && uptime == start; i++) {
			uptime = uptimeMs.getAsLong();
		}
		return nanoTime.getAsLong() - uptime * MS;
	}

	public long anchor() {
		return nanosAtUptimeZero;
	}

	public void observe(long receivedNanos, long endMs) {
		double received = (receivedNanos - nanosAtUptimeZero) / (double) MS;
		offsetMs = Math.min(offsetMs, received - endMs);
	}

	public boolean calibrated() {
		return offsetMs != Double.POSITIVE_INFINITY;
	}

	// Meaningful only once calibrated.
	public double offsetMs() {
		return offsetMs;
	}

	public Calibration calibration() {
		return new Calibration(nanosAtUptimeZero, calibrated() ? offsetMs : Double.NaN);
	}

	// A copy for the analysis thread. offsetMs is NaN until the first notification arrived.
	public record Calibration(long nanosAtUptimeZero, double offsetMs) {
		public boolean calibrated() {
			return !Double.isNaN(offsetMs);
		}

		public long pauseStart(long startMs) {
			return nanosAtUptimeZero + Math.round((startMs + offsetMs) * MS);
		}

		public long pauseEnd(long endMs) {
			return nanosAtUptimeZero + Math.round((endMs + offsetMs + 1) * MS);
		}

		public long receivedNanos(double receivedUptimeMs) {
			return nanosAtUptimeZero + Math.round(receivedUptimeMs * MS);
		}
	}
}
