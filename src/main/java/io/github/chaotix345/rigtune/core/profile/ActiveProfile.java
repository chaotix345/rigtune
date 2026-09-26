package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.jspecify.annotations.Nullable;

import java.util.List;

// Whether the active profile in profiles.json still stands (self-review finding 1): the switch that made it active is a
// journal entry, and once Undo this / last / all reverted or cancelled all of it, nothing is on that profile any more, so the
// Profiles screen stops marking it and the battery offer stops treating it as active.
public final class ActiveProfile {
	private ActiveProfile() {
	}

	// entryId: ProfileStore.activeEntry (null: the switch changed nothing, which stands). A journal that can't be read decides
	// nothing (stands); a readable one without the entry, or whose entry has no applied or staged change left, voids it.
	public static boolean inEffect(@Nullable String entryId, Journal.State state, List<JournalEntry> entries) {
		if (entryId == null || state != Journal.State.OK && state != Journal.State.MISSING) {
			return true;
		}
		for (JournalEntry entry : entries) {
			if (entryId.equals(entry.id())) {
				return entry.changes().stream().anyMatch(c -> JournalChange.APPLIED.equals(c.status()) || JournalChange.STAGED.equals(c.status()));
			}
		}
		return false;
	}
}
