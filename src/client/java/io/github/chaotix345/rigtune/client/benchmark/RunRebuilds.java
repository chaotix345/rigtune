package io.github.chaotix345.rigtune.client.benchmark;

import java.util.function.BooleanSupplier;

// #42: a report built while a benchmark has its knobs applied reads the run's temporary settings (its uncapped frame
// rate, the render distance of its last step), so a rebuild asked for meanwhile waits, and one runs after the run's
// finish() has put the player's settings back. Render thread only.
public final class RunRebuilds {
	private final BooleanSupplier running;
	private boolean waiting;

	public RunRebuilds(BooleanSupplier running) {
		this.running = running;
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
