package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Difference;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Cause;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Caveat;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Kind;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.chaotix345.rigtune.core.tryit.TryItFixtures.ENTRY;
import static io.github.chaotix345.rigtune.core.tryit.TryItFixtures.KEY;
import static io.github.chaotix345.rigtune.core.tryit.TryItFixtures.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md AC6.5, docs/research/v0.5/feature-try-it.md §2.6: the verdict's golden table. The floor is never under
// 5 % (2 x a CV of at least MIN_CV, a missing CV counting as 5 %), and the trend's floor when 3 or more earlier comparable
// runs exist; BETTER / WORSE / NO_CLEAR_CHANGE at +-floor; no verdict (NOT_COMPARABLE, each cause named in order) when
// anything but the tried key differs; NO_NUMBERS without a result; the caveat lines. The numbers are always there.
class TryItVerdictTest {
	private static final String PAIR = "tryit-p";
	private static final List<JournalEntry> JOURNAL = List.of(TryItFixtures.apply(ENTRY, "2026-09-20T10:05:00Z", JournalChange.APPLIED));

	private static TryIt restart() {
		return TryItFixtures.restartTry().withAfter(TryItFixtures.restartTry().settingsBefore(), "session-b", null);
	}

	private static TryItFixtures.Run before() {
		return run("before").at("2026-09-20T10:01:00Z").before(PAIR).low(1000).avg(1500).cursor("e-older");
	}

	private static TryItFixtures.Run after() {
		return run("after").at("2026-09-21T10:01:00Z").after(PAIR).low(1000).avg(1500).cursor(ENTRY);
	}

	private static Verdict verdict(TryIt t, TryItFixtures.Run before, TryItFixtures.Run after) {
		return verdict(t, before, after, JOURNAL);
	}

	private static Verdict verdict(TryIt t, TryItFixtures.Run before, TryItFixtures.Run after, List<JournalEntry> journal) {
		BenchmarkRecord b = before.build();
		BenchmarkRecord a = after.build();
		return TryItVerdict.of(t, b, a, List.of(b, a), journal);
	}

	private static Verdict low(double beforeLow, double afterLow, Double beforeCv, Double afterCv) {
		return verdict(restart(), before().low(beforeLow).cv(beforeCv), after().low(afterLow).cv(afterCv));
	}

	@Test
	void theFloorIsFivePercentWithTinyCvs() {
		assertEquals(0.025, TryItVerdict.MIN_CV);
		Verdict v = low(1000, 1049, 0.003, 0.004);
		assertEquals(5.0, v.floorPercent(), 1e-12);
		assertEquals(Kind.NO_CLEAR_CHANGE, v.kind());
		assertEquals(4.9, v.lowPercent(), 1e-9);
		assertEquals(Kind.BETTER, low(1000, 1050, 0.003, 0.004).kind());
		assertEquals(Kind.WORSE, low(1000, 950, 0.003, 0.004).kind());
		assertEquals(Kind.NO_CLEAR_CHANGE, low(1000, 951, 0.003, 0.004).kind());
		assertEquals(Kind.WORSE, low(1000, 500, 0.003, 0.004).kind());
	}

	@Test
	void theBoundariesAreWithin1e9() {
		assertEquals(Kind.BETTER, low(1000, 1050 - 5e-9, 0.01, 0.01).kind(), "5 - 5e-10 %");
		assertEquals(Kind.NO_CLEAR_CHANGE, low(1000, 1050 - 2e-8, 0.01, 0.01).kind(), "5 - 2e-9 %");
		assertEquals(Kind.WORSE, low(1000, 950 + 5e-9, 0.01, 0.01).kind());
		assertEquals(Kind.NO_CLEAR_CHANGE, low(1000, 950 + 2e-8, 0.01, 0.01).kind());
	}

	@Test
	void theFloorIsTwiceTheLargerCvAboveTwoAndAHalfPercent() {
		assertEquals(8.0, low(1000, 1000, 0.04, 0.03).floorPercent(), 1e-9);
		assertEquals(8.0, low(1000, 1000, 0.03, 0.04).floorPercent(), 1e-9);
		assertEquals(6.0, low(1000, 1000, 0.01, 0.03).floorPercent(), 1e-9);
		assertEquals(Kind.NO_CLEAR_CHANGE, low(1000, 1079, 0.04, 0.03).kind());
		assertEquals(Kind.BETTER, low(1000, 1080, 0.04, 0.03).kind());
	}

	@Test
	void aMissingCvCountsAsFivePercent() {
		assertEquals(10.0, low(1000, 1000, null, 0.01).floorPercent(), 1e-9);
		assertEquals(10.0, low(1000, 1000, 0.01, null).floorPercent(), 1e-9);
	}

	@Test
	void theTrendsFloorWithThreeEarlierComparableRuns() {
		List<BenchmarkRecord> runs = new ArrayList<>();
		runs.add(run("old-rd").low(100).rd(16).build());
		runs.add(run("old1").low(400).build());
		runs.add(run("old2").low(500).build());
		BenchmarkRecord b = before().cv(0.02).build();
		BenchmarkRecord a = after().cv(0.02).low(1500).build();
		List<BenchmarkRecord> two = new ArrayList<>(runs);
		two.add(b);
		two.add(a);
		assertEquals(5.0, TryItVerdict.of(restart(), b, a, two, JOURNAL).floorPercent(), 1e-9, "2 comparable runs: no trend floor");

		runs.add(run("old3").low(600).build());
		runs.add(b);
		runs.add(a);
		// Earlier lows 400, 500, 600 (the RD 16 run isn't comparable): median 500, MAD 100.
		Verdict v = TryItVerdict.of(restart(), b, a, runs, JOURNAL);
		assertEquals(2 * 1.4826 * 100 / 500 * 100, v.floorPercent(), 1e-9);
		assertEquals(Kind.NO_CLEAR_CHANGE, v.kind(), "+50 % is inside a 59 % floor");
	}

	@Test
	void theTrendsFloorNeverLowersTheMinimum() {
		List<BenchmarkRecord> runs = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			runs.add(run("old" + i).low(1000).build());
		}
		BenchmarkRecord b = before().cv(0.001).build();
		BenchmarkRecord a = after().cv(0.001).build();
		runs.add(b);
		runs.add(a);
		assertEquals(5.0, TryItVerdict.of(restart(), b, a, runs, JOURNAL).floorPercent(), 1e-9);
	}

	@Test
	void theNumbersAreAlwaysThere() {
		Verdict v = verdict(restart(), before().low(1000).avg(1500), after().low(1100).avg(1650).size(1920, 1080));
		assertEquals(Kind.NOT_COMPARABLE, v.kind());
		assertEquals(10.0, v.lowPercent(), 1e-9);
		assertEquals(10.0, v.avgPercent(), 1e-9);
		assertEquals(5.0, v.floorPercent(), 1e-9);
	}

	@Test
	void noNumbersWithoutAResult() {
		Verdict v = verdict(restart(), before(), after().noResult());
		assertEquals(Kind.NO_NUMBERS, v.kind());
		assertNull(v.lowPercent());
		assertNull(v.avgPercent());
		assertEquals(Kind.NO_NUMBERS, verdict(restart(), before().noResult(), after()).kind());
		assertEquals(Kind.NO_NUMBERS, verdict(restart(), before().noResult(), after().size(1920, 1080)).kind(), "no numbers comes first");
	}

	@Test
	void eachCauseAlone() {
		assertEquals(List.of(new Cause.Condition(Difference.RESOLUTION)), verdict(restart(), before(), after().size(1920, 1080)).causes());
		assertEquals(List.of(new Cause.Condition(Difference.DISTANT_HORIZONS)), verdict(restart(), before(), after().dh(true)).causes());
		assertEquals(List.of(new Cause.Condition(Difference.SCENE)), verdict(restart(), before(), after().scene("CURRENT")).causes());
		assertEquals(List.of(new Cause.Mods()), verdict(restart(), before(), after().hash("hash-b")).causes());
		assertEquals(Kind.NO_CLEAR_CHANGE, verdict(restart(), before(), after().hash(null)).kind(), "an unknown mod set doesn't count");

		List<JournalEntry> later = List.of(JOURNAL.getFirst(), TryItFixtures.entry("e-2", "2026-09-20T11:00:00Z", JournalEntry.APPLY, null,
				TryItFixtures.setting("vanilla.particles", JournalChange.APPLIED)));
		assertEquals(List.of(new Cause.Entry("e-2", JournalEntry.APPLY)), verdict(restart(), before(), after().cursor("e-2"), later).causes());

		TryIt t = restart().withAfter(with(restart().settingsBefore(), "vanilla.particles", "2"), "session-b", null);
		Verdict v = verdict(t, before(), after());
		assertEquals(Kind.NOT_COMPARABLE, v.kind());
		assertEquals(List.of(new Cause.Setting("vanilla.particles")), v.causes());
	}

	@Test
	void allCausesTogetherInOrder() {
		List<JournalEntry> journal = List.of(JOURNAL.getFirst(),
				TryItFixtures.entry("e-2", "2026-09-20T11:00:00Z", JournalEntry.BENCHMARK, null, TryItFixtures.setting("vanilla.renderDistance", JournalChange.APPLIED)),
				TryItFixtures.entry("e-3", "2026-09-20T12:00:00Z", JournalEntry.UNDO, "e-2", TryItFixtures.setting("vanilla.renderDistance", JournalChange.APPLIED)));
		Map<String, String> after = with(with(restart().settingsBefore(), "vanilla.particles", "2"), "vanilla.renderDistance", "10");
		TryIt t = restart().withAfter(after, "session-b", null);
		Verdict v = verdict(t, before(), after().size(1920, 1080).rd(10).hash("hash-b").cursor("e-3"), journal);
		assertEquals(List.of(new Cause.Condition(Difference.RENDER_DISTANCE), new Cause.Condition(Difference.RESOLUTION), new Cause.Mods(),
				new Cause.Entry("e-2", JournalEntry.BENCHMARK), new Cause.Entry("e-3", JournalEntry.UNDO), new Cause.Setting("vanilla.renderDistance"),
				new Cause.Setting("vanilla.particles")), v.causes());
	}

	@Test
	void theTriedKeysOwnDifferencesAreAllowed() {
		TryIt shaders = TryItFixtures.tryOf("iris.enableShaders", TryIt.Kind.RESTART, BenchmarkRequest.Scene.BENCHMARK_WORLD);
		assertEquals(Kind.NO_CLEAR_CHANGE, verdict(shaders, before(), after().shaders(true, "Complementary.zip")).kind());
		TryIt rdTry = TryItFixtures.tryOf("vanilla.renderDistance", TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT);
		TryIt rd = rdTry.withAfter(rdTry.settingsBefore(), TryItFixtures.SESSION, TryItFixtures.HERE);
		assertEquals(Kind.NO_CLEAR_CHANGE, verdict(rd, before().scene("CURRENT"), after().scene("CURRENT").rd(10)).kind());
		assertEquals(List.of(new Cause.Condition(Difference.SIMULATION_DISTANCE)),
				verdict(rd, before().scene("CURRENT"), after().scene("CURRENT").rd(10).sd(6)).causes(), "RD allows only RD");
		assertEquals(List.of(new Cause.Condition(Difference.SHADERS)), verdict(restart(), before(), after().shaders(true, "x.zip")).causes());
	}

	@Test
	void theTriedKeyAndTheLiftedKeysArentSettingCauses() {
		Map<String, String> after = with(with(restart().settingsBefore(), KEY, "ONE_FRAME"), "vanilla.maxFps", "260");
		assertEquals(Kind.NO_CLEAR_CHANGE, verdict(restart().withAfter(after, "session-b", null), before(), after()).kind());
		assertEquals(Kind.NO_CLEAR_CHANGE, verdict(TryItFixtures.restartTry(), before(), after()).kind(), "no after snapshot: nothing to compare");
		Map<String, String> gone = new LinkedHashMap<>(restart().settingsBefore());
		gone.remove("vanilla.particles");
		assertEquals(List.of(new Cause.Setting("vanilla.particles")), verdict(restart().withAfter(gone, "session-b", null), before(), after()).causes(),
				"a key missing on one side differs");
	}

	@Test
	void whichHistoryEntriesCount() {
		JournalEntry undoOfTheTry = TryItFixtures.entry("e-undo", "2026-09-20T11:00:00Z", JournalEntry.UNDO, ENTRY,
				TryItFixtures.setting(KEY, JournalChange.APPLIED));
		JournalEntry discarded = TryItFixtures.entry("e-gone", "2026-09-20T11:30:00Z", JournalEntry.APPLY, null,
				TryItFixtures.setting("sodium.performance.use_fog_occlusion", JournalChange.DISCARDED),
				TryItFixtures.setting("vanilla.particles", JournalChange.ABANDONED));
		JournalEntry empty = TryItFixtures.entry("e-empty", "2026-09-20T11:40:00Z", JournalEntry.UNDO, "e-older");
		JournalEntry staged = TryItFixtures.entry("e-staged", "2026-09-20T11:50:00Z", JournalEntry.APPLY, null,
				TryItFixtures.setting("sodium.performance.use_fog_occlusion", JournalChange.STAGED));
		JournalEntry afterTheRun = TryItFixtures.entry("e-late", "2026-09-22T10:00:00Z", JournalEntry.APPLY, null,
				TryItFixtures.setting("vanilla.particles", JournalChange.APPLIED));
		List<JournalEntry> journal = List.of(TryItFixtures.entry("e-older", "2026-09-19T10:00:00Z", JournalEntry.APPLY, null,
				TryItFixtures.setting("vanilla.particles", JournalChange.APPLIED)), JOURNAL.getFirst(), undoOfTheTry, discarded, empty, staged, afterTheRun);
		assertEquals(List.of(new Cause.Entry("e-staged", JournalEntry.APPLY)), verdict(restart(), before(), after().cursor("e-staged"), journal).causes(),
				"only the staged one: an undo of the try, changes that never took effect and entries after the run don't count");
		assertEquals(List.of(), verdict(restart(), before(), after().cursor(ENTRY), journal).causes(), "the cursor at the try's own entry");
	}

	@Test
	void withoutTheCursorTheEntriesUpToTheAfterRunsTimeCount() {
		List<JournalEntry> journal = List.of(JOURNAL.getFirst(),
				TryItFixtures.entry("e-2", "2026-09-21T10:00:00Z", JournalEntry.APPLY, null, TryItFixtures.setting("vanilla.particles", JournalChange.APPLIED)),
				TryItFixtures.entry("e-3", "2026-09-21T10:02:00Z", JournalEntry.APPLY, null, TryItFixtures.setting("vanilla.particles", JournalChange.APPLIED)));
		assertEquals(List.of(new Cause.Entry("e-2", JournalEntry.APPLY)), verdict(restart(), before(), after().cursor(null), journal).causes());
		assertEquals(List.of(new Cause.Entry("e-2", JournalEntry.APPLY)), verdict(restart(), before(), after().cursor("e-folded"), journal).causes(),
				"a cursor no longer in the journal");
	}

	@Test
	void theCaveats() {
		assertEquals(List.of(Caveat.SESSIONS, Caveat.SCENE), verdict(restart(), before(), after()).caveats());
		assertEquals(List.of(Caveat.NOISY, Caveat.SESSIONS, Caveat.SCENE), verdict(restart(), before().cv(0.06), after()).caveats());
		assertEquals(List.of(Caveat.NOISY, Caveat.SESSIONS, Caveat.SCENE), verdict(restart(), before(), after().cv(0.051)).caveats());
		assertEquals(List.of(Caveat.SESSIONS, Caveat.SCENE), verdict(restart(), before().cv(0.05), after()).caveats(), "5 % isn't over 5 %");
		assertEquals(List.of(Caveat.DH, Caveat.SESSIONS, Caveat.SCENE), verdict(restart(), before().dh(true), after().dh(true)).caveats());
		assertEquals(List.of(Caveat.DH, Caveat.SESSIONS, Caveat.SCENE), verdict(restart(), before(), after().dhGenerating(true)).caveats());
		assertEquals(List.of(Caveat.SESSIONS, Caveat.SCENE), verdict(TryItFixtures.restartTry(), before(), after()).caveats(),
				"a restart try without an after session: different sessions");

		TryIt nowHere = TryItFixtures.tryOf("vanilla.particles", TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT);
		TryIt nowHereAfter = nowHere.withAfter(nowHere.settingsBefore(), TryItFixtures.SESSION, TryItFixtures.HERE);
		assertEquals(List.of(Caveat.SCENE), verdict(nowHereAfter, before().scene("CURRENT"), after().scene("CURRENT")).caveats());
		assertEquals(List.of(Caveat.SCENE), verdict(nowHere, before().scene("CURRENT"), after().scene("CURRENT")).caveats(), "NOW without one: same session");
		TryIt nowWorld = TryItFixtures.tryOf("vanilla.particles", TryIt.Kind.NOW, BenchmarkRequest.Scene.BENCHMARK_WORLD);
		assertEquals(List.of(Caveat.WORLD_CONTENT, Caveat.SCENE), verdict(nowWorld.withAfter(nowWorld.settingsBefore(), TryItFixtures.SESSION, null), before(),
				after()).caveats());
		assertEquals(List.of(Caveat.WORLD_CONTENT, Caveat.SESSIONS, Caveat.SCENE), verdict(nowWorld.withAfter(nowWorld.settingsBefore(), "session-c", null),
				before(), after()).caveats(), "resumed after a restart");
	}

	@Test
	void aVerdictIsAlwaysMade() {
		Verdict v = verdict(restart(), before(), after());
		assertNotNull(v);
		assertTrue(v.causes().isEmpty());
		assertEquals(0.0, v.lowPercent(), 1e-12);
	}

	@Test
	void anAfterRunElsewhereInTheCurrentSceneHasNoVerdict() {
		TryIt here = TryItFixtures.tryOf("vanilla.particles", TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT);
		TryItFixtures.Run b = before().scene("CURRENT");
		TryItFixtures.Run a = after().scene("CURRENT");
		TryIt.Spot spot = TryItFixtures.HERE;
		assertEquals(List.of(), verdict(here.withAfter(here.settingsBefore(), TryItFixtures.SESSION, spot), b, a).causes());
		for (TryIt.Spot moved : List.of(new TryIt.Spot(101, 64, -200, spot.dimension(), spot.server()),
				new TryIt.Spot(100, 65, -200, spot.dimension(), spot.server()), new TryIt.Spot(100, 64, -200, "minecraft:the_nether", spot.server()),
				new TryIt.Spot(100, 64, -200, spot.dimension(), "sp:Other World"))) {
			Verdict v = verdict(here.withAfter(here.settingsBefore(), TryItFixtures.SESSION, moved), b, a);
			assertEquals(Kind.NOT_COMPARABLE, v.kind(), moved.toString());
			assertEquals(List.of(new Cause.Moved()), v.causes());
		}
		assertEquals(List.of(new Cause.Moved()), verdict(here.withAfter(here.settingsBefore(), TryItFixtures.SESSION, null), b, a).causes(),
				"an unknown after spot fails closed");
		assertEquals(List.of(new Cause.Moved()), verdict(here, b, a).causes(), "no after spot yet");
		TryIt world = TryItFixtures.tryOf("vanilla.particles", TryIt.Kind.NOW, BenchmarkRequest.Scene.BENCHMARK_WORLD);
		assertEquals(List.of(), verdict(world.withAfter(world.settingsBefore(), TryItFixtures.SESSION, null), before(), after()).causes(),
				"the benchmark world is always the same spot");
		assertEquals(List.of(new Cause.Condition(Difference.RESOLUTION), new Cause.Moved(), new Cause.Mods()),
				verdict(here.withAfter(here.settingsBefore(), TryItFixtures.SESSION, null), b, a.size(1920, 1080).hash("hash-b")).causes(),
				"after the conditions, before the mods");
	}

	// The coordinator's SPEC decision (WS-B review M4): a run left out of the trend (a fresh benchmark world, Distant
	// Horizons generating) makes the pair incomparable, as the Benchmark menu's pair.
	@Test
	void aRunLeftOutOfTheTrendMeansNoVerdict() {
		assertEquals(List.of(new Cause.Excluded(Cause.Excluded.Why.FRESH_WORLD)), verdict(restart(), before().worldFresh(true), after()).causes());
		assertEquals(List.of(new Cause.Excluded(Cause.Excluded.Why.DH_GENERATING)), verdict(restart(), before(), after().dhGenerating(true)).causes());
		assertEquals(List.of(new Cause.Excluded(Cause.Excluded.Why.FRESH_WORLD), new Cause.Excluded(Cause.Excluded.Why.DH_GENERATING)),
				verdict(restart(), before().worldFresh(true), after().worldFresh(true).dhGenerating(true)).causes(), "each reason once");
		assertEquals(Kind.NOT_COMPARABLE, verdict(restart(), before().worldFresh(true).low(1000), after().low(2000)).kind());
		assertEquals(List.of(), verdict(restart(), before().worldFresh(false), after().dhGenerating(false)).causes());
		TryIt here = TryItFixtures.tryOf("vanilla.particles", TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT);
		assertEquals(List.of(new Cause.Condition(Difference.RESOLUTION), new Cause.Excluded(Cause.Excluded.Why.DH_GENERATING), new Cause.Moved()),
				verdict(here, before().scene("CURRENT"), after().scene("CURRENT").size(1920, 1080).dhGenerating(true)).causes(),
				"after the conditions, before the spot");
	}

	@Test
	void numbersAHandEditBrokeAreNoNumbers() {
		for (double bad : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0, -5}) {
			assertEquals(Kind.NO_NUMBERS, verdict(restart(), before().low(bad), after()).kind(), "low " + bad);
			assertEquals(Kind.NO_NUMBERS, verdict(restart(), before(), after().avg(bad)).kind(), "avg " + bad);
		}
		Verdict nanCv = low(1000, 1000, Double.NaN, 0.01);
		assertEquals(10.0, nanCv.floorPercent(), 1e-9, "a CV that isn't a number counts as missing");
		assertEquals(List.of(Caveat.SESSIONS, Caveat.SCENE), nanCv.caveats());
		assertEquals(10.0, low(1000, 1000, Double.POSITIVE_INFINITY, 0.01).floorPercent(), 1e-9);

		List<BenchmarkRecord> runs = new ArrayList<>();
		runs.add(run("old1").low(1000).build());
		runs.add(run("old2").low(1000).build());
		runs.add(run("old3").low(Double.NaN).build());
		BenchmarkRecord b = before().build();
		BenchmarkRecord a = after().build();
		runs.add(b);
		runs.add(a);
		assertEquals(5.0, TryItVerdict.of(restart(), b, a, runs, JOURNAL).floorPercent(), 1e-9, "only 2 usable earlier lows: no trend floor");
	}

	@Test
	void timesThatCantBeReadCountAsChanges() {
		List<JournalEntry> journal = List.of(JOURNAL.getFirst(),
				TryItFixtures.entry("e-2", "yesterday", JournalEntry.APPLY, null, TryItFixtures.setting("vanilla.particles", JournalChange.APPLIED)),
				TryItFixtures.entry("e-3", "2026-09-23T10:00:00Z", JournalEntry.APPLY, null, TryItFixtures.setting("vanilla.particles", JournalChange.APPLIED)));
		assertEquals(List.of(new Cause.Entry("e-2", JournalEntry.APPLY)), verdict(restart(), before(), after().cursor(null), journal).causes());
		assertEquals(List.of(new Cause.Entry("e-2", JournalEntry.APPLY), new Cause.Entry("e-3", JournalEntry.APPLY)),
				verdict(restart(), before(), after().cursor(null).at("not a time"), journal).causes(), "without the run's time, every later entry");
	}

	private static Map<String, String> with(Map<String, String> map, String key, String value) {
		Map<String, String> out = new LinkedHashMap<>(map);
		out.put(key, value);
		return out;
	}
}
