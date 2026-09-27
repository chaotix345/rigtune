package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.stutter.StutterHooks;

// The Stutter Doctor's side of each benchmark step (docs/v0.5/SPEC.md RW-15): the capture records a step's sweeps only
// when its terrain had loaded. A step whose settle timed out gets StutterHooks.benchmarkStepExcluded(true/false) instead of
// benchmarkSweep(true/false), so the capture stays paused through it (or hasn't started, when it's the first step); RW-5's
// re-measure of that distance records normally once it settles. Render thread.
final class StutterSteps {
	interface Hooks {
		void sweep(boolean recording);

		void excluded(boolean excluded);
	}

	static final Hooks STUTTER_HOOKS = new Hooks() {
		@Override
		public void sweep(boolean recording) {
			StutterHooks.benchmarkSweep(recording);
		}

		@Override
		public void excluded(boolean excluded) {
			try {
				StutterHooks.benchmarkStepExcluded(excluded);
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Stutter Doctor: could not leave a benchmark step out of the capture", e);
			}
		}
	};

	private final Hooks hooks;
	private boolean open;
	private boolean excluded;

	StutterSteps(Hooks hooks) {
		this.hooks = hooks;
	}

	// The step's first recorded frame is next; settled: its terrain had loaded (SettleCheck.Result.complete()).
	void begin(boolean settled) {
		open = true;
		excluded = !settled;
		if (excluded) {
			hooks.excluded(true);
		} else {
			hooks.sweep(true);
		}
	}

	// The step's last sweep ended.
	void end() {
		if (!open) {
			return;
		}
		open = false;
		if (excluded) {
			hooks.excluded(false);
		} else {
			hooks.sweep(false);
		}
	}

	// The run ended: a step left out is closed; inside a recorded step, StutterHooks.benchmarkFinished stops the capture.
	void close() {
		if (open && excluded) {
			hooks.excluded(false);
		}
		open = false;
	}
}
