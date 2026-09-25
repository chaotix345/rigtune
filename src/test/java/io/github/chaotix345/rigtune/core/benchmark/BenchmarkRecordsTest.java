package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Mode;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkSession.TuneLimits;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import org.junit.jupiter.api.Test;

import static io.github.chaotix345.rigtune.core.benchmark.FakeRig.low;
import static io.github.chaotix345.rigtune.core.benchmark.FakeRig.stats;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRecordsTest {
	private static final Knobs ORIGINAL = new Knobs(12, 12, true, true);

	private static SessionResult tuned() {
		BenchmarkSession session = BenchmarkSession.tune(ORIGINAL, new TuneLimits(4, 32, 100, true, 5), Timing.DEFAULT, 0);
		new FakeRig().drive(session, step -> {
			Knobs k = step.knobs();
			double l = 2000.0 / k.renderDistance() - (k.simulationDistance() - 5);
			return stats(l * 2 * (k.dhRendering() ? 0.9 : 1), l * (k.dhRendering() ? 0.9 : 1) * (k.shaders() ? 0.8 : 1));
		});
		return session.result();
	}

	private static SessionResult measured(double low) {
		BenchmarkSession session = BenchmarkSession.measure(ORIGINAL, 100, Timing.DEFAULT, 0);
		double[] lows = {low, low * 1.02};
		int[] i = {0};
		new FakeRig().drive(session, step -> low(lows[i[0]++]));
		return session.result();
	}

	private static BenchmarkRecord of(SessionResult r, BenchmarkRequest request, String phase) {
		return BenchmarkRecords.of(r, request, phase, "id", "2026-09-25T10:00:00Z", "0.2.0+mc26.2", "26.2", null, null);
	}

	@Test
	void phaseSingleForTuneAndUnpairedMeasure() {
		assertEquals(BenchmarkRecord.SINGLE, BenchmarkRecords.phase(BenchmarkRequest.DEFAULT, BenchmarkHistory.empty()));
		assertEquals(BenchmarkRecord.SINGLE, BenchmarkRecords.phase(new BenchmarkRequest(Mode.MEASURE, Scene.CURRENT, null), BenchmarkHistory.empty()));
	}

	@Test
	void phaseBeforeThenAfter() {
		BenchmarkRequest request = new BenchmarkRequest(Mode.MEASURE, Scene.CURRENT, "p1");
		assertEquals(BenchmarkRecord.BEFORE, BenchmarkRecords.phase(request, BenchmarkHistory.empty()));
		BenchmarkHistory history = BenchmarkHistory.empty().with(of(measured(100), request, BenchmarkRecord.BEFORE));
		assertEquals(BenchmarkRecord.AFTER, BenchmarkRecords.phase(request, history));
	}

	@Test
	void tuneRecordHasRdAndSdKnobs() {
		SessionResult r = tuned();
		BenchmarkRecord rec = of(r, BenchmarkRequest.DEFAULT, BenchmarkRecord.SINGLE);
		assertEquals("TUNE", rec.mode());
		assertEquals("CURRENT", rec.scene());
		assertEquals(BenchmarkRecord.SINGLE, rec.phase());
		assertNull(rec.pairId());
		assertEquals(100, rec.targetFps());
		BenchmarkRecord.KnobResult rd = rec.knobs().get(BenchmarkRecord.RENDER_DISTANCE);
		assertEquals(r.chosen().renderDistance(), rd.value());
		assertEquals(12, rd.original());
		assertNotNull(rd.onePercentLowFps());
		BenchmarkRecord.KnobResult sd = rec.knobs().get(BenchmarkRecord.SIMULATION_DISTANCE);
		assertEquals(r.chosen().simulationDistance(), sd.value());
		assertEquals(12, sd.original());
		assertEquals(r.targetMet(), rec.targetMet());
	}

	// The RD steps ran at the original simulation distance, so a lowered SD must not hide their stats.
	@Test
	void rdStatsKeptWhenSdWasLowered() {
		BenchmarkSession session = BenchmarkSession.tune(ORIGINAL, new TuneLimits(4, 32, 100, true, 5), Timing.DEFAULT, 0);
		new FakeRig().drive(session, step -> step.kind() != Step.Kind.SIMULATION_DISTANCE ? low(2000.0 / step.knobs().renderDistance())
				: low(step.knobs().simulationDistance() == 12 ? 50 : 100), s -> 1);
		SessionResult r = session.result();
		assertTrue(r.chosen().simulationDistance() < 12, "SD lowered: " + r.chosen());
		BenchmarkRecord rec = of(r, BenchmarkRequest.DEFAULT, BenchmarkRecord.SINGLE);
		assertEquals(2000.0 / r.chosen().renderDistance(), rec.knobs().get(BenchmarkRecord.RENDER_DISTANCE).onePercentLowFps(), 1e-9);
		assertEquals(100, rec.knobs().get(BenchmarkRecord.SIMULATION_DISTANCE).onePercentLowFps(), 1e-9);
	}

	@Test
	void measureRecordHasResultAndCv() {
		SessionResult r = measured(100);
		BenchmarkRecord rec = of(r, new BenchmarkRequest(Mode.MEASURE, Scene.BENCHMARK_WORLD, "p9"), BenchmarkRecord.BEFORE);
		assertEquals("MEASURE", rec.mode());
		assertEquals("BENCHMARK_WORLD", rec.scene());
		assertEquals("p9", rec.pairId());
		assertNotNull(rec.result());
		assertEquals(101, rec.result().onePercentLowFps(), 1e-9);
		assertEquals(2, rec.result().repeats());
		assertEquals(BenchmarkMath.cv(100, 102), rec.result().cv());
		assertEquals(12, rec.knobs().get(BenchmarkRecord.RENDER_DISTANCE).value());
		assertTrue(rec.costs().isEmpty());
	}

	@Test
	void costsRecorded() {
		BenchmarkRecord rec = of(tuned(), BenchmarkRequest.DEFAULT, BenchmarkRecord.SINGLE);
		BenchmarkRecord.Cost dh = rec.costs().get(BenchmarkRecord.DISTANT_HORIZONS);
		BenchmarkRecord.Cost shaders = rec.costs().get(BenchmarkRecord.SHADERS);
		assertNotNull(dh);
		assertNotNull(shaders);
		assertTrue(dh.offOnePercentLowFps() > dh.baselineOnePercentLowFps());
		assertTrue(shaders.offOnePercentLowFps() > shaders.baselineOnePercentLowFps());
	}

	@Test
	void notMeasuredReportsRecorded() {
		BenchmarkSession session = BenchmarkSession.tune(ORIGINAL, new TuneLimits(4, 32, 100, false, 5), Timing.DEFAULT, 0);
		java.util.Optional<Step> next;
		while ((next = session.next(0)).isPresent()) {
			Step step = next.get();
			if (step.kind() == Step.Kind.DH_OFF) {
				session.skipFailed(step, "failed: DH refused");
				continue;
			}
			session.record(step, low(100));
		}
		BenchmarkRecord rec = of(session.result(), BenchmarkRequest.DEFAULT, BenchmarkRecord.SINGLE);
		assertEquals(java.util.Map.of(BenchmarkRecord.DISTANT_HORIZONS, "failed: DH refused"), rec.notMeasured());
		assertNull(rec.costs().get(BenchmarkRecord.DISTANT_HORIZONS));
		assertNotNull(rec.costs().get(BenchmarkRecord.SHADERS));
	}

	@Test
	void summaryUsesResultAndChosenRd() {
		SessionResult r = tuned();
		BenchmarkRecord rec = of(r, BenchmarkRequest.DEFAULT, BenchmarkRecord.SINGLE);
		BenchmarkSummary summary = BenchmarkRecords.summary(rec);
		assertEquals("2026-09-25T10:00:00Z", summary.at());
		assertEquals("TUNE", summary.mode());
		assertEquals("CURRENT", summary.scene());
		assertEquals(100, summary.targetFps());
		assertEquals(r.chosen().renderDistance(), summary.renderDistance());
		assertEquals(rec.result().avgFps(), summary.avgFps());
		assertEquals(rec.result().onePercentLowFps(), summary.onePercentLowFps());
		assertEquals(rec.targetMet(), summary.targetMet());
	}

	@Test
	void gainBetweenPair() {
		BenchmarkRequest request = new BenchmarkRequest(Mode.MEASURE, Scene.CURRENT, "p1");
		BenchmarkRecord before = of(measured(100), request, BenchmarkRecord.BEFORE);
		BenchmarkRecord after = of(measured(130), request, BenchmarkRecord.AFTER);
		BenchmarkMath.Gain gain = BenchmarkRecords.gain(before, after);
		assertNotNull(gain);
		assertEquals(30, gain.lowPercent(), 1e-9);
		assertTrue(gain.significant());
		BenchmarkMath.Gain none = BenchmarkRecords.gain(before, of(measured(101), request, BenchmarkRecord.AFTER));
		assertNotNull(none);
		assertFalse(none.significant());
	}

	@Test
	void gainNeedsBothResults() {
		BenchmarkRecord noResult = new BenchmarkRecord("e", "t", "v", "26.2", "MEASURE", "CURRENT", BenchmarkRecord.AFTER, "p", 60, false,
				java.util.Map.of(), null, java.util.Map.of(), java.util.Map.of(), null, false);
		BenchmarkRecord before = of(measured(100), new BenchmarkRequest(Mode.MEASURE, Scene.CURRENT, "p"), BenchmarkRecord.BEFORE);
		assertNull(BenchmarkRecords.gain(before, noResult));
	}
	@Test
	void theContextIsKeptOnTheRecord() {
		BenchmarkRecord.Context context = new BenchmarkRecord.Context(true, true, "Complementary.zip", 2560, 1440, true, BenchmarkRecord.Context.PROTOCOL);
		BenchmarkRecord record = BenchmarkRecords.of(tuned(), new BenchmarkRequest(Mode.TUNE, Scene.CURRENT, null), BenchmarkRecord.SINGLE, "id",
				"2026-09-25T10:00:00Z", "0.3.0+mc26.2", "26.2", null, context);
		assertEquals(context, record.context());
		assertNull(of(tuned(), new BenchmarkRequest(Mode.TUNE, Scene.CURRENT, null), BenchmarkRecord.SINGLE).context());
	}
}
