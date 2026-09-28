package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 6 (C09): the outcome hook Try it uses so its runs open no result screen. No handler, or one that
// answers false or throws: the outcome is shown as before (show() and the current-world cancel branch ask it).
class BenchmarkControllerHandlerTest {
	private static BenchmarkController.Outcome outcome(String pairId) {
		Knobs knobs = new Knobs(12, 8, false, false);
		SessionResult session = new SessionResult(BenchmarkRequest.Mode.MEASURE, knobs, knobs, 170,
				new PlannerResult(12, true, 12, List.of(), "test"), List.of(), null, null, null, Map.of(), false);
		return new BenchmarkController.Outcome(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, BenchmarkRequest.Scene.CURRENT, pairId), session,
				true, null, null, true, false, List.of());
	}

	@AfterEach
	void noHandler() {
		BenchmarkController.setOutcomeHandler(null);
	}

	@Test
	void withoutAHandlerNothingIsClaimed() {
		assertFalse(BenchmarkController.claimed(outcome("p")));
	}

	@Test
	void theHandlerDecides() {
		List<String> asked = new ArrayList<>();
		BenchmarkController.setOutcomeHandler(o -> {
			asked.add(o.request().pairId());
			return o.request().pairId().startsWith("tryit-");
		});
		assertTrue(BenchmarkController.claimed(outcome("tryit-1")));
		assertFalse(BenchmarkController.claimed(outcome("p")));
		assertEquals(List.of("tryit-1", "p"), asked);
	}

	@Test
	void aHandlerThatThrowsLeavesTheOutcomeToTheController() {
		BenchmarkController.setOutcomeHandler(o -> {
			throw new IllegalStateException("boom");
		});
		assertFalse(BenchmarkController.claimed(outcome("tryit-1")));
	}
}
