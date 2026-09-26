package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.profile.BatteryPrompt;
import org.jspecify.annotations.Nullable;
import oshi.SystemInfo;
import oshi.hardware.PowerSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

// Follows the power state on a laptop (docs/v0.4/SPEC.md 4, research profiles.md §6). Runs only when the startup probe
// found a real battery: one daemon thread "RigTune power" re-reads the real batteries every PERIOD_SECONDS (about half a
// millisecond per poll with OSHI 6.9 on Windows), and a change counts after BatteryPrompt.DEBOUNCE_POLLS polls in a row.
// On a counted change it tells the listener (on this thread). It never switches anything itself.
public final class PowerWatcher {
	public static final long PERIOD_SECONDS = 30;
	private static final String THREAD_NAME = "RigTune power";
	private static @Nullable PowerWatcher running;

	// One battery as the watcher sees it (OSHI's PowerSource in the game; a fake in tests).
	public interface Battery {
		String deviceName();

		String chemistry();

		int maxCapacity();

		int designCapacity();

		// Re-reads the battery's state; false when that failed.
		boolean update();

		boolean powerOnLine();

		boolean discharging();
	}

	private final List<Battery> batteries;
	private final BatteryPrompt.Debouncer debouncer;
	private final Consumer<Boolean> listener;
	private final ScheduledExecutorService executor;

	private PowerWatcher(List<Battery> batteries, boolean onBattery, Consumer<Boolean> listener, ScheduledExecutorService executor) {
		this.batteries = List.copyOf(batteries);
		this.debouncer = new BatteryPrompt.Debouncer(onBattery);
		this.listener = listener;
		this.executor = executor;
	}

	// The real batteries among sources (OSHI's placeholder "System Battery" on desktops isn't one).
	public static List<Battery> realBatteries(List<Battery> sources) {
		List<Battery> out = new ArrayList<>();
		for (Battery battery : sources) {
			if (HardwareProbe.realBattery(battery.deviceName(), battery.chemistry(), battery.maxCapacity(), battery.designCapacity())) {
				out.add(battery);
			}
		}
		return out;
	}

	// A watcher over the real batteries among sources, polling on executor; null (nothing started, executor untouched) when
	// there is none.
	public static @Nullable PowerWatcher start(List<Battery> sources, boolean onBattery, Consumer<Boolean> listener,
			ScheduledExecutorService executor, long periodSeconds) {
		List<Battery> real = realBatteries(sources);
		if (real.isEmpty()) {
			return null;
		}
		PowerWatcher watcher = new PowerWatcher(real, onBattery, listener, executor);
		executor.scheduleWithFixedDelay(watcher::poll, periodSeconds, periodSeconds, TimeUnit.SECONDS);
		return watcher;
	}

	// In the game: after the startup probe, only when it found a real battery. Idempotent.
	public static synchronized void startIfBattery(boolean hasBattery, boolean onBattery, Consumer<Boolean> listener) {
		if (!hasBattery || running != null) {
			return;
		}
		ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, THREAD_NAME);
			thread.setDaemon(true);
			return thread;
		});
		try {
			List<Battery> sources = new ArrayList<>();
			for (PowerSource source : new SystemInfo().getHardware().getPowerSources()) {
				sources.add(oshi(source));
			}
			running = start(sources, onBattery, listener, executor, PERIOD_SECONDS);
		} catch (Throwable t) {
			RigTune.LOGGER.warn("RigTune can't follow the battery state", t);
		}
		if (running == null) {
			executor.shutdownNow();
		}
	}

	public static synchronized void stop() {
		if (running != null) {
			running.executor.shutdownNow();
			running = null;
		}
	}

	public static synchronized boolean isRunning() {
		return running != null;
	}

	// One poll: on battery when some real battery is off the mains and discharging.
	void poll() {
		try {
			boolean onBattery = false;
			for (Battery battery : batteries) {
				if (battery.update()) {
					onBattery |= !battery.powerOnLine() && battery.discharging();
				}
			}
			Boolean edge = debouncer.poll(onBattery);
			if (edge != null) {
				listener.accept(edge);
			}
		} catch (Throwable t) {
			RigTune.LOGGER.warn("RigTune power poll failed", t);
		}
	}

	private static Battery oshi(PowerSource source) {
		return new Battery() {
			@Override
			public String deviceName() {
				return source.getDeviceName();
			}

			@Override
			public String chemistry() {
				return source.getChemistry();
			}

			@Override
			public int maxCapacity() {
				return source.getMaxCapacity();
			}

			@Override
			public int designCapacity() {
				return source.getDesignCapacity();
			}

			@Override
			public boolean update() {
				return source.updateAttributes();
			}

			@Override
			public boolean powerOnLine() {
				return source.isPowerOnLine();
			}

			@Override
			public boolean discharging() {
				return source.isDischarging();
			}
		};
	}
}
