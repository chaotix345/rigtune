package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC7.3: a history fixture gives the right labels and order for a window by cursor and by timestamps; a
// RigTune version change; a differing hash without a journal match; an empty window.
class ChangeWindowTest {
	private static final HistoryModel.Labels LABELS = new HistoryModel.Labels() {
		@Override
		public String label(String key) {
			return Map.of("vanilla.renderDistance", "Render Distance", "vanilla.entityShadows", "Entity Shadows",
					"vanilla.particles", "Particles", "vanilla.clouds", "Clouds").getOrDefault(key, key);
		}
	};

	private static final JournalEntry BEFORE = new JournalEntry("e1", "2026-09-20T09:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null,
			List.of(JournalChange.setting("vanilla.entityShadows", "true", "false", JournalChange.APPLIED, null)));
	private static final JournalEntry UPDATE = new JournalEntry("e2", "2026-09-22T12:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null, List.of(
			JournalChange.file(JournalChange.DISABLE, "sodium", "sodium-0.6.5.jar", JournalChange.APPLIED, "op-1", "g-1"),
			JournalChange.file(JournalChange.ENABLE, "sodium", "sodium-0.6.6.jar", JournalChange.APPLIED, "op-2", "g-1"),
			JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null)));
	private static final JournalEntry NOT_IN_EFFECT = new JournalEntry("e3", "2026-09-23T12:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null, List.of(
			JournalChange.setting("vanilla.particles", "0", "1", JournalChange.STAGED, "op-3"),
			JournalChange.setting("vanilla.clouds", "1", "0", JournalChange.DISCARDED, "op-4"),
			JournalChange.setting("vanilla.clouds", "1", "0", JournalChange.ABANDONED, "op-5")));
	private static final JournalEntry SETTING = new JournalEntry("e4", "2026-09-24T08:00:00Z", JournalEntry.BENCHMARK, "0.4.0", "26.2", null,
			List.of(JournalChange.setting("vanilla.renderDistance", "16", "14", JournalChange.REVERTED, null)));
	private static final JournalEntry AFTER = new JournalEntry("e5", "2026-09-26T08:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null,
			List.of(JournalChange.setting("vanilla.clouds", "1", "0", JournalChange.APPLIED, null)));

	private static List<HistoryModel.Entry> history(JournalEntry... entries) {
		return HistoryModel.build(Journal.State.OK, List.of(entries), Map.of(), LABELS).entries();
	}

	private static BenchmarkRecord baseline() {
		return TrendFixtures.run("base").at("2026-09-21T10:00:00Z").cursor("e1").build();
	}

	private static BenchmarkRecord latest() {
		return TrendFixtures.run("latest").at("2026-09-25T10:00:00Z").cursor("e4").build();
	}

	private static List<String> described(ChangeWindow window) {
		return window.items().stream().map(i -> i.entryId() + " " + switch (i.change().row()) {
			case SETTING -> i.change().label() + ": " + i.change().before() + " -> " + i.change().after();
			case UPDATED -> "updated " + i.change().modId() + ": " + i.change().file() + " -> " + i.change().newFile();
			default -> i.change().row() + " " + i.change().file();
		}).toList();
	}

	private static final List<String> EXPECTED = List.of("e2 updated sodium: sodium-0.6.5.jar -> sodium-0.6.6.jar", "e2 Render Distance: 12 -> 16",
			"e4 Render Distance: 16 -> 14");

	@Test
	void byCursorOldestFirstWithHistorysLabels() {
		ChangeWindow window = ChangeWindow.between(baseline(), latest(), history(BEFORE, UPDATE, NOT_IN_EFFECT, SETTING, AFTER));
		assertTrue(window.byCursor());
		assertEquals(EXPECTED, described(window));
		assertEquals(JournalEntry.BENCHMARK, window.items().getLast().entryKind());
		assertFalse(window.outsideChange());
		assertFalse(window.rigtuneChanged());
		assertFalse(window.nothingRecorded());
	}

	@Test
	void theCursorWinsOverTheClock() {
		// Written after e1 but with a clock that was behind: in the window by cursor, not by time.
		JournalEntry skewed = new JournalEntry("e1b", "2026-09-19T00:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null,
				List.of(JournalChange.setting("vanilla.clouds", "1", "0", JournalChange.APPLIED, null)));
		List<HistoryModel.Entry> entries = history(BEFORE, skewed, UPDATE, SETTING);
		assertEquals("e1b Clouds: 1 -> 0", described(ChangeWindow.between(baseline(), latest(), entries)).getFirst());
		ChangeWindow byTime = ChangeWindow.between(TrendFixtures.run("base").at("2026-09-21T10:00:00Z").cursor(null).build(), latest(), entries);
		assertFalse(byTime.byCursor());
		assertEquals(EXPECTED, described(byTime));
	}

	@Test
	void aPrunedCursorFallsBackToTimestamps() {
		// history.json keeps 50 entries: the baseline's cursor may be gone.
		ChangeWindow window = ChangeWindow.between(TrendFixtures.run("base").at("2026-09-21T10:00:00Z").cursor("pruned").build(), latest(),
				history(UPDATE, NOT_IN_EFFECT, SETTING, AFTER));
		assertFalse(window.byCursor());
		assertEquals(EXPECTED, described(window));
	}

	@Test
	void byTimestampsWithoutCursors() {
		// 0.3.0 runs (or runs a 0.3.0 rewrite stripped) have no cursor: (baseline.createdAt, latest.createdAt].
		BenchmarkRecord base = TrendFixtures.run("base").at("2026-09-21T10:00:00Z").build();
		BenchmarkRecord last = TrendFixtures.run("latest").at("2026-09-24T08:00:00Z").build();
		ChangeWindow window = ChangeWindow.between(base, last, history(BEFORE, UPDATE, NOT_IN_EFFECT, SETTING, AFTER));
		assertFalse(window.byCursor());
		assertEquals(EXPECTED, described(window));
	}

	@Test
	void aRigTuneVersionChange() {
		BenchmarkRecord base = TrendFixtures.run("base").at("2026-09-21T10:00:00Z").version("0.3.0+mc26.2").build();
		ChangeWindow window = ChangeWindow.between(base, TrendFixtures.run("latest").at("2026-09-21T11:00:00Z").build(), history(BEFORE));
		assertTrue(window.rigtuneChanged());
		assertEquals("0.3.0+mc26.2", window.rigtuneFrom());
		assertEquals("0.4.0+mc26.2", window.rigtuneTo());
		assertTrue(window.items().isEmpty());
		assertFalse(window.nothingRecorded());
	}

	@Test
	void aDifferentModSetWithoutAJournalModChangeIsOutsideRigTune() {
		BenchmarkRecord changed = TrendFixtures.run("latest").at("2026-09-25T10:00:00Z").hash("hash-b").build();
		ChangeWindow settingsOnly = ChangeWindow.between(TrendFixtures.run("base").at("2026-09-23T13:00:00Z").build(), changed,
				history(BEFORE, UPDATE, NOT_IN_EFFECT, SETTING));
		assertEquals(List.of("e4 Render Distance: 16 -> 14"), described(settingsOnly));
		assertTrue(settingsOnly.outsideChange());
		assertFalse(settingsOnly.nothingRecorded());
		// A mod change in the window may explain it: not claimed.
		assertFalse(ChangeWindow.between(TrendFixtures.run("base").at("2026-09-21T10:00:00Z").build(), changed,
				history(BEFORE, UPDATE, SETTING)).outsideChange());
		// An unknown hash on either side: nothing claimed.
		assertFalse(ChangeWindow.between(TrendFixtures.run("base").at("2026-09-23T13:00:00Z").hash(null).build(), changed, history(SETTING))
				.outsideChange());
		assertTrue(ChangeWindow.between(TrendFixtures.run("base").at("2026-09-23T13:00:00Z").build(), changed, history()).outsideChange());
	}

	@Test
	void anEmptyWindow() {
		ChangeWindow window = ChangeWindow.between(TrendFixtures.run("base").at("2026-09-24T09:00:00Z").build(),
				TrendFixtures.run("latest").at("2026-09-25T10:00:00Z").build(), history(BEFORE, UPDATE, NOT_IN_EFFECT, SETTING, AFTER));
		assertTrue(window.items().isEmpty());
		assertTrue(window.nothingRecorded());
		// Only changes that never took effect: nothing recorded either.
		assertTrue(ChangeWindow.between(TrendFixtures.run("base").at("2026-09-23T00:00:00Z").build(),
				TrendFixtures.run("latest").at("2026-09-23T23:00:00Z").build(), history(NOT_IN_EFFECT)).nothingRecorded());
		// Unreadable run times: nothing can be placed.
		assertTrue(ChangeWindow.between(TrendFixtures.run("base").at("later").build(), TrendFixtures.run("latest").at("2026-09-25T10:00:00Z").build(), history(UPDATE))
				.items().isEmpty());
	}
}
