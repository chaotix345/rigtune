package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;

import java.util.function.BooleanSupplier;

// #42: a report built while a benchmark has its knobs applied reads the run's temporary settings (its uncapped frame
// rate, the render distance of its last step), so a rebuild asked for meanwhile waits, and one runs after the run's
// finish() has put the player's settings back (restored()). Render thread only. Kept apart from BenchmarkController so
// RealController's construction and start don't load that class (footprint budgets).
public final class RunRebuilds {
	private static volatile Runnable onRestored = () -> {
	};

	private final BooleanSupplier running;
	private boolean waiting;

	public RunRebuilds(BooleanSupplier running) {
		this.running = running;
	}

	// RealController's rebuild after a run's restore.
	public static void onRestored(Runnable rebuild) {
		onRestored = rebuild != null ? rebuild : () -> {
		};
	}

	// BenchmarkController.finish(), after the restore.
	public static void restored() {
		try {
			onRestored.run();
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Benchmark: could not rebuild the RigTune report", e);
		}
	}

	// True: a run is active, so the rebuild waits for its end.
	public boolean defer() {
		if (!running.getAsBoolean()) {
			return false;
		}
		waiting = true;
		return true;
	}

	// After the run restored the settings: true once if a rebuild waited for it.
	public boolean release() {
		boolean was = waiting;
		waiting = false;
		return was;
	}
}
