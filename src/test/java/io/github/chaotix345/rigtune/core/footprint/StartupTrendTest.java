package io.github.chaotix345.rigtune.core.footprint;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Run;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend.Assessment;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend.Cause;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 9 (C18, AC9.1): the launch-time trend. Comparable = the same MC version; the baseline is the newest 10
// comparable runs before the latest, from 5 of them; the floor is BenchmarkTrend's (at least 10 %); SLOWER only past it;
// the cause against the newest comparable run before the latest, first match only. RW-19: without the crash-report setup
// when recorded; review H1: a slow streak is one regression.
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

	@Test
	void oneKeyPerLaunchAndTheLogLine() {
		Run latest = new Run("2026-09-27T10:00:00Z", 14_500, "26.2", "0.5.0", 92, "h2");
		Assessment slower = StartupTrend.assess(steady(6, latest));
		assertEquals("startup.regression.2026-09-27T10:00:00Z", StartupTrend.key(slower));
		assertNull(StartupTrend.key(StartupTrend.assess(List.of())));
		assertEquals("SLOWER: +45.0 % vs the median 10.0 s of 6 comparable launches (floor 10.0 %); cause: MOD_COUNT", StartupTrend.describe(slower));
		assertEquals("IN_LINE: +0.0 % vs the median 10.0 s of 6 comparable launches (floor 10.0 %)", StartupTrend.describe(StartupTrend.assess(steady(6, run(10_000)))));
		assertEquals("too few comparable launches (2 of 5)", StartupTrend.describe(StartupTrend.assess(steady(2, run(10_000)))));
		assertEquals("no launch recorded", StartupTrend.describe(StartupTrend.assess(List.of())));
	}

	// Review H1: ten launches of 10 s, then 14.5 s from launch N on with 12 more mods. N is SLOWER with the mod count;
	// N+1..N+4 are the same regression (N's key, N's cause, "slower for your last k launches"); N+5 is in line again.
	@Test
	void aSlowStreakIsOneRegression() {
		List<Run> runs = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			runs.add(run(10_000, 80, "h", "26.2", "0.5.0"));
		}
		Run n = run(14_500, 92, "h2", "26.2", "0.5.0");
		runs.add(n);
		Assessment first = StartupTrend.assess(runs);
		assertEquals(Kind.SLOWER, first.kind());
		assertEquals(1, first.streak());
		assertSame(n, first.first());
		assertEquals(Cause.MOD_COUNT, first.cause());
		String key = StartupTrend.key(first);
		assertEquals("startup.regression." + n.at(), key);
		for (int k = 2; k <= 5; k++) {
			runs.add(run(14_500, 92, "h2", "26.2", "0.5.0"));
			Assessment a = StartupTrend.assess(runs);
			assertEquals(Kind.SLOWER, a.kind(), "N+" + (k - 1));
			assertEquals(k, a.streak());
			assertEquals(key, StartupTrend.key(a), "one Got it covers the streak");
			assertEquals(Cause.MOD_COUNT, a.cause(), "the cause before its first launch, though nothing changed since N");
			assertEquals("Slower for your last " + k + " launches; may be related to your mod set changing (80 → 92 mods) before the first of them",
					StartupTrend.cause(a).english());
		}
		runs.add(run(14_500, 92, "h2", "26.2", "0.5.0"));
		Assessment after = StartupTrend.assess(runs);
		assertEquals(Kind.IN_LINE, after.kind(), "half the baseline is at 14.5 s now: " + StartupTrend.describe(after));
		assertEquals(0, after.streak());
	}

	@Test
	void aStreakEndsAtAnInLineLaunchAndTheNextSlowdownIsANewOne() {
		List<Run> runs = steady(10, run(14_500, 92, "h2", "26.2", "0.5.0"));
		String earlier = StartupTrend.key(StartupTrend.assess(runs));
		runs.add(run(10_000, 92, "h2", "26.2", "0.5.0"));
		assertEquals(Kind.IN_LINE, StartupTrend.assess(runs).kind());
		Run again = run(15_000, 92, "h2", "26.2", "0.5.0");
		runs.add(again);
		Assessment a = StartupTrend.assess(runs);
		assertEquals(Kind.SLOWER, a.kind());
		assertEquals(1, a.streak());
		assertEquals("startup.regression." + again.at(), StartupTrend.key(a));
		assertTrue(!StartupTrend.key(a).equals(earlier));
		assertEquals(Cause.NONE, a.cause());

		runs.add(run(15_000, 92, "h2", "26.2", "0.5.0"));
		Assessment streak = StartupTrend.assess(runs);
		assertEquals(2, streak.streak());
		assertEquals("Slower for your last 2 launches; no change recorded before the first of them; possibly another program running, a cold disk cache, "
				+ "or a driver/OS update", StartupTrend.cause(streak).english(), "a streak whose first launch had no recorded change keeps none");
		assertEquals(StartupTrend.key(a), StartupTrend.key(streak));
		assertTrue(StartupTrend.describe(streak).contains("; slower 2 launches in a row, from " + again.at()), StartupTrend.describe(streak));
	}

	private static Run timed(long ms, long preloadMs) {
		day++;
		return new Run(String.format("2026-08-%02dT%02d:00:00Z", 1 + day % 28, day % 24), ms, "26.2", "0.5.0", 80, "h", preloadMs);
	}

	// RW-19: with Windows' performance counters off, vanilla's crash-report setup is 1 s warm and 5-7 s cold, which widens
	// the floor past a real slowdown; left out, the same launches show it.
	@Test
	void theCrashReportSetupIsLeftOutWhenTheLatestAndFiveBaselineRunsRecordedIt() {
		List<Run> runs = new ArrayList<>(List.of(timed(16_000, 6_000), timed(11_000, 1_000), timed(16_500, 6_500), timed(11_200, 1_200), timed(15_800, 5_800),
				timed(11_100, 1_100)));
		runs.add(timed(15_000, 1_000));
		Assessment a = StartupTrend.assess(runs);
		assertTrue(a.preloadSubtracted());
		assertEquals(Kind.SLOWER, a.kind(), StartupTrend.describe(a));
		assertEquals(10_000, a.medianMs(), 1e-9);
		assertEquals(14_000, a.latestMs(), 1e-9);
		assertEquals(13_500, a.rawMedianMs(), 1e-9, "the same launches' launch to title, for Tools' line");
		assertEquals("Launch time 40% higher than usual (14.0 s vs your usual ~10.0 s, not counting Minecraft's crash-report setup)",
				StartupTrend.regression(a).english());
		assertTrue(StartupTrend.describe(a).contains("comparable launches, crash-report setup left out"), StartupTrend.describe(a));

		List<Run> raw = new ArrayList<>(runs.subList(0, runs.size() - 1));
		raw.add(new Run("2026-08-30T10:00:00Z", 15_000, "26.2", "0.5.0", 80, "h"));
		Assessment unmeasured = StartupTrend.assess(raw);
		assertTrue(!unmeasured.preloadSubtracted(), "the latest didn't record it");
		assertEquals(Kind.IN_LINE, unmeasured.kind(), "the raw floor is " + unmeasured.floorPercent());
		assertEquals(13_500, unmeasured.medianMs(), 1e-9);

		List<Run> fourMeasured = new ArrayList<>(List.of(run(16_000), run(11_000)));
		fourMeasured.addAll(List.of(timed(16_500, 6_500), timed(11_200, 1_200), timed(15_800, 5_800), timed(11_100, 1_100), timed(15_000, 1_000)));
		assertTrue(!StartupTrend.assess(fourMeasured).preloadSubtracted(), "four baseline runs recorded it, fewer than MIN_RUNS");

		List<Run> broken = new ArrayList<>(runs.subList(0, runs.size() - 1));
		broken.add(timed(15_000, 15_000));
		assertTrue(!StartupTrend.assess(broken).preloadSubtracted(), "a preload time as long as the launch is left alone");
	}

	// RW-19's evidence (docs/research/v0.5/real-world-2026-09-28.md; review M2): the player's 44 real launches of one
	// instance, 07-09 to 09-27 (launch to title and vanilla's OSHI block from the logs, whole seconds). Compared raw, nothing is
	// ever SLOWER, even through the real +34-39 % slowdown after the 09-20 mod updates; without the crash-report setup, the
	// five launches of that stretch are SLOWER and nothing else, and the streak rule makes them two notices (the 09-20-3 and
	// 09-20-4 launches were in line between them).
	@Test
	void thePlayersRealLaunches() throws IOException {
		List<String> lines = Files.readAllLines(RepoFiles.resolve("src/test/resources/startup/real-launch-times-2026-09-28.tsv"), StandardCharsets.UTF_8);
		assertEquals("log\tmods\tjvm_to_title_s\toshi_s\ttitle_minus_oshi_s\ttitle_source", lines.getFirst());
		List<Run> measured = new ArrayList<>();
		List<Run> raw = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			String[] f = line.split("\t");
			String at = f[0].replace(".log.gz", "");
			long ms = Math.round(Double.parseDouble(f[2]) * 1000);
			int mods = f[1].equals("None") ? 0 : Integer.parseInt(f[1]);
			measured.add(new Run(at, ms, "26.2", null, mods, null, Long.parseLong(f[3]) * 1000));
			raw.add(new Run(at, ms, "26.2", null, mods, null));
		}
		assertEquals(44, measured.size());
		assertEquals(List.of(), slower(raw), "raw: the floor hides everything");
		assertEquals(List.of("2026-09-20-2", "2026-09-21-1", "2026-09-22-1", "2026-09-23-1", "2026-09-24-1"), slower(measured));
		List<String> keys = new ArrayList<>();
		for (int i = 1; i <= measured.size(); i++) {
			Assessment a = StartupTrend.assess(measured.subList(0, i));
			if (a.slower() && !keys.contains(StartupTrend.key(a))) {
				keys.add(StartupTrend.key(a));
			}
		}
		assertEquals(List.of("startup.regression.2026-09-20-2", "startup.regression.2026-09-21-1"), keys);
	}

	private static List<String> slower(List<Run> runs) {
		List<String> out = new ArrayList<>();
		for (int i = 1; i <= runs.size(); i++) {
			Assessment a = StartupTrend.assess(runs.subList(0, i));
			if (a.slower()) {
				out.add(a.latest().at());
			}
		}
		return out;
	}
}
