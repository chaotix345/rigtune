package io.github.chaotix345.rigtune.client.probe;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.9: the watcher never starts without a real battery, and reports a change after two polls in a row.
class PowerWatcherTest {
	private static final class FakeBattery implements PowerWatcher.Battery {
		final String name;
		final String chemistry;
		final int capacity;
		boolean onLine = true;
		boolean discharging;
		boolean readable = true;
		int updates;

		FakeBattery(String name, String chemistry, int capacity) {
			this.name = name;
			this.chemistry = chemistry;
			this.capacity = capacity;
		}

		@Override
		public String deviceName() {
			return name;
		}

		@Override
		public String chemistry() {
			return chemistry;
		}

		@Override
		public int maxCapacity() {
			return capacity;
		}

		@Override
		public int designCapacity() {
			return capacity;
		}

		@Override
		public boolean update() {
			updates++;
			return readable;
		}

		@Override
		public boolean powerOnLine() {
			return onLine;
		}

		@Override
		public boolean discharging() {
			return discharging;
		}
	}

	@Test
	void neverStartsWithoutARealBattery() {
		java.util.concurrent.ScheduledThreadPoolExecutor executor = new java.util.concurrent.ScheduledThreadPoolExecutor(1);
		try {
			// OSHI's placeholder on a Windows desktop, and no source at all.
			assertNull(PowerWatcher.start(List.of(new FakeBattery("unknown", "unknown", 1)), false, on -> { }, executor, 30));
			assertNull(PowerWatcher.start(List.of(), false, on -> { }, executor, 30));
			assertFalse(executor.isShutdown());
			assertEquals(0, executor.getQueue().size(), "nothing scheduled");
		} finally {
			executor.shutdownNow();
		}
		PowerWatcher.startIfBattery(false, false, on -> { });
		assertFalse(PowerWatcher.isRunning());
	}

	@Test
	void aRealBatteryIsPolledAndAChangeIsReportedAfterTwoPolls() {
		FakeBattery placeholder = new FakeBattery("unknown", "unknown", 1);
		FakeBattery laptop = new FakeBattery("DELL 7FHR", "LiP", 54000);
		assertEquals(List.of(laptop), PowerWatcher.realBatteries(List.of(placeholder, laptop)));
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		List<Boolean> edges = new ArrayList<>();
		try {
			PowerWatcher watcher = PowerWatcher.start(List.of(placeholder, laptop), false, edges::add, executor, 3600);
			assertNotNull(watcher);
			watcher.poll();
			laptop.onLine = false;
			laptop.discharging = true;
			watcher.poll();
			assertEquals(List.of(), edges);
			watcher.poll();
			assertEquals(List.of(true), edges);
			laptop.onLine = true;
			laptop.discharging = false;
			watcher.poll();
			watcher.poll();
			assertEquals(List.of(true, false), edges);
			assertEquals(5, laptop.updates);
			// Polls that read no battery tell nothing: unplugged but unreadable doesn't count as "on AC" again.
			laptop.onLine = false;
			laptop.discharging = true;
			watcher.poll();
			watcher.poll();
			assertEquals(List.of(true, false, true), edges);
			laptop.readable = false;
			watcher.poll();
			watcher.poll();
			assertEquals(List.of(true, false, true), edges);
			assertEquals(0, placeholder.updates);
			assertTrue(laptop.updates > 0);
		} finally {
			executor.shutdownNow();
		}
	}

	// docs/v0.5/SPEC.md AC3e.3: polls that read no battery aren't "on AC"; with two batteries one discharging off the mains
	// is on battery; a wiggle shorter than the debounce is no edge; stop() before the startup probe finishes starts nothing.
	@Test
	void pollsThatReadNoBatteryAreNotAc() {
		FakeBattery first = new FakeBattery("BAT0", "Li-ion", 50000);
		FakeBattery second = new FakeBattery("BAT1", "Li-ion", 30000);
		// On battery at start; the batteries' last values would say AC, but no poll can read them.
		first.onLine = second.onLine = true;
		List<Boolean> edges = new ArrayList<>();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try {
			PowerWatcher watcher = PowerWatcher.start(List.of(first, second), true, edges::add, executor, 3600);
			assertNotNull(watcher);
			first.readable = second.readable = false;
			for (int i = 0; i < 5; i++) {
				watcher.poll();
			}
			assertEquals(List.of(), edges);
			assertEquals(5, first.updates);
			assertEquals(5, second.updates);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void oneOfTwoBatteriesDischargingIsOnBattery() {
		FakeBattery charging = new FakeBattery("BAT0", "Li-ion", 50000);
		FakeBattery discharging = new FakeBattery("BAT1", "Li-ion", 30000);
		List<Boolean> edges = new ArrayList<>();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try {
			PowerWatcher watcher = PowerWatcher.start(List.of(charging, discharging), false, edges::add, executor, 3600);
			assertNotNull(watcher);
			discharging.onLine = false;
			discharging.discharging = true;
			watcher.poll();
			watcher.poll();
			assertEquals(List.of(true), edges);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void aWiggleShorterThanTheDebounceIsNoEdge() {
		FakeBattery laptop = new FakeBattery("BAT0", "Li-ion", 50000);
		List<Boolean> edges = new ArrayList<>();
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
		try {
			PowerWatcher watcher = PowerWatcher.start(List.of(laptop), false, edges::add, executor, 3600);
			assertNotNull(watcher);
			for (int i = 0; i < 3; i++) {
				laptop.onLine = false;
				laptop.discharging = true;
				watcher.poll();
				laptop.onLine = true;
				laptop.discharging = false;
				watcher.poll();
			}
			assertEquals(List.of(), edges);
		} finally {
			executor.shutdownNow();
		}
	}

	// The client stopping while the startup probe still runs: the probe's late startIfBattery (a battery found) reads no
	// battery and starts nothing; without the stop it would start.
	@Test
	void stopBeforeTheProbeFinishesStartsNothing() {
		AtomicInteger reads = new AtomicInteger();
		Supplier<List<PowerWatcher.Battery>> laptop = () -> {
			reads.incrementAndGet();
			return List.of(new FakeBattery("BAT0", "Li-ion", 50000));
		};
		PowerWatcher.stop();
		PowerWatcher.startIfBattery(true, true, on -> { }, laptop);
		assertFalse(PowerWatcher.isRunning());
		assertEquals(0, reads.get(), "the batteries weren't read after stop()");
		PowerWatcher.resetForTest();
		PowerWatcher.startIfBattery(true, true, on -> { }, laptop);
		assertTrue(PowerWatcher.isRunning(), "the same call starts the watcher when nothing stopped it");
		assertEquals(1, reads.get());
	}

	@AfterEach
	void resetTheStaticWatcher() {
		PowerWatcher.resetForTest();
	}
}
