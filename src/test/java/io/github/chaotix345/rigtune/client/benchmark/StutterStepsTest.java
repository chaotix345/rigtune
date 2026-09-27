package io.github.chaotix345.rigtune.client.benchmark;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md RW-15 (AC2B.9, WS-B's half): the frames of a step whose settle timed out stay out of the benchmark's
// stutter capture. The capture only records between sweep(true) and sweep(false), so such a step gets neither: only
// StutterHooks.benchmarkStepExcluded(true/false) around it (WS-S's seam counts it). RW-5's re-measure records normally.
class StutterStepsTest {
	private static final class Calls implements StutterSteps.Hooks {
		final List<String> calls = new ArrayList<>();

		@Override
		public void sweep(boolean recording) {
			calls.add("sweep " + recording);
		}

		@Override
		public void excluded(boolean excluded) {
			calls.add("excluded " + excluded);
		}
	}

	@Test
	void rw15AnIncompleteStepIsLeftOutOfTheCapture() {
		Calls hooks = new Calls();
		StutterSteps steps = new StutterSteps(hooks);
		steps.begin(true);
		steps.end();
		steps.begin(false);
		steps.end();
		// The re-measure of the same distance, settled this time.
		steps.begin(true);
		steps.end();
		assertEquals(List.of("sweep true", "sweep false", "excluded true", "excluded false", "sweep true", "sweep false"), hooks.calls);
	}

	@Test
	void rw15ARunEndingInsideAStepClosesIt() {
		Calls excludedStep = new Calls();
		StutterSteps inside = new StutterSteps(excludedStep);
		inside.begin(false);
		inside.close();
		inside.close();
		assertEquals(List.of("excluded true", "excluded false"), excludedStep.calls);

		Calls between = new Calls();
		StutterSteps idle = new StutterSteps(between);
		idle.begin(true);
		idle.end();
		idle.close();
		assertEquals(List.of("sweep true", "sweep false"), between.calls, "nothing to close between steps");

		// Inside a recorded step, as before: StutterHooks.benchmarkFinished stops the capture.
		Calls recorded = new Calls();
		StutterSteps sweeping = new StutterSteps(recorded);
		sweeping.begin(true);
		sweeping.close();
		assertEquals(List.of("sweep true"), recorded.calls);
	}
}
