package io.github.chaotix345.rigtune.core.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md C1, L8: an entry rebuilt with new changes (a status update, changes added to its Apply) keeps its
// foldedEntryIds.
class FoldedEntryIdsKeptTest {
	private static final List<String> FOLDED = List.of("entry-0", "entry-00");

	private static JournalEntry baseline() {
		JournalChange setting = JournalChange.setting("vanilla.renderDistance", "12", "8", JournalChange.STAGED, null);
		return new JournalEntry("entry-1", "2026-09-26T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null, List.of(setting))
				.withFoldedEntryIds(FOLDED);
	}

	@Test
	void aStatusUpdateKeepsThem() {
		List<JournalEntry> mapped = HistoryUpdates.map(List.of(baseline()), c -> c.withStatus(JournalChange.APPLIED));
		assertEquals(JournalChange.APPLIED, mapped.getFirst().changes().getFirst().status(), "the change was rebuilt");
		assertEquals(FOLDED, mapped.getFirst().foldedEntryIds());
	}

	@Test
	void addedChangesKeepThem(@TempDir Path dir) {
		Journal journal = new Journal(dir, "0.5.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		JournalChange more = JournalChange.setting("vanilla.simulationDistance", "12", "8", JournalChange.APPLIED, null);
		List<JournalEntry> out = journal.withChanges(List.of(baseline()), "entry-1", JournalEntry.APPLY, List.of(more));
		assertEquals(2, out.getFirst().changes().size());
		assertEquals(FOLDED, out.getFirst().foldedEntryIds());
	}
}
