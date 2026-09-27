package io.github.chaotix345.rigtune.core.footprint;

import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Run;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend.Assessment;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend.Cause;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 9 (C18, AC9.1): the launch-time trend. Comparable = the same MC version; the baseline is the newest 10
// comparable runs before the latest, from 5 of them; the floor is BenchmarkTrend's (at least 10 %); SLOWER only past it;
// the cause against the newest comparable run before the latest, first match only.
class StartupTrendTest {
	private static int day;

	private static Run run(long ms) {
		return run(ms, 80, "h", "26.2", "0.5.0");
	}

	private static Run run(long ms, int mods, String hash, String mc, String rigtune) {
		day++;
		return new Run(String.format("2026-09-%02dT%02d:00:00Z", 1 + day % 28, day % 24), ms, mc, rigtune, mods, hash);
	}

	// `count` launches of 10 s, then `latest`.
	private static List<Run> steady(int count, Run latest) {
		List<Run> runs = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			runs.add(run(10_000));
		}
		runs.add(latest);
		return runs;
	}

	@Test
	void noRunsIsNoRun() {
		Assessment a = StartupTrend.assess(List.of());
		assertEquals(Kind.NO_RUN, a.kind());
		assertNull(a.latest());
		assertEquals(Cause.NONE, a.cause());
	}

	@Test
	void fewerThanFiveComparableRunsIsTooFewWithNoCause() {
		Assessment a = StartupTrend.assess(steady(4, run(30_000, 120, "other", "26.2", "0.6.0")));
		assertEquals(Kind.TOO_FEW, a.kind());
		assertEquals(4, a.baselineRuns());
		assertEquals(Cause.NONE, a.cause());
		assertNull(a.medianMs());
		assertNull(a.deltaPercent());
		assertNull(a.previous());
		assertEquals(30_000, a.latest().ms());
		assertEquals(Kind.SLOWER, StartupTrend.assess(steady(5, run(30_000))).kind(), "five is enough");
	}

	@Test
	void runsOfAnotherMinecraftVersionNeverEnterTheBaseline() {
		List<Run> runs = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			runs.add(run(10_000));
		}
		for (int i = 0; i < 6; i++) {
			runs.add(run(40_000, 80, "h", "26.3", "0.5.0"));
		}
		runs.add(run(30_000));
		Assessment a = StartupTrend.assess(runs);
		assertEquals(Kind.TOO_FEW, a.kind(), "only four 26.2 runs");
		assertEquals(4, a.baselineRuns());

		runs.add(run(10_000));
		Assessment back = StartupTrend.assess(runs);
		assertEquals(Kind.IN_LINE, back.kind(), "10 s against the five 26.2 runs (median 10 s), not the 26.3 ones");
		assertEquals(5, back.baselineRuns());
		runs.add(run(41_000, 80, "h", "26.3", "0.5.0"));
		Assessment other = StartupTrend.assess(runs);
		assertEquals(Kind.IN_LINE, other.kind(), "a 26.3 launch against the six 26.3 runs of 40 s");
		assertEquals(6, other.baselineRuns());
		assertEquals(40_000, other.medianMs(), 1e-9);
	}

	@Test
	void aLaunchOfAnUnknownVersionHasNoBaseline() {
		Assessment a = StartupTrend.assess(steady(8, run(30_000, 80, "h", null, "0.5.0")));
		assertEquals(Kind.TOO_FEW, a.kind());
		assertEquals(0, a.baselineRuns());
	}

	@Test
	void withinTheFloorIsInLineAndPastItSlowerOrAnImprovement() {
		Assessment inLine = StartupTrend.assess(steady(6, run(10_900)));
		assertEquals(Kind.IN_LINE, inLine.kind());
		assertEquals(10_000, inLine.medianMs());
		assertEquals(9.0, inLine.deltaPercent(), 1e-9);
		assertEquals(10.0, inLine.floorPercent(), 1e-9);
		assertEquals(Kind.IN_LINE, StartupTrend.assess(steady(6, run(11_000))).kind(), "exactly at the floor is not more than it");
		assertEquals(Kind.SLOWER, StartupTrend.assess(steady(6, run(11_001))).kind());
		Assessment slower = StartupTrend.assess(steady(6, run(14_500)));
		assertEquals(Kind.SLOWER, slower.kind());
		assertEquals(45.0, slower.deltaPercent(), 1e-9);
		assertEquals(6, slower.baselineRuns());
		assertEquals(Kind.IN_LINE, StartupTrend.assess(steady(6, run(9_000))).kind());
		Assessment faster = StartupTrend.assess(steady(6, run(8_000)));
		assertEquals(Kind.IMPROVEMENT, faster.kind());
		assertEquals(-20.0, faster.deltaPercent(), 1e-9);
	}

	@Test
	void theBaselineIsTheNewestTenComparableRunsBeforeTheLatest() {
		List<Run> runs = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			runs.add(run(40_000));
		}
		for (int i = 0; i < 10; i++) {
			runs.add(run(10_000));
		}
		Run latest = run(10_500);
		runs.add(latest);
		assertEquals(10, StartupTrend.baseline(runs).size());
		assertTrue(StartupTrend.baseline(runs).stream().allMatch(r -> r.ms() == 10_000), "the five 40 s runs are older than the newest ten");
		Assessment a = StartupTrend.assess(runs);
		assertEquals(Kind.IN_LINE, a.kind());
		assertEquals(10, a.baselineRuns());
		assertSame(latest, a.latest());
		assertSame(runs.get(runs.size() - 2), a.previous());
	}

	@Test
	void anAllIdenticalBaselineDoesNotDivideByZero() {
		Assessment a = StartupTrend.assess(steady(7, run(10_000)));
		assertEquals(Kind.IN_LINE, a.kind());
		assertEquals(0.0, a.deltaPercent(), 1e-9);
		assertEquals(10.0, a.floorPercent(), 1e-9, "MAD 0: the 5 % default, doubled");
	}

	@Test
	void oneExtremeOutlierInTheBaselineLeavesTheFloorAlone() {
		List<Run> runs = new ArrayList<>(List.of(run(10_000), run(10_100), run(9_900), run(10_050), run(60_000), run(9_950)));
		runs.add(run(11_500));
		Assessment a = StartupTrend.assess(runs);
		assertEquals(10.0, a.floorPercent(), 1e-9, "the 60 s launch doesn't widen the floor");
		assertEquals(10_025, a.medianMs(), 1e-9, "nor moves the median much");
		assertEquals(Kind.SLOWER, a.kind());
	}

	@Test
	void aNoisyBaselineRaisesTheFloor() {
		List<Run> runs = new ArrayList<>(List.of(run(10_000), run(12_000), run(8_000), run(11_000), run(9_000)));
		runs.add(run(12_500));
		Assessment a = StartupTrend.assess(runs);
		assertEquals(2 * 1.4826 * 1000 / 10_000 * 100, a.floorPercent(), 1e-9);
		assertEquals(Kind.IN_LINE, a.kind(), "25 % slower is inside a 29.7 % floor");
	}

	@Test
	void theCauseIsTheFirstMatchAgainstTheNewestComparableRunBefore() {
		assertEquals(Cause.MOD_COUNT, StartupTrend.assess(steady(5, run(20_000, 95, "other", "26.2", "0.6.0"))).cause(), "count before hash and version");
		assertEquals(Cause.MOD_SET, StartupTrend.assess(steady(5, run(20_000, 80, "other", "26.2", "0.6.0"))).cause(), "hash before version");
		assertEquals(Cause.RIGTUNE_VERSION, StartupTrend.assess(steady(5, run(20_000, 80, "h", "26.2", "0.6.0"))).cause());
		assertEquals(Cause.NONE, StartupTrend.assess(steady(5, run(20_000))).cause());
		assertEquals(Cause.RIGTUNE_VERSION, StartupTrend.assess(steady(5, run(20_000, 80, null, "26.2", "0.6.0"))).cause(), "an unknown hash claims nothing");
		assertEquals(Cause.NONE, StartupTrend.assess(steady(5, run(20_000, 80, "h", "26.2", null))).cause(), "an unknown version claims nothing");
		assertEquals(Cause.NONE, StartupTrend.assess(steady(5, run(20_000, 0, "h", "26.2", "0.5.0"))).cause(), "no mod count recorded claims nothing");

		// Against the newest comparable run: a 26.3 launch in between with another mod set is skipped.
		List<Run> runs = steady(5, run(10_000, 99, "x", "26.3", "0.5.0"));
		runs.add(run(20_000));
		Assessment a = StartupTrend.assess(runs);
		assertEquals(Cause.NONE, a.cause());
		assertEquals("26.2", a.previous().mcVersion());
	}

	@Test
	void theLinesTheNoticeAndToolsShow() {
		Assessment modCount = StartupTrend.assess(steady(6, run(21_300, 95, "other", "26.2", "0.5.0")));
		assertEquals("Launch time 113% higher than usual (21.3 s vs your usual ~10.0 s)", StartupTrend.regression(modCount).english());
		assertEquals("May be related to your mod set changing (80 → 95 mods) since your last launch", StartupTrend.cause(modCount).english());
		assertEquals("May be related to your mod set changing since your last launch",
				StartupTrend.cause(StartupTrend.assess(steady(6, run(21_300, 80, "other", "26.2", "0.5.0")))).english());
		assertEquals("RigTune 0.5.0 → 0.6.0 since your last launch",
				StartupTrend.cause(StartupTrend.assess(steady(6, run(21_300, 80, "h", "26.2", "0.6.0")))).english());
		assertEquals("No change recorded since your last launch; possibly another program running, a cold disk cache, or a driver/OS update",
				StartupTrend.cause(StartupTrend.assess(steady(6, run(21_300)))).english());
		List<Run> runs = new ArrayList<>(List.of(run(14_470), run(14_530), run(14_500), run(14_480), run(14_520)));
		runs.add(run(16_049));
		assertEquals("Launch time 11% higher than usual (16.0 s vs your usual ~14.5 s)", StartupTrend.regression(StartupTrend.assess(runs)).english());
	}
}
