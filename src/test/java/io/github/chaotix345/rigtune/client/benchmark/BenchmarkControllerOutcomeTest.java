package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.benchmark.SettleCheck;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import io.github.chaotix345.rigtune.core.benchmark.Timing;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkControllerOutcomeTest {
	private static BenchmarkController.Settled settled(int rd, boolean timedOut, int missing) {
		Knobs knobs = new Knobs(rd, 8, false, false);
		return new BenchmarkController.Settled(new Step(Step.Kind.RENDER_DISTANCE, knobs, Timing.DEFAULT.full()),
				new SettleCheck.Result(rd - 1, 1000, missing, timedOut ? 20 : 3, timedOut), 1200);
	}

	// docs/v0.5/SPEC.md RW-15 (AC2B.9): the steps left out of the stutter capture are the ones whose settle timed out
	// incomplete; a timeout within the 2 % allowance still counts as settled.
	@Test
	void rw15StepsLeftOutCountsTheTimedOutSettles() {
		Knobs knobs = new Knobs(32, 8, false, false);
		SessionResult session = new SessionResult(BenchmarkRequest.Mode.TUNE, knobs, knobs, 170,
				new PlannerResult(31, true, 31, List.of(), "test"), List.of(), null, null, null, Map.of(), false);
		BenchmarkController.Outcome outcome = new BenchmarkController.Outcome(BenchmarkRequest.DEFAULT, session, false, null, null, true, false,
				List.of(settled(32, true, 372), settled(17, false, 0), settled(31, true, 15), settled(32, false, 0)));
		assertEquals(1, outcome.stepsLeftOut());
	}
}
