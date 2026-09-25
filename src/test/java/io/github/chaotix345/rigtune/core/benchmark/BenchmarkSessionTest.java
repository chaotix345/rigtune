package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkSession.TuneLimits;
import io.github.chaotix345.rigtune.core.benchmark.Step.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;

import static io.github.chaotix345.rigtune.core.benchmark.FakeRig.low;
import static io.github.chaotix345.rigtune.core.benchmark.FakeRig.stats;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkSessionTest {
	private static final Knobs ORIGINAL = new Knobs(12, 12, false, false);
	private static final TuneLimits LIMITS = new TuneLimits(4, 32, 100, true, 5);

	// 1% low falls with render distance and (a little) with simulation distance; DH costs 10%, shaders 30%.
	private static FrameStats model(Step step) {
		Knobs k = step.knobs();
		double low = 2000.0 / k.renderDistance() - (k.simulationDistance() - 5);
		if (k.dhRendering()) {
			low *= 0.9;
		}
		if (k.shaders()) {
			low *= 0.7;
		}
		return stats(low * 2, low);
	}

	private static BenchmarkSession tune(Knobs original, TuneLimits limits) {
		return BenchmarkSession.tune(original, limits, Timing.DEFAULT, 0);
	}

	private static List<Kind> distinctKinds(List<Kind> kinds) {
		List<Kind> out = new ArrayList<>();
		for (Kind k : kinds) {
			if (out.isEmpty() || out.getLast() != k) {
				out.add(k);
			}
		}
		return out;
	}

	@Test
	void rdComesFirstThenSdThenARepeatThenCostsThenTheLastRepeat() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(new Knobs(12, 12, true, true), new TuneLimits(4, 32, 20, true, 5));
		rig.drive(session, BenchmarkSessionTest::model, s -> 1);
		assertEquals(List.of(Kind.RENDER_DISTANCE, Kind.SIMULATION_DISTANCE, Kind.REPEAT, Kind.DH_OFF, Kind.SHADERS_OFF, Kind.REPEAT),
				distinctKinds(rig.kinds()));
		assertTrue(session.done());
	}

	// With worst-case steps the second repeat is what gets cut, not the cost reports.
	@Test
	void worstCaseKeepsTheCostReports() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(new Knobs(12, 12, true, true), new TuneLimits(4, 32, 20, true, 5));
		rig.drive(session, BenchmarkSessionTest::model);
		assertEquals(List.of(Kind.RENDER_DISTANCE, Kind.SIMULATION_DISTANCE, Kind.REPEAT, Kind.DH_OFF, Kind.SHADERS_OFF),
				distinctKinds(rig.kinds()));
		assertTrue(session.result().deadlineHit());
		assertNotNull(session.result().dhCost());
		assertNotNull(session.result().shaderCost());
	}

	@Test
	void rdSearchMatchesThePlanner() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		rig.drive(session, BenchmarkSessionTest::model);
		List<Integer> sessionRds = rig.steps.stream().filter(s -> s.kind() == Kind.RENDER_DISTANCE).map(s -> s.knobs().renderDistance()).toList();

		RenderDistancePlanner planner = new RenderDistancePlanner(4, 32, 12, 100, 6);
		List<Integer> plannerRds = new ArrayList<>();
		OptionalInt rd;
		while ((rd = planner.next()).isPresent()) {
			plannerRds.add(rd.getAsInt());
			planner.record(rd.getAsInt(), model(new Step(Kind.RENDER_DISTANCE, ORIGINAL.withRenderDistance(rd.getAsInt()), Timing.DEFAULT.full())));
		}
		assertEquals(plannerRds, sessionRds);
		assertEquals(planner.result().suggestedRd(), session.result().chosen().renderDistance());
		assertTrue(rig.steps.stream().filter(s -> s.kind() == Kind.RENDER_DISTANCE)
				.allMatch(s -> s.protocol().equals(Timing.DEFAULT.full()) && s.knobs().simulationDistance() == 12));
	}

	@Test
	void sdSkippedWhenNotTunable() {
		FakeRig rig = new FakeRig();
		rig.drive(tune(ORIGINAL, new TuneLimits(4, 32, 100, false, 5)), BenchmarkSessionTest::model);
		assertFalse(rig.kinds().contains(Kind.SIMULATION_DISTANCE));
	}

	@Test
	void sdSkippedAtMinimum() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(new Knobs(12, 5, false, false), LIMITS);
		rig.drive(session, BenchmarkSessionTest::model);
		assertFalse(rig.kinds().contains(Kind.SIMULATION_DISTANCE));
		assertEquals(5, session.result().chosen().simulationDistance());
	}

	@Test
	void sdStopsAtFirstThatMeetsTarget() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		rig.drive(session, step -> step.kind() == Kind.SIMULATION_DISTANCE ? low(150) : model(step));
		List<Step> sd = rig.steps.stream().filter(s -> s.kind() == Kind.SIMULATION_DISTANCE).toList();
		assertEquals(1, sd.size());
		assertEquals(12, sd.getFirst().knobs().simulationDistance());
		assertEquals(Timing.DEFAULT.quick().sweeps(), sd.getFirst().protocol().sweeps());
		assertEquals(12, session.result().chosen().simulationDistance());
	}

	@Test
	void sdLowersWhenLowerMeetsTarget() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		rig.drive(session, step -> step.kind() != Kind.SIMULATION_DISTANCE ? model(step)
				: low(step.knobs().simulationDistance() <= 10 ? 100 : 90));
		assertEquals(List.of(12, 10), rig.steps.stream().filter(s -> s.kind() == Kind.SIMULATION_DISTANCE)
				.map(s -> s.knobs().simulationDistance()).toList());
		assertEquals(10, session.result().chosen().simulationDistance());
	}

	@Test
	void sdKeepsCurrentWhenGainBelowFivePercent() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		rig.drive(session, step -> step.kind() != Kind.SIMULATION_DISTANCE ? model(step)
				: low(switch (step.knobs().simulationDistance()) {
					case 12 -> 80;
					case 10 -> 83;
					default -> 83.9;
				}));
		assertEquals(3, rig.steps.stream().filter(s -> s.kind() == Kind.SIMULATION_DISTANCE).count());
		assertEquals(12, session.result().chosen().simulationDistance());
	}

	@Test
	void sdPicksBestWhenGainAtLeastFivePercent() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		rig.drive(session, step -> step.kind() != Kind.SIMULATION_DISTANCE ? model(step)
				: low(switch (step.knobs().simulationDistance()) {
					case 12 -> 80;
					case 10 -> 84;
					default -> 83;
				}));
		assertEquals(10, session.result().chosen().simulationDistance());
	}

	@Test
	void sdCandidatesClampedToMinimum() {
		FakeRig rig = new FakeRig();
		rig.drive(tune(new Knobs(12, 8, false, false), LIMITS), step -> step.kind() == Kind.SIMULATION_DISTANCE ? low(10) : model(step));
		assertEquals(List.of(8, 6), rig.steps.stream().filter(s -> s.kind() == Kind.SIMULATION_DISTANCE)
				.map(s -> s.knobs().simulationDistance()).toList());
	}

	// The chosen render distance usually isn't the last one measured, so its chunk sections have to rebuild first.
	@Test
	void sdStepAfterAnRdChangeSettlesFully() {
		FakeRig rig = new FakeRig();
		rig.drive(tune(ORIGINAL, LIMITS), step -> step.kind() == Kind.SIMULATION_DISTANCE ? low(10) : model(step));
		List<Step> rd = rig.steps.stream().filter(s -> s.kind() == Kind.RENDER_DISTANCE).toList();
		List<Step> sd = rig.steps.stream().filter(s -> s.kind() == Kind.SIMULATION_DISTANCE).toList();
		assertTrue(rd.getLast().knobs().renderDistance() != sd.getFirst().knobs().renderDistance());
		assertEquals(Timing.DEFAULT.quickSettled(), sd.getFirst().protocol());
		assertTrue(sd.subList(1, sd.size()).stream().allMatch(s -> s.protocol().equals(Timing.DEFAULT.quick())), "only the render distance change needs it");
	}

	@Test
	void sdStepAtTheSameRenderDistanceUsesTheQuickSettle() {
		FakeRig rig = new FakeRig();
		// Everything passes at the cap, so the search ends on the render distance it chooses.
		rig.drive(tune(new Knobs(32, 12, false, false), new TuneLimits(4, 32, 1, true, 5)), step -> step.kind() == Kind.SIMULATION_DISTANCE ? low(0.5) : low(100));
		List<Step> sd = rig.steps.stream().filter(s -> s.kind() == Kind.SIMULATION_DISTANCE).toList();
		assertEquals(32, sd.getFirst().knobs().renderDistance());
		assertTrue(sd.stream().allMatch(s -> s.protocol().equals(Timing.DEFAULT.quick())));
	}

	@Test
	void shadersOffSettlesFullyAndDhOffDoesNot() {
		FakeRig rig = new FakeRig();
		rig.drive(tune(new Knobs(12, 12, true, true), new TuneLimits(4, 32, 20, false, 5)), BenchmarkSessionTest::model);
		Step dh = rig.steps.stream().filter(s -> s.kind() == Kind.DH_OFF).findFirst().orElseThrow();
		Step shaders = rig.steps.stream().filter(s -> s.kind() == Kind.SHADERS_OFF).findFirst().orElseThrow();
		assertEquals(Timing.DEFAULT.quick(), dh.protocol());
		assertEquals(Timing.DEFAULT.quickSettled(), shaders.protocol());
	}

	@Test
	void sdMeasuredAtTheChosenRenderDistance() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		rig.drive(session, step -> step.kind() == Kind.SIMULATION_DISTANCE ? low(10) : model(step));
		int rd = session.result().chosen().renderDistance();
		assertTrue(rig.steps.stream().filter(s -> s.kind() == Kind.SIMULATION_DISTANCE).allMatch(s -> s.knobs().renderDistance() == rd));
	}

	@Test
	void repeatsAggregateWithCv() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(ORIGINAL, new TuneLimits(4, 32, 100, false, 5));
		int[] repeat = {0};
		rig.drive(session, step -> step.kind() != Kind.REPEAT ? model(step) : stats(300, repeat[0]++ == 0 ? 100 : 110), s -> 1);
		List<Step> repeats = rig.steps.stream().filter(s -> s.kind() == Kind.REPEAT).toList();
		assertEquals(2, repeats.size());
		assertTrue(repeats.stream().allMatch(s -> s.knobs().equals(session.result().chosen()) && s.protocol().equals(Timing.DEFAULT.full())));
		BenchmarkMath.Aggregate result = session.result().result();
		assertNotNull(result);
		assertEquals(105, result.onePercentLowFps(), 1e-9);
		assertEquals(2, result.repeats());
		assertEquals(BenchmarkMath.cv(100, 110), result.cv());
	}

	@Test
	void resultFallsBackToRdMeasurementWithoutRepeats() {
		FakeRig rig = new FakeRig();
		Timing noRepeats = new Timing(6, 8.0, 2.0, 20.0, 1.5, 6.0, 2.0, 0, 300.0);
		BenchmarkSession session = BenchmarkSession.tune(ORIGINAL, new TuneLimits(4, 32, 100, false, 5), noRepeats, 0);
		rig.drive(session, BenchmarkSessionTest::model);
		BenchmarkMath.Aggregate result = session.result().result();
		assertNotNull(result);
		assertEquals(1, result.repeats());
		assertNull(result.cv());
		int rd = session.result().chosen().renderDistance();
		assertEquals(2000.0 / rd - 7, result.onePercentLowFps(), 1e-9);
	}

	@Test
	void costReportsOnlyForActiveFeatures() {
		FakeRig rig = new FakeRig();
		rig.drive(tune(new Knobs(12, 12, false, true), LIMITS), BenchmarkSessionTest::model);
		assertFalse(rig.kinds().contains(Kind.DH_OFF));
		assertTrue(rig.kinds().contains(Kind.SHADERS_OFF));
		FakeRig none = new FakeRig();
		none.drive(tune(ORIGINAL, LIMITS), BenchmarkSessionTest::model);
		assertFalse(none.kinds().contains(Kind.BASELINE));
		assertFalse(none.kinds().contains(Kind.SHADERS_OFF));
	}

	@Test
	void costBaselineReusesSdMeasurement() {
		FakeRig rig = new FakeRig();
		rig.drive(tune(new Knobs(12, 12, true, false), LIMITS), BenchmarkSessionTest::model);
		assertTrue(rig.kinds().contains(Kind.SIMULATION_DISTANCE));
		assertFalse(rig.kinds().contains(Kind.BASELINE));
		assertTrue(rig.kinds().contains(Kind.DH_OFF));
	}

	@Test
	void costBaselineMeasuredWithoutSdStep() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(new Knobs(12, 12, true, false), new TuneLimits(4, 32, 100, false, 5));
		rig.drive(session, BenchmarkSessionTest::model);
		int at = rig.kinds().indexOf(Kind.BASELINE);
		assertEquals(List.of(Kind.REPEAT, Kind.BASELINE, Kind.DH_OFF, Kind.REPEAT), rig.kinds().subList(at - 1, at + 3));
		Step baseline = rig.steps.get(at);
		assertEquals(session.result().chosen(), baseline.knobs());
		assertEquals(Timing.DEFAULT.quick(), baseline.protocol());
	}

	@Test
	void costComputedFromBaselineAndOff() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(new Knobs(12, 12, true, true), new TuneLimits(4, 32, 20, false, 5));
		rig.drive(session, BenchmarkSessionTest::model);
		SessionResult r = session.result();
		assertNotNull(r.dhCost());
		assertNotNull(r.shaderCost());
		assertEquals(100.0 / 9, r.dhCost().lowGainPercent(), 1e-6);
		assertEquals(100.0 / 0.7 - 100, r.shaderCost().lowGainPercent(), 1e-6);
		Step dh = rig.steps.stream().filter(s -> s.kind() == Kind.DH_OFF).findFirst().orElseThrow();
		assertEquals(r.chosen().withDhRendering(false), dh.knobs());
		Step shaders = rig.steps.stream().filter(s -> s.kind() == Kind.SHADERS_OFF).findFirst().orElseThrow();
		assertEquals(r.chosen().withShaders(false), shaders.knobs());
	}

	@Test
	void chosenKnobsNeverTurnDhOrShadersOff() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(new Knobs(12, 12, true, true), LIMITS);
		rig.drive(session, BenchmarkSessionTest::model);
		assertTrue(session.result().chosen().dhRendering());
		assertTrue(session.result().chosen().shaders());
	}

	@Test
	void measureModeRepeatsOriginal() {
		FakeRig rig = new FakeRig();
		Knobs original = new Knobs(10, 8, true, true);
		BenchmarkSession session = BenchmarkSession.measure(original, 100, Timing.DEFAULT, 0);
		rig.drive(session, BenchmarkSessionTest::model);
		assertEquals(List.of(Kind.REPEAT, Kind.REPEAT), rig.kinds());
		assertTrue(rig.steps.stream().allMatch(s -> s.knobs().equals(original) && s.protocol().equals(Timing.DEFAULT.full())));
		SessionResult r = session.result();
		assertEquals(original, r.chosen());
		assertEquals(BenchmarkRequest.Mode.MEASURE, r.mode());
		assertNotNull(r.result());
		assertNull(r.dhCost());
	}

	@Test
	void measureTargetMetFollowsResult() {
		BenchmarkSession met = BenchmarkSession.measure(ORIGINAL, 100, Timing.DEFAULT, 0);
		new FakeRig().drive(met, step -> low(120));
		assertTrue(met.result().targetMet());
		BenchmarkSession missed = BenchmarkSession.measure(ORIGINAL, 100, Timing.DEFAULT, 0);
		new FakeRig().drive(missed, step -> low(80));
		assertFalse(missed.result().targetMet());
	}

	// AC6.1 (M16): with every step taking its worst case, RD and SD finish inside the 5 min deadline.
	@Test
	void worstCaseRdAndSdFinishWithinDeadline() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(ORIGINAL, new TuneLimits(4, 32, 101, true, 5));
		// 2000/rd: 12, 14, 18 pass; 26, 22, 20 fail → six RD steps. SD candidates all miss the target → three SD steps.
		rig.drive(session, step -> step.kind() == Kind.SIMULATION_DISTANCE ? low(50) : low(2000.0 / step.knobs().renderDistance()));
		assertEquals(6, rig.steps.stream().filter(s -> s.kind() == Kind.RENDER_DISTANCE).count());
		assertEquals(3, rig.steps.stream().filter(s -> s.kind() == Kind.SIMULATION_DISTANCE).count());
		double rdAndSd = rig.steps.stream().filter(s -> s.kind() == Kind.RENDER_DISTANCE || s.kind() == Kind.SIMULATION_DISTANCE)
				.mapToDouble(s -> s.protocol().worstCaseSeconds()).sum();
		// The first SD step follows a render distance change, so it gets the full settle.
		assertEquals(6 * 37.5 + 27.5 + 2 * 9.5, rdAndSd, 1e-9);
		assertTrue(rdAndSd <= 300);
		assertTrue(rig.elapsedSeconds() <= 300, "elapsed " + rig.elapsedSeconds());
		assertTrue(session.result().deadlineHit(), "a worst-case repeat no longer fits");
		assertEquals(0, rig.steps.stream().filter(s -> s.kind() == Kind.REPEAT).count());
		assertNotNull(session.result().result(), "the result falls back to the RD measurement");
	}

	@Test
	void deadlineCutsTheTailNotTheWholeRun() {
		FakeRig rig = new FakeRig();
		Timing tight = new Timing(6, 8.0, 2.0, 20.0, 1.5, 6.0, 2.0, 2, 120.0);
		BenchmarkSession session = BenchmarkSession.tune(new Knobs(12, 12, true, false), LIMITS, tight, 0);
		rig.drive(session, BenchmarkSessionTest::model);
		assertTrue(session.result().deadlineHit());
		// 120 s minus the 10 s slack: two 37.5 s steps fit, a third would end at 112.5 s.
		assertEquals(2, rig.steps.stream().filter(s -> s.kind() == Kind.RENDER_DISTANCE).count());
		assertTrue(rig.elapsedSeconds() <= 120 - BenchmarkSession.SLACK_SECONDS);
		assertTrue(session.done());
		assertNotNull(session.result().result());
	}

	@Test
	void stepNeverStartsPastDeadline() {
		for (int deadline = 0; deadline <= 400; deadline += 7) {
			FakeRig rig = new FakeRig();
			Timing timing = new Timing(6, 8.0, 2.0, 20.0, 1.5, 6.0, 2.0, 2, deadline);
			BenchmarkSession session = BenchmarkSession.tune(new Knobs(12, 12, true, true), LIMITS, timing, 0);
			rig.drive(session, BenchmarkSessionTest::model);
			// The slack leaves room for knob changes and tick granularity, which the worst cases don't count.
			assertTrue(rig.maxStepEnd <= Math.max(0, deadline - BenchmarkSession.SLACK_SECONDS) * FakeRig.NANOS, "deadline " + deadline);
			assertTrue(session.done());
		}
	}

	@Test
	void noTimeForAnyStepKeepsOriginal() {
		FakeRig rig = new FakeRig();
		Timing none = new Timing(6, 8.0, 2.0, 20.0, 1.5, 6.0, 2.0, 2, 5);
		BenchmarkSession session = BenchmarkSession.tune(ORIGINAL, LIMITS, none, 0);
		rig.drive(session, BenchmarkSessionTest::model);
		assertTrue(rig.steps.isEmpty());
		assertEquals(ORIGINAL, session.result().chosen());
		assertNull(session.result().result());
		assertTrue(session.result().deadlineHit());
	}

	@Test
	void realDurationsUseLessThanTheWorstCase() {
		FakeRig rig = new FakeRig();
		BenchmarkSession session = tune(new Knobs(12, 12, true, true), LIMITS);
		// Settling takes 3 s instead of the timeout.
		Function<Step, FrameStats> source = BenchmarkSessionTest::model;
		rig.drive(session, source, s -> s.protocol().settleMinSeconds() + 1 + s.protocol().warmupSeconds() + s.protocol().measuredSeconds());
		assertFalse(session.result().deadlineHit());
		assertTrue(rig.kinds().contains(Kind.SHADERS_OFF));
	}

	@Test
	void reportCutByTheDeadlineSaysSo() {
		Timing tight = new Timing(6, 8.0, 2.0, 20.0, 1.5, 6.0, 2.0, 2, 5);
		BenchmarkSession session = BenchmarkSession.tune(new Knobs(12, 12, true, false), LIMITS, tight, 0);
		new FakeRig().drive(session, BenchmarkSessionTest::model);
		assertEquals(SessionResult.NOT_MEASURED_DEADLINE, session.result().notMeasured().get(BenchmarkRecord.DISTANT_HORIZONS));
		assertFalse(session.result().notMeasured().containsKey(BenchmarkRecord.SHADERS), "shaders weren't on");
	}

	@Test
	void worstCaseRdAndSdFitTheBudgetWithSlack() {
		assertTrue(6 * Timing.DEFAULT.full().worstCaseSeconds() + Timing.DEFAULT.quickSettled().worstCaseSeconds()
				+ 2 * Timing.DEFAULT.quick().worstCaseSeconds() <= Timing.DEFAULT.deadlineSeconds() - BenchmarkSession.SLACK_SECONDS);
	}

	@Test
	void failedReportIsSkippedWithItsReason() {
		BenchmarkSession session = tune(new Knobs(12, 12, true, true), new TuneLimits(4, 32, 20, false, 5));
		FakeRig rig = new FakeRig();
		java.util.Optional<Step> next;
		while ((next = session.next(rig.now)).isPresent()) {
			Step step = next.get();
			rig.steps.add(step);
			if (step.kind() == Kind.DH_OFF) {
				session.skipFailed(step, "failed: DH refused");
				continue;
			}
			session.record(step, model(step));
		}
		SessionResult r = session.result();
		assertNull(r.dhCost());
		assertNotNull(r.shaderCost(), "the other report still runs");
		assertEquals("failed: DH refused", r.notMeasured().get(BenchmarkRecord.DISTANT_HORIZONS));
		assertFalse(r.notMeasured().containsKey(BenchmarkRecord.SHADERS));
		assertEquals(2, rig.steps.stream().filter(s -> s.kind() == Kind.REPEAT).count());
	}

	@Test
	void failedBaselineSkipsBothReports() {
		BenchmarkSession session = tune(new Knobs(12, 12, true, true), new TuneLimits(4, 32, 20, false, 5));
		java.util.Optional<Step> next;
		while ((next = session.next(0)).isPresent()) {
			Step step = next.get();
			if (step.kind() == Kind.BASELINE) {
				session.skipFailed(step, "failed: x");
				continue;
			}
			session.record(step, model(step));
		}
		assertEquals("failed: x", session.result().notMeasured().get(BenchmarkRecord.DISTANT_HORIZONS));
		assertEquals("failed: x", session.result().notMeasured().get(BenchmarkRecord.SHADERS));
	}

	@Test
	void onlyReportStepsCanBeSkipped() {
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		Step first = session.next(0).orElseThrow();
		assertThrows(IllegalStateException.class, () -> session.skipFailed(first, "failed"));
	}

	@Test
	void nextReturnsSameStepUntilRecorded() {
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		Step first = session.next(0).orElseThrow();
		assertEquals(first, session.next(5 * FakeRig.NANOS).orElseThrow());
		session.record(first, low(150));
		assertFalse(first.equals(session.next(40 * FakeRig.NANOS).orElseThrow()));
	}

	@Test
	void recordingAnotherStepFails() {
		BenchmarkSession session = tune(ORIGINAL, LIMITS);
		Step first = session.next(0).orElseThrow();
		Step other = new Step(Kind.REPEAT, first.knobs(), first.protocol());
		assertThrows(IllegalStateException.class, () -> session.record(other, low(10)));
	}
}
