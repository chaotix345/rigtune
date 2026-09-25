package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderDistancePlannerTest {
	private static final int MIN = 2;
	private static final int MAX = 32;

	private static FrameStats simulated(int rd) {
		return low(600.0 / rd);
	}

	private static FrameStats low(double fps) {
		return new FrameStats(100, fps, fps, 1000 / fps, 1000 / fps);
	}

	private static List<Integer> run(RenderDistancePlanner planner) {
		List<Integer> tested = new ArrayList<>();
		OptionalInt next;
		while ((next = planner.next()).isPresent()) {
			int rd = next.getAsInt();
			assertFalse(tested.contains(rd), "retested " + rd + " after " + tested);
			assertTrue(rd >= MIN && rd <= MAX, "out of range: " + rd);
			tested.add(rd);
			planner.record(rd, simulated(rd));
		}
		assertTrue(planner.done());
		return tested;
	}

	@Test
	void bisectsDownAfterFailedStart() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 12, 60, 10);

		assertEquals(List.of(12, 6, 9, 10, 11), run(planner));
		PlannerResult result = planner.result();
		assertEquals(10, result.bestRd());
		assertTrue(result.targetMet());
		assertEquals(5, result.measurements().size());
		assertTrue(result.reason().startsWith("Converged"), result.reason());
	}

	@Test
	void allPassClimbsWithGrowingStepsToMax() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 12, 10, 10);

		assertEquals(List.of(12, 14, 18, 26, 32), run(planner));
		PlannerResult result = planner.result();
		assertEquals(32, result.bestRd());
		assertTrue(result.targetMet());
		assertTrue(result.reason().contains("maximum"), result.reason());
	}

	@Test
	void nonePassFallsBackToMin() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 12, 1000, 10);

		assertEquals(List.of(12, 6, 3, 2), run(planner));
		PlannerResult result = planner.result();
		assertEquals(MIN, result.bestRd());
		assertFalse(result.targetMet());
		assertEquals(MIN, result.bestEffortRd());
		assertEquals(MIN, result.suggestedRd());
		assertTrue(result.reason().contains("minimum"), result.reason());
	}

	@Test
	void missedTargetSuggestsHighestOnePercentLowNotMinimum() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 12, 200, 3);
		assertEquals(OptionalInt.of(12), planner.next());
		planner.record(12, low(110));
		assertEquals(OptionalInt.of(6), planner.next());
		planner.record(6, low(150));
		assertEquals(OptionalInt.of(3), planner.next());
		planner.record(3, low(140));
		assertTrue(planner.done());

		PlannerResult result = planner.result();
		assertFalse(result.targetMet());
		assertEquals(MIN, result.bestRd());
		assertEquals(6, result.bestEffortRd());
		assertEquals(6, result.suggestedRd());
	}

	@Test
	void bestEffortTieGoesToLargerDistance() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 12, 200, 2);
		planner.record(12, low(119));
		planner.record(6, low(119));

		assertEquals(12, planner.result().bestEffortRd());
	}

	@Test
	void metTargetSuggestsBestPass() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 12, 60, 10);
		run(planner);

		PlannerResult result = planner.result();
		assertTrue(result.targetMet());
		assertEquals(10, result.suggestedRd());
		assertEquals(6, result.bestEffortRd());
	}

	@Test
	void startAtMinClimbsThenBisects() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, MIN, 60, 10);

		assertEquals(List.of(2, 4, 8, 16, 12, 10, 11), run(planner));
		assertEquals(10, planner.result().bestRd());
		assertTrue(planner.result().targetMet());
	}

	@Test
	void startAtMaxBisectsDown() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, MAX, 60, 10);

		assertEquals(List.of(32, 16, 8, 12, 10, 11), run(planner));
		assertEquals(10, planner.result().bestRd());
	}

	@Test
	void startIsClampedIntoRange() {
		assertEquals(OptionalInt.of(MAX), new RenderDistancePlanner(MIN, MAX, 99, 60, 6).next());
		assertEquals(OptionalInt.of(MIN), new RenderDistancePlanner(MIN, MAX, 0, 60, 6).next());
	}

	@Test
	void singleStepStopsImmediately() {
		RenderDistancePlanner failing = new RenderDistancePlanner(MIN, MAX, 12, 60, 1);
		assertEquals(List.of(12), run(failing));
		assertEquals(MIN, failing.result().bestRd());
		assertFalse(failing.result().targetMet());
		assertTrue(failing.result().reason().contains("Step limit"), failing.result().reason());

		RenderDistancePlanner passing = new RenderDistancePlanner(MIN, MAX, 8, 60, 1);
		assertEquals(List.of(8), run(passing));
		assertEquals(8, passing.result().bestRd());
		assertTrue(passing.result().targetMet());
	}

	@Test
	void stepLimitKeepsBestPassSoFar() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, MIN, 60, 6);

		assertEquals(List.of(2, 4, 8, 16, 12, 10), run(planner));
		assertEquals(10, planner.result().bestRd());
		assertTrue(planner.result().targetMet());
	}

	@Test
	void resultBeforeAnyMeasurement() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 12, 60, 6);
		assertFalse(planner.done());
		assertEquals(MIN, planner.result().bestRd());
		assertFalse(planner.result().targetMet());
		assertEquals(MIN, planner.result().suggestedRd());
		assertThrows(IllegalArgumentException.class, () -> new RenderDistancePlanner(10, 5, 6, 60, 6));
		assertThrows(IllegalArgumentException.class, () -> new RenderDistancePlanner(2, 32, 6, 60, 0));
	}

	// docs/v0.3/SPEC.md E-M1: a step measured before its terrain arrived can't count as a pass, however fast it was.
	@Test
	void anIncompleteStepNeverPasses() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 8, 100, 6);
		assertEquals(OptionalInt.of(8), planner.next());
		planner.record(8, low(150));
		assertEquals(OptionalInt.of(10), planner.next());
		planner.record(10, low(120));
		assertEquals(OptionalInt.of(14), planner.next());
		planner.record(14, low(500), false);
		assertEquals(OptionalInt.of(12), planner.next());
		planner.record(12, low(110));
		assertEquals(OptionalInt.of(13), planner.next());
		planner.record(13, low(90));
		assertTrue(planner.done());
		PlannerResult result = planner.result();
		assertEquals(12, result.bestRd());
		PlannerResult.Measurement fourteen = result.measurements().stream().filter(m -> m.rd() == 14).findFirst().orElseThrow();
		assertFalse(fourteen.passed());
		assertFalse(fourteen.complete());
		assertTrue(result.measurements().stream().filter(m -> m.rd() != 14).allMatch(PlannerResult.Measurement::complete));
	}

	@Test
	void bestEffortPrefersCompleteSteps() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 16, 1000, 3);
		planner.record(16, low(400), false);
		planner.record(9, low(300));
		planner.record(5, low(350));
		assertFalse(planner.result().targetMet());
		assertEquals(5, planner.result().bestEffortRd());

		RenderDistancePlanner onlyIncomplete = new RenderDistancePlanner(MIN, MAX, 16, 1000, 1);
		onlyIncomplete.record(16, low(400), false);
		assertEquals(16, onlyIncomplete.result().bestEffortRd());
	}
}
