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
		double fps = 600.0 / rd;
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
		assertTrue(result.reason().contains("minimum"), result.reason());
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
		assertThrows(IllegalArgumentException.class, () -> new RenderDistancePlanner(10, 5, 6, 60, 6));
		assertThrows(IllegalArgumentException.class, () -> new RenderDistancePlanner(2, 32, 6, 60, 0));
	}
}
