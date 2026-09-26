package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Self-review finding 1: an undone switch no longer makes its profile active.
class ActiveProfileTest {
	private static JournalEntry entry(String id, String... statuses) {
		List<JournalChange> changes = java.util.Arrays.stream(statuses).map(s -> JournalChange.setting("vanilla.maxFps", "120", "60", s, null)).toList();
		return new JournalEntry(id, "2026-09-26T10:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null, changes);
	}

	@Test
	void aSwitchStandsWhileAnyOfItsChangesIsAppliedOrStaged() {
		assertTrue(ActiveProfile.inEffect("e", Journal.State.OK, List.of(entry("e", JournalChange.APPLIED, JournalChange.REVERTED))));
		assertTrue(ActiveProfile.inEffect("e", Journal.State.OK, List.of(entry("e", JournalChange.STAGED))));
		assertFalse(ActiveProfile.inEffect("e", Journal.State.OK, List.of(entry("e", JournalChange.REVERTED, JournalChange.DISCARDED))));
		assertFalse(ActiveProfile.inEffect("e", Journal.State.OK, List.of(entry("other", JournalChange.APPLIED))), "the entry is gone");
		assertFalse(ActiveProfile.inEffect("e", Journal.State.MISSING, List.of()), "history.json was deleted");
	}

	@Test
	void noEntryOrAnUnreadableJournalDecidesNothing() {
		assertTrue(ActiveProfile.inEffect(null, Journal.State.OK, List.of()));
		assertTrue(ActiveProfile.inEffect("e", Journal.State.NEWER, List.of()));
		assertTrue(ActiveProfile.inEffect("e", Journal.State.CORRUPT, List.of()));
		assertTrue(ActiveProfile.inEffect("e", Journal.State.UNREADABLE, List.of()));
	}
}
