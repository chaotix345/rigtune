package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Assessment;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Current;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Difference;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC7.2: median and MAD on fixed arrays; the noise-floor truth table (below -> no alert; at/above ->
// alert; fewer than 3 comparable runs -> no alert whatever the delta); an improvement is never an alert. Plus the
// not-comparable case, the "needs a rerun" marker (B-L1) and the per-context view.
class BenchmarkTrendTest {
	private static final Current NOW = new Current("26.2", 12, 8, 2560, 1440, false, false, null, false, "hash-a");

	private static List<BenchmarkRecord> history(double... lows) {
		List<BenchmarkRecord> runs = new ArrayList<>();
		for (int i = 0; i < lows.length; i++) {
			runs.add(TrendFixtures.run("r" + i).at("2026-09-2" + i + "T10:00:00Z").low(lows[i]).build());
		}
		return runs;
	}

	private static Assessment assessLatest(double latestLow, Double latestCv, double... baseline) {
		List<BenchmarkRecord> runs = history(baseline);
		BenchmarkRecord latest = TrendFixtures.run("latest").at("2026-09-29T10:00:00Z").low(latestLow).cv(latestCv).build();
		runs.add(latest);
		return BenchmarkTrend.assess(latest, runs);
	}

	@Test
	void medianOfOddAndEvenCounts() {
		assertEquals(505, BenchmarkTrend.median(510, 490, 505));
		assertEquals(502.5, BenchmarkTrend.median(500, 510, 490, 505));
		assertEquals(42, BenchmarkTrend.median(42));
		assertThrows(IllegalArgumentException.class, BenchmarkTrend::median);
	}

	@Test
	void madIsTheMedianAbsoluteDeviation() {
		// median 502.5; deviations 2.5, 7.5, 12.5, 2.5 -> 5
		assertEquals(5, BenchmarkTrend.mad(500, 510, 490, 505));
		assertEquals(0, BenchmarkTrend.mad(500, 500, 500));
		assertEquals(100, BenchmarkTrend.mad(400, 500, 600));
	}

	@Test
	void theFloorIsTwiceTheLargerOfTheCvAndTheScaledMad() {
		assertEquals(4.0, BenchmarkTrend.noiseFloorPercent(0.02, 500, 500, 500), 1e-9);
		// No CV on the latest run: 5 %.
		assertEquals(10.0, BenchmarkTrend.noiseFloorPercent(null, 500, 500, 500), 1e-9);
		// The spread of the history wins: 1.4826 x 100 / 500.
		assertEquals(2 * 1.4826 * 100 / 500 * 100, BenchmarkTrend.noiseFloorPercent(0.02, 400, 500, 600), 1e-9);
	}

	@Test
	void theTruthTable() {
		// Baseline 500, 500, 500 (MAD 0), latest CV 2 % -> floor 4 %.
		assertEquals(Kind.IN_LINE, assessLatest(481, 0.02, 500, 500, 500).kind());
		assertEquals(Kind.REGRESSION, assessLatest(480, 0.02, 500, 500, 500).kind());
		assertEquals(Kind.REGRESSION, assessLatest(300, 0.02, 500, 500, 500).kind());
		assertEquals(Kind.IN_LINE, assessLatest(519, 0.02, 500, 500, 500).kind());
		assertEquals(Kind.IMPROVEMENT, assessLatest(520, 0.02, 500, 500, 500).kind());
		// Without a CV the floor is 10 %.
		assertEquals(Kind.IN_LINE, assessLatest(460, null, 500, 500, 500).kind());
		assertEquals(Kind.REGRESSION, assessLatest(450, null, 500, 500, 500).kind());
		// Fewer than 3 comparable runs: nothing is claimed, whatever the delta.
		assertEquals(Kind.TOO_FEW, assessLatest(100, 0.02, 500, 500).kind());
		assertEquals(Kind.TOO_FEW, assessLatest(100, 0.02).kind());
	}

	@Test
	void aRegressionIsAnchoredAtTheNewestComparableRunBeforeIt() {
		Assessment a = assessLatest(400, 0.02, 500, 520, 480, 510);
		assertEquals(Kind.REGRESSION, a.kind());
		assertEquals(4, a.baselineRuns());
		assertEquals(505.0, a.median());
		assertEquals(400.0, a.latestLow());
		assertEquals((400 - 505) / 505.0 * 100, a.deltaPercent(), 1e-9);
		BenchmarkTrend.Regression r = a.regression();
		assertNotNull(r);
		assertEquals("r3", r.baselineRunId());
		assertEquals(505.0, r.median());
		assertEquals("latest", a.latestRunId());
	}

	@Test
	void anImprovementIsNeverAnAlert() {
		Assessment a = assessLatest(900, 0.02, 500, 500, 500);
		assertEquals(Kind.IMPROVEMENT, a.kind());
		assertNull(a.regression());
		assertNull(assessLatest(490, 0.02, 500, 500, 500).regression());
	}

	@Test
	void onlyComparableRunsBeforeTheLatestCount() {
		List<BenchmarkRecord> runs = history(500, 500, 500);
		runs.add(1, TrendFixtures.run("rd16").rd(16).low(100).build());
		runs.add(TrendFixtures.run("empty").noResult().build());
		BenchmarkRecord latest = TrendFixtures.run("latest").low(400).hash("hash-b").build();
		runs.add(latest);
		// A later run doesn't count for an earlier one.
		runs.add(TrendFixtures.run("after").low(10).build());
		Assessment a = BenchmarkTrend.assess(latest, runs);
		assertEquals(Kind.REGRESSION, a.kind());
		assertEquals(3, a.baselineRuns());
		assertEquals(500.0, a.median());
	}

	@Test
	void theBaselineIsTheNewestTenComparableRuns() {
		double[] lows = new double[14];
		for (int i = 0; i < lows.length; i++) {
			lows[i] = i < 4 ? 100 : 500;
		}
		Assessment a = assessLatest(500, 0.02, lows);
		assertEquals(10, a.baselineRuns());
		assertEquals(500.0, a.median());
		assertEquals(Kind.IN_LINE, a.kind());
	}

	@Test
	void differentConditionsClaimNoDelta() {
		List<BenchmarkRecord> runs = new ArrayList<>(history(500, 500, 500));
		BenchmarkRecord latest = TrendFixtures.run("latest").low(300).rd(16).size(1920, 1080).build();
		runs.add(latest);
		Assessment a = BenchmarkTrend.assess(latest, runs);
		assertEquals(Kind.DIFFERENT_CONDITIONS, a.kind());
		assertEquals(List.of(Difference.RENDER_DISTANCE, Difference.RESOLUTION), a.differences());
		assertEquals("r2", a.baselineRunId());
		assertNull(a.deltaPercent());
		assertNull(a.median());
		assertNull(a.regression());
	}

	@Test
	void differentConditionsWithinTheNoiseSayNothing() {
		List<BenchmarkRecord> runs = new ArrayList<>(history(500));
		BenchmarkRecord latest = TrendFixtures.run("latest").low(495).rd(16).build();
		runs.add(latest);
		assertEquals(Kind.TOO_FEW, BenchmarkTrend.assess(latest, runs).kind());
	}

	@Test
	void aRunWithoutAResult() {
		BenchmarkRecord latest = TrendFixtures.run("latest").noResult().build();
		assertEquals(Kind.NO_RESULT, BenchmarkTrend.assess(latest, List.of(latest)).kind());
	}

	@Test
	void differencesNameEveryKey() {
		BenchmarkRecord base = TrendFixtures.run("a").build();
		assertEquals(List.of(), BenchmarkTrend.differences(base, TrendFixtures.run("b").hash("other").cursor("x").build()));
		assertEquals(List.of(Difference.MC_VERSION, Difference.SCENE, Difference.SIMULATION_DISTANCE),
				BenchmarkTrend.differences(base, TrendFixtures.run("b").mc("26.3").scene("CURRENT").sd(10).build()));
		assertEquals(List.of(Difference.FULLSCREEN, Difference.SHADERS, Difference.DISTANT_HORIZONS, Difference.PROTOCOL),
				BenchmarkTrend.differences(base, TrendFixtures.run("b").context(new BenchmarkRecord.Context(true, true, "p.zip", 2560, 1440, true, 2)).build()));
		BenchmarkRecord shaders = TrendFixtures.run("s").context(new BenchmarkRecord.Context(false, true, "p.zip", 2560, 1440, false, 1)).build();
		assertEquals(List.of(Difference.SHADER_PACK),
				BenchmarkTrend.differences(shaders, TrendFixtures.run("t").context(new BenchmarkRecord.Context(false, true, "q.zip", 2560, 1440, false, 1)).build()));
		assertEquals(List.of(Difference.NOT_RECORDED), BenchmarkTrend.differences(base, TrendFixtures.run("n").context(null).build()));
		assertEquals(List.of(), BenchmarkTrend.differences(TrendFixtures.run("m").context(null).build(), TrendFixtures.run("n").context(null).build()));
	}

	@Test
	void theContextKeyMatchesComparability() {
		BenchmarkRecord a = TrendFixtures.run("a").build();
		assertEquals(BenchmarkTrend.contextKey(a), BenchmarkTrend.contextKey(TrendFixtures.run("b").hash("x").cursor("y").low(1).build()));
		for (BenchmarkRecord other : List.of(TrendFixtures.run("c").rd(13).build(), TrendFixtures.run("d").size(2561, 1440).build(),
				TrendFixtures.run("e").context(null).build(), TrendFixtures.run("f").mc("26.3").build(),
				TrendFixtures.run("g").context(new BenchmarkRecord.Context(false, true, null, 2560, 1440, false, 1)).build())) {
			assertTrue(!BenchmarkTrend.contextKey(a).equals(BenchmarkTrend.contextKey(other)), other.id());
		}
	}

	@Test
	void staleMarkerResolutionWithinTenPercentIsTheSame() {
		BenchmarkRecord last = TrendFixtures.run("last").build();
		assertEquals(List.of(), BenchmarkTrend.stale(last, NOW));
		// 2560x1400 is 2.8 % fewer pixels; 2560x1300 is 9.7 %; 2560x1296 is exactly 10 %.
		assertEquals(List.of(), BenchmarkTrend.stale(last, size(2560, 1400)));
		assertEquals(List.of(), BenchmarkTrend.stale(last, size(2560, 1300)));
		assertEquals(List.of(Difference.RESOLUTION), BenchmarkTrend.stale(last, size(2560, 1296)));
		assertEquals(List.of(Difference.RESOLUTION), BenchmarkTrend.stale(last, size(1920, 1080)));
		assertEquals(List.of(Difference.RESOLUTION), BenchmarkTrend.stale(last, size(3840, 2160)));
	}

	private static Current size(int width, int height) {
		return new Current("26.2", 12, 8, width, height, false, false, null, false, "hash-a");
	}

	@Test
	void staleMarkerEveryOtherKey() {
		BenchmarkRecord last = TrendFixtures.run("last").build();
		assertEquals(List.of(Difference.MC_VERSION, Difference.RENDER_DISTANCE, Difference.SIMULATION_DISTANCE, Difference.FULLSCREEN,
						Difference.SHADERS, Difference.DISTANT_HORIZONS, Difference.MOD_SET),
				BenchmarkTrend.stale(last, new Current("26.3", 16, 10, 2560, 1440, true, true, "p.zip", true, "hash-b")));
		BenchmarkRecord shaders = TrendFixtures.run("s").context(new BenchmarkRecord.Context(false, true, "p.zip", 2560, 1440, false, 1)
				.withModSet("hash-a", null)).build();
		assertEquals(List.of(Difference.SHADER_PACK),
				BenchmarkTrend.stale(shaders, new Current("26.2", 12, 8, 2560, 1440, false, true, "q.zip", false, "hash-a")));
		assertEquals(List.of(), BenchmarkTrend.stale(shaders, new Current("26.2", 12, 8, 2560, 1440, false, true, "p.zip", false, "hash-a")));
	}

	@Test
	void staleMarkerOnlyComparesWhatWasRecorded() {
		// Unknown hash on either side: the mod set isn't claimed to differ.
		assertEquals(List.of(), BenchmarkTrend.stale(TrendFixtures.run("a").hash(null).build(), size(2560, 1440)));
		assertEquals(List.of(), BenchmarkTrend.stale(TrendFixtures.run("a").build(),
				new Current("26.2", 12, 8, 2560, 1440, false, false, null, false, null)));
		// No context (0.2.x): only MC version and the distances.
		BenchmarkRecord old = TrendFixtures.run("old").context(null).build();
		assertEquals(List.of(), BenchmarkTrend.stale(old, new Current("26.2", 12, 8, 640, 480, true, true, "p.zip", true, "x")));
		assertEquals(List.of(Difference.RENDER_DISTANCE), BenchmarkTrend.stale(old, new Current("26.2", 6, 8, 640, 480, true, true, "p.zip", true, "x")));
	}

	@Test
	void theViewShowsOneContextAndCountsTheRest() {
		List<BenchmarkRecord> runs = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			runs.add(TrendFixtures.run("a" + i).low(500).build());
		}
		runs.add(TrendFixtures.run("b0").rd(16).low(300).build());
		runs.add(TrendFixtures.run("b1").rd(16).low(310).build());
		runs.add(TrendFixtures.run("none").noResult().build());
		BenchmarkTrend.View latest = BenchmarkTrend.view(runs, null, NOW);
		String a = BenchmarkTrend.contextKey(runs.getFirst());
		String b = BenchmarkTrend.contextKey(runs.get(12));
		assertEquals(b, latest.contextKey());
		assertEquals(List.of(b, a), latest.contextKeys());
		assertEquals(List.of("b1", "a11"), latest.examples().stream().map(BenchmarkRecord::id).toList());
		assertEquals(2, latest.comparableRuns());
		assertEquals(12, latest.otherRuns());
		assertEquals(List.of("b0", "b1"), latest.points().stream().map(BenchmarkRecord::id).toList());
		assertEquals(Kind.TOO_FEW, latest.assessment().kind());
		assertEquals("b1", latest.last().id());
		assertEquals(List.of(Difference.RENDER_DISTANCE), latest.stale());

		BenchmarkTrend.View older = BenchmarkTrend.view(runs, a, NOW);
		assertEquals(a, older.contextKey());
		assertEquals(12, older.comparableRuns());
		assertEquals(2, older.otherRuns());
		assertEquals(10, older.points().size());
		assertEquals("a2", older.points().getFirst().id());
		assertEquals("a11", older.latest().id());
		assertEquals(Kind.IN_LINE, older.assessment().kind());
		assertEquals(500.0, older.median());
		assertEquals("b1", older.last().id());

		assertEquals(b, BenchmarkTrend.view(runs, "gone", null).contextKey());
		assertEquals(List.of(), BenchmarkTrend.view(runs, null, null).stale());
		assertEquals(BenchmarkTrend.View.EMPTY, BenchmarkTrend.view(List.of(TrendFixtures.run("x").noResult().build()), null, NOW));
	}
}
