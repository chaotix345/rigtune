package io.github.chaotix345.rigtune.client.probe;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
			return true;
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
			assertEquals(0, placeholder.updates);
			assertTrue(laptop.updates > 0);
		} finally {
			executor.shutdownNow();
		}
	}
}
