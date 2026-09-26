package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.ChangeWindow;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkResultScreenTest {
	// review-8 P5B-F4: status lines wrap; when the rows would crowd out the table, the trend's later lines go first, then
	// the most-wrapped line folds back to one clipped row (its text a tooltip).
	@Test
	void statusLinesWrapWithinTheirRows() {
		assertArrayEquals(new int[]{1, 2, 1, 2}, BenchmarkResultScreen.fit(new int[]{1, 2, 1, 2}, 6, 0, 0), "everything fits wrapped");
		assertArrayEquals(new int[]{1, 2, 1, 0, 0, 2}, BenchmarkResultScreen.fit(new int[]{1, 2, 1, 1, 1, 2}, 6, 2, 5),
				"the trend (lines 2-4) keeps its first line; its later lines go first, from the end");
		assertArrayEquals(new int[]{1, 2, 1, 1}, BenchmarkResultScreen.fit(new int[]{1, 2, 1, 3}, 5, 0, 0), "then the most-wrapped line folds back");
		assertArrayEquals(new int[]{1, 1, 1, 1}, BenchmarkResultScreen.fit(new int[]{1, 2, 1, 2}, 3, 0, 0), "never below one row a line");
		assertArrayEquals(new int[]{2, 1}, BenchmarkResultScreen.fit(new int[]{2, 2}, 3, 0, 0), "ties: the later line folds first");
	}

	@Test
	void theChartDateIsTheLocalDay() {
		assertEquals("09-26", BenchmarkResultScreen.chartDate("2026-09-25T20:05:31Z", ZoneOffset.ofHours(10)));
		assertEquals("09-25", BenchmarkResultScreen.chartDate("2026-09-25T20:05:31Z", ZoneOffset.UTC));
	}

	@Test
	void anUnreadableDateFallsBackToItsMonthAndDay() {
		assertEquals("09-25", BenchmarkResultScreen.chartDate("2026-09-25 later", ZoneOffset.UTC));
		assertEquals("?", BenchmarkResultScreen.chartDate("soon", ZoneOffset.UTC));
		assertEquals("?", BenchmarkResultScreen.chartDate(null, ZoneOffset.UTC));
	}

	// Parses as an Instant but has no date in the zone: shown as text, never an exception while drawing (re-check of review 6).
	@Test
	void anInstantOutsideTheZonesRangeDoesntThrow() {
		assertEquals("00000", BenchmarkResultScreen.chartDate("+1000000000-01-01T00:00:00Z", ZoneOffset.UTC));
		assertEquals("99999", BenchmarkResultScreen.chartDate("+999999999-12-31T23:59:59Z", ZoneOffset.ofHours(10)));
	}

	// docs/v0.4/SPEC.md AC7.5: the truth table of the trend lines under the result (regression with its changes, the
	// outside and nothing-recorded cases, in line, improvement, too few, different conditions, no result). The result
	// screen counts the changes in one line (review M3); Benchmark history lists them (TrendTextTest).

	private static final Function<HistoryModel.Change, Text> DESCRIBE = c -> Text.literal(c.row() == HistoryModel.Row.SETTING
			? c.label() + ": " + c.before() + " → " + c.after() : "Updated " + c.modId() + ": " + c.file() + " → " + c.newFile());

	private static BenchmarkRecord run(String id, String at, @Nullable Double low, int rd, int width, @Nullable String hash, String version) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(rd, rd, null, null, null));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(8, 8, null, null, null));
		return new BenchmarkRecord(id, at, version, "26.2", "MEASURE", "BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 144, true, knobs,
				low == null ? null : new BenchmarkRecord.Result(800, low, 2, 2, 0.02), Map.of(), Map.of(), null, false,
				new BenchmarkRecord.Context(false, false, null, width, 1440, false, 1).withModSet(hash, null));
	}

	private static List<BenchmarkRecord> usual(double... lows) {
		List<BenchmarkRecord> runs = new ArrayList<>();
		for (int i = 0; i < lows.length; i++) {
			runs.add(run("r" + i, "2026-09-2" + i + "T10:00:00Z", lows[i], 12, 2560, "hash-a", "0.4.0"));
		}
		return runs;
	}

	private static BenchmarkTrend.View view(List<BenchmarkRecord> runs, BenchmarkRecord latest) {
		List<BenchmarkRecord> all = new ArrayList<>(runs);
		all.add(latest);
		return BenchmarkTrend.view(all, BenchmarkTrend.contextKey(latest), null);
	}

	private static BenchmarkTrend.View regressed(BenchmarkRecord latest, JournalEntry... journal) {
		List<BenchmarkRecord> runs = usual(540, 545, 538, 550);
		BenchmarkTrend.View view = view(runs, latest);
		List<HistoryModel.Entry> entries = HistoryModel.build(Journal.State.OK, List.of(journal), Map.of(), HistoryModel.Labels.RAW).entries();
		return view.withChanges(ChangeWindow.between(runs.getLast(), latest, entries));
	}

	private static List<String> english(BenchmarkTrend.@Nullable View view) {
		return BenchmarkResultScreen.trendLines(view, ZoneOffset.UTC, DESCRIBE).stream().map(l -> l.tone() + " " + l.text().english()).toList();
	}

	private static final JournalEntry UPDATE = new JournalEntry("e1", "2026-09-24T12:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null, List.of(
			JournalChange.file(JournalChange.DISABLE, "sodium", "sodium-0.6.5.jar", JournalChange.APPLIED, "op-1", "g-1"),
			JournalChange.file(JournalChange.ENABLE, "sodium", "sodium-0.6.6.jar", JournalChange.APPLIED, "op-2", "g-1")));

	private static JournalEntry settings(String id, String at, String... keys) {
		List<JournalChange> changes = new ArrayList<>();
		for (String key : keys) {
			changes.add(JournalChange.setting(key, "1", "2", JournalChange.APPLIED, null));
		}
		return new JournalEntry(id, at, JournalEntry.APPLY, "0.4.0", "26.2", null, changes);
	}

	@Test
	void aRegressionListsTheChangesSinceTheBaselineAsPossiblyRelated() {
		// Median of 540, 545, 538, 550 = 542.5; 440 is 18.9 % below it.
		assertEquals(List.of("BAD 1% lows 19% below your usual 543 FPS since 2026-09-23",
						"WARNING Changes since then (may be related): 1, listed in Benchmark history"),
				english(regressed(run("latest", "2026-09-25T10:00:00Z", 440.0, 12, 2560, "hash-b", "0.4.0"), UPDATE)));
	}

	@Test
	void aRegressionWithNothingRecorded() {
		assertEquals(List.of("BAD 1% lows 19% below your usual 543 FPS since 2026-09-23",
						"WARNING No change recorded; possibly a driver, OS or other change"),
				english(regressed(run("latest", "2026-09-25T10:00:00Z", 440.0, 12, 2560, "hash-a", "0.4.0"))));
	}

	@Test
	void aRegressionWithManyChangesAVersionChangeAndTheModSetChangedOutside() {
		assertEquals(List.of("BAD 1% lows 19% below your usual 543 FPS since 2026-09-23",
						"WARNING Changes since then (may be related): 4, listed in Benchmark history",
						"WARNING Something outside RigTune changed too (the mod set differs)"),
				english(regressed(run("latest", "2026-09-25T10:00:00Z", 440.0, 12, 2560, "hash-b", "0.4.1"),
						settings("e1", "2026-09-24T12:00:00Z", "vanilla.clouds", "vanilla.particles", "vanilla.entityShadows"))));
		// Benchmark history lists them (3 rows: 2 and "…and N more").
		BenchmarkTrend.View listed = regressed(run("latest", "2026-09-25T10:00:00Z", 440.0, 12, 2560, "hash-b", "0.4.1"),
				settings("e1", "2026-09-24T12:00:00Z", "vanilla.clouds", "vanilla.particles", "vanilla.entityShadows"));
		assertEquals(List.of("BAD 1% lows 19% below your usual 543 FPS since 2026-09-23",
						"WARNING Changes since then (may be related):",
						"NORMAL · RigTune 0.4.0 → 0.4.1",
						"NORMAL · clouds: 1 → 2",
						"NORMAL · …and 2 more (see History)",
						"WARNING Something outside RigTune changed too (the mod set differs)"),
				TrendText.assessment(listed, ZoneOffset.UTC, DESCRIBE, 3).stream().map(l -> l.tone() + " " + l.text().english()).toList());
	}

	@Test
	void inLineImprovementAndTooFew() {
		assertEquals(List.of("NORMAL 1% lows in line with your usual 500 FPS (3 comparable runs)"),
				english(view(usual(500, 500, 500), run("latest", "2026-09-25T10:00:00Z", 490.0, 12, 2560, "hash-a", "0.4.0"))));
		// An improvement is never an alert: it is shown, in the "good" colour.
		assertEquals(List.of("GOOD 1% lows 20% above your usual 500 FPS (3 comparable runs)"),
				english(view(usual(500, 500, 500), run("latest", "2026-09-25T10:00:00Z", 600.0, 12, 2560, "hash-a", "0.4.0"))));
		assertEquals(List.of("NORMAL Not enough comparable runs for a trend yet (2 of 3)"),
				english(view(usual(500, 500), run("latest", "2026-09-25T10:00:00Z", 100.0, 12, 2560, "hash-a", "0.4.0"))));
	}

	@Test
	void differentConditionsClaimNoDelta() {
		assertEquals(List.of("WARNING Performance changed under different conditions (render distance, resolution); cause unknown."),
				english(view(usual(500, 500, 500), run("latest", "2026-09-25T10:00:00Z", 300.0, 16, 1920, "hash-a", "0.4.0"))));
	}

	@Test
	void noResultNoLines() {
		// The screen has no trend for a run it couldn't save (null), and a run without a result has nothing to compare.
		assertEquals(List.of(), english(null));
		BenchmarkRecord latest = run("latest", "2026-09-25T10:00:00Z", null, 12, 2560, "hash-a", "0.4.0");
		String key = BenchmarkTrend.contextKey(latest);
		assertEquals(List.of(), english(new BenchmarkTrend.View(key, List.of(key), 0, 0, List.of(latest), List.of(latest),
				BenchmarkTrend.assess(latest, List.of(latest)), null, null, List.of())));
	}

	@Test
	void everyToneHasItsColour() {
		assertEquals(BenchmarkTrendLines.COLOR_BAD, BenchmarkTrendLines.color(TrendText.Tone.BAD));
		assertEquals(BenchmarkTrendLines.COLOR_GOOD, BenchmarkTrendLines.color(TrendText.Tone.GOOD));
		assertEquals(BenchmarkTrendLines.COLOR_WARNING, BenchmarkTrendLines.color(TrendText.Tone.WARNING));
		assertEquals(BenchmarkTrendLines.COLOR_NORMAL, BenchmarkTrendLines.color(TrendText.Tone.NORMAL));
	}
}
