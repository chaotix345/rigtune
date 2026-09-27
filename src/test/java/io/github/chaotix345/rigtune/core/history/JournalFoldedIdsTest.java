package io.github.chaotix345.rigtune.core.history;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md L8 (AC2H.3): the baseline the cap folds entries into records their ids (foldedEntryIds, at most 50,
// the newest kept), so a folded profile switch keeps its "Profile: X" label and a record that names a folded entry (C09,
// C20) still finds it. JournalFoldTest (M6, the 400-seed Undo-all property) is unchanged.
class JournalFoldedIdsTest {
	private static final String FPS = "vanilla.maxFps";
	private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

	private static JournalEntry apply(String id, int minute) {
		return new JournalEntry(id, START.plusSeconds(60L * minute).toString(), JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null,
				List.of(JournalChange.setting(FPS, String.valueOf(minute), String.valueOf(minute + 1), JournalChange.APPLIED, null)));
	}

	private static List<JournalEntry> written(int count) {
		List<JournalEntry> capped = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			capped.add(apply("e" + i, i));
			capped = Journal.cap(capped);
		}
		return capped;
	}

	private static List<String> ids(int from, int to) {
		return IntStream.range(from, to).mapToObj(i -> "e" + i).toList();
	}

	@Test
	void theFoldRecordsTheFoldedIds() {
		List<JournalEntry> many = new ArrayList<>();
		for (int i = 0; i < 53; i++) {
			many.add(apply("e" + i, i));
		}

		JournalEntry baseline = Journal.cap(many).getFirst();

		assertTrue(Journal.isBaseline(baseline));
		assertEquals(ids(0, 4), baseline.foldedEntryIds());
	}

	// Every later write folds into the same baseline (its id kept): its list grows by the entries it takes, oldest first,
	// never with its own id.
	@Test
	void aBaselineThatKeepsItsIdAddsTheEntriesItTakes() {
		List<JournalEntry> capped = written(70);

		JournalEntry baseline = capped.getFirst();
		assertEquals(ids(0, 21), baseline.foldedEntryIds());
		assertEquals(ids(21, 70), capped.subList(1, capped.size()).stream().map(JournalEntry::id).toList());
	}

	// A baseline inside a later run (an older entry had a staged change when it was made) passes its folded ids and its own
	// id on to the new baseline.
	@Test
	void aBaselineAbsorbedByALaterFoldPassesItsIdsOn() {
		List<JournalEntry> many = new ArrayList<>();
		many.add(apply("x", 0));
		many.add(new JournalEntry(Journal.BASELINE + "old", START.plusSeconds(60).toString(), JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null,
				List.of(JournalChange.setting(FPS, "1", "2", JournalChange.APPLIED, null))).withFoldedEntryIds(List.of("p", "q")));
		for (int i = 2; i < 52; i++) {
			many.add(apply("e" + i, i));
		}

		JournalEntry baseline = Journal.cap(many).getFirst();

		assertTrue(Journal.isBaseline(baseline));
		assertEquals(List.of("x", "p", "q", Journal.BASELINE + "old", "e2"), baseline.foldedEntryIds());
	}

	@Test
	void atMost50IdsAreKeptTheNewest() {
		JournalEntry baseline = written(120).getFirst();

		assertEquals(ids(21, 71), baseline.foldedEntryIds());
	}

	@Test
	void anEntryThatIsNoBaselineHasNone() {
		assertNull(written(49).getFirst().foldedEntryIds());
		assertNull(written(70).get(1).foldedEntryIds());
	}

	// The ids the journal still accounts for (ProfileService prunes the switch labels against them), and the entry that
	// holds a folded id.
	@Test
	void idsWithFoldedAndHoldingFindAFoldedEntry() {
		List<JournalEntry> capped = written(55);
		JournalEntry baseline = capped.getFirst();

		Set<String> ids = Journal.idsWithFolded(capped);
		assertTrue(ids.containsAll(ids(0, 55)), ids.toString());
		assertTrue(ids.contains(baseline.id()));
		assertEquals(56, ids.size());
		assertEquals(baseline, Journal.holding(capped, "e3"));
		assertEquals(capped.getLast(), Journal.holding(capped, "e54"));
		assertNull(Journal.holding(capped, "nothing"));
		assertEquals(Set.of(), Journal.idsWithFolded(List.of()));
	}

	// history.json is the player's file too: only a baseline's foldedEntryIds count, and at most its newest MAX_FOLDED_IDS
	// (coordinator review L4).
	@Test
	void onlyABaselinesNewestFoldedIdsCount() {
		JournalEntry edited = apply("plain", 0).withFoldedEntryIds(List.of("x", "y"));
		List<String> many = IntStream.range(0, 60).mapToObj(i -> "f" + i).toList();
		JournalEntry baseline = new JournalEntry(Journal.BASELINE + "b", START.toString(), JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null,
				List.of()).withFoldedEntryIds(many);

		assertEquals(List.of(), Journal.folded(edited));
		assertEquals(many.subList(10, 60), Journal.folded(baseline));
		assertEquals(Set.of("plain", Journal.BASELINE + "b"), Set.copyOf(Journal.idsWithFolded(List.of(edited, baseline)).stream()
				.filter(id -> !id.startsWith("f")).toList()));
		assertEquals(50, Journal.idsWithFolded(List.of(baseline)).size() - 1);
		assertNull(Journal.holding(List.of(edited), "x"));
		assertNull(Journal.holding(List.of(baseline), "f0"));
		assertEquals(baseline, Journal.holding(List.of(baseline), "f59"));
		assertEquals(List.of(), HistoryModel.build(Journal.State.OK, List.of(edited), Map.of(), HistoryModel.Labels.RAW).entries().getFirst().folded());
	}

	// The fold keeps UndoPlanner's view unchanged: the same changes as before L8 (the list is the only addition).
	@Test
	void theFoldedChangesAreUnchanged() {
		List<JournalEntry> many = new ArrayList<>();
		for (int i = 0; i < 53; i++) {
			many.add(apply("e" + i, i));
		}
		JournalEntry baseline = Journal.cap(many).getFirst();

		assertEquals(Map.of(FPS, "0->4"), Map.of(baseline.changes().getFirst().key(),
				baseline.changes().getFirst().before() + "->" + baseline.changes().getFirst().after()));
		assertEquals(1, baseline.changes().size());
	}
}
