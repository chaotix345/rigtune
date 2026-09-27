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

	// docs/v0.3/SPEC.md E-M1 as changed by docs/v0.5/SPEC.md RW-5: a step measured before its terrain arrived is neither a
	// pass, however fast it was, nor a fail; here 13 fails, so 14 can't change the answer and isn't measured again.
	@Test
	void anIncompleteStepIsNeitherAPassNorAFail() {
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
		assertTrue(result.reason().startsWith("Converged: 12 meets the target, 13 does not"), result.reason());
	}

	// docs/v0.5/SPEC.md RW-5 (AC2B.3): the real first run started at 32 on terrain still generating. 32 is not measured, the
	// search goes below it (17..31 pass), then 32 is measured again once, outside the step limit.
	private static RenderDistancePlanner incompleteAt32ThenPassesBelow() {
		RenderDistancePlanner planner = new RenderDistancePlanner(4, MAX, 32, 170, 6);
		assertEquals(OptionalInt.of(32), planner.next());
		planner.record(32, low(36), false);
		for (int expected : new int[]{17, 24, 28, 30, 31}) {
			assertEquals(OptionalInt.of(expected), planner.next());
			planner.record(expected, low(300));
		}
		return planner;
	}

	@Test
	void rw5AnIncompleteStepIsMeasuredAgainAfterTheLowerSteps() {
		RenderDistancePlanner planner = incompleteAt32ThenPassesBelow();
		assertEquals(OptionalInt.of(32), planner.next(), "measured again, though 6 steps were taken");
		assertFalse(planner.done());
	}

	@Test
	void rw5ACompletePassWhenMeasuredAgainMeetsTheMaximum() {
		RenderDistancePlanner planner = incompleteAt32ThenPassesBelow();
		planner.record(32, low(250));
		assertTrue(planner.done());
		PlannerResult result = planner.result();
		assertTrue(result.targetMet());
		assertEquals(32, result.bestRd());
		assertTrue(result.reason().contains("maximum") && result.reason().contains("32"), result.reason());
		assertTrue(result.measurements().stream().allMatch(PlannerResult.Measurement::complete));
	}

	@Test
	void rw5AFailWhenMeasuredAgainConverges() {
		RenderDistancePlanner planner = incompleteAt32ThenPassesBelow();
		planner.record(32, low(160));
		assertTrue(planner.done());
		assertEquals(31, planner.result().bestRd());
		assertTrue(planner.result().reason().startsWith("Converged: 31 meets the target, 32 does not"), planner.result().reason());
	}

	// No time left for the second try (the session's deadline): 32 couldn't be measured, and nothing says it failed.
	@Test
	void rw5WithNoTimeLeftItCouldntBeMeasured() {
		RenderDistancePlanner planner = incompleteAt32ThenPassesBelow();
		assertEquals("In progress", planner.result().reason(), "32 is still to be measured again");
		// BenchmarkSession ends the stage: the deadline left no time for the second try.
		planner.finish();
		PlannerResult result = planner.result();
		assertTrue(result.targetMet());
		assertEquals(31, result.bestRd());
		assertTrue(result.reason().contains("32 couldn't be measured"), result.reason());
		assertFalse(result.reason().contains("does not"), result.reason());
		PlannerResult.Measurement at32 = result.measurements().stream().filter(m -> m.rd() == 32).findFirst().orElseThrow();
		assertFalse(at32.passed());
		assertFalse(at32.complete());
	}

	@Test
	void rw5AnIncompleteStepIsMeasuredAgainOnlyOnce() {
		RenderDistancePlanner planner = incompleteAt32ThenPassesBelow();
		assertEquals(OptionalInt.of(32), planner.next());
		planner.record(32, low(36), false);
		assertTrue(planner.done());
		assertEquals(31, planner.result().bestRd());
		assertTrue(planner.result().reason().contains("32 couldn't be measured"), planner.result().reason());
	}

	// Review (part 1 M1): with no step measured on loaded terrain, nothing is suggested but the distance it started from.
	@Test
	void rw5NothingMeasuredSuggestsTheStart() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 12, 100, 2);
		planner.record(12, low(400), false);
		planner.record(6, low(500), false);
		planner.finish();
		PlannerResult result = planner.result();
		assertFalse(result.targetMet());
		assertEquals(12, result.suggestedRd());
		assertTrue(result.reason().startsWith("Nothing could be measured"), result.reason());
	}

	// Review (part 1 L5): below target without reaching the step limit is not "Step limit reached".
	@Test
	void rw5NoMeasuredDistanceMeetsTheTarget() {
		RenderDistancePlanner planner = new RenderDistancePlanner(4, MAX, 8, 100, 6);
		planner.record(8, low(50));
		assertEquals(OptionalInt.of(5), planner.next());
		planner.record(5, low(60));
		assertEquals(OptionalInt.of(4), planner.next());
		planner.record(4, low(90), false);
		assertEquals(OptionalInt.of(4), planner.next(), "measured again once");
		planner.record(4, low(90), false);
		assertTrue(planner.done());
		planner.finish();
		assertEquals("No measured distance meets the target; 4 couldn't be measured (its terrain hadn't loaded)", planner.result().reason());
	}

	// Review (part 1 L7): after a re-measure passes, the climb goes on in small steps from the distance it started at.
	@Test
	void rw5TheClimbAfterAPassingRemeasureStartsSmall() {
		RenderDistancePlanner planner = new RenderDistancePlanner(4, MAX, 16, 100, 10);
		planner.record(16, low(300), false);
		for (int expected : new int[]{9, 12, 14, 15}) {
			assertEquals(OptionalInt.of(expected), planner.next());
			planner.record(expected, low(300));
		}
		assertEquals(OptionalInt.of(16), planner.next());
		planner.record(16, low(300));
		assertEquals(OptionalInt.of(18), planner.next());
	}

	// An incomplete step below a pass can't change the answer: it isn't measured again, and the search above it goes on.
	@Test
	void rw5AnIncompleteStepBelowAPassIsntMeasuredAgain() {
		RenderDistancePlanner planner = new RenderDistancePlanner(MIN, MAX, 8, 100, 6);
		planner.record(8, low(150));
		planner.record(10, low(150), false);
		planner.record(14, low(120));
		assertEquals(OptionalInt.of(22), planner.next(), "everything complete passed: the climb goes on above 14");
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
		assertEquals(16, onlyIncomplete.result().bestEffortRd(), "the start (review M1: never an unmeasured distance)");
	}
}
