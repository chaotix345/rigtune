package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

// docs/v0.5/SPEC.md 8 (C02): whether this player is new to RigTune, from what's on disk (nothing is stored for it). New:
// history.json is missing or has no entries, and there's neither a last-apply.json nor a pending.json (a 0.1.x instance
// whose preLaunch couldn't take the apply lock has those but no history.json yet). Any entry (an Apply, an Undo, a kept
// benchmark, the 0.1 import, a baseline) makes a returning player, and so does a history.json that can't be read.
public final class FirstRun {
	// UNKNOWN until client/FirstRunService.load() has run (a load that finishes after an Apply never turns RETURNING back).
	public enum Status {
		UNKNOWN,
		NEW,
		RETURNING
	}

	private FirstRun() {
	}

	public static boolean isNew(Journal.State state, List<JournalEntry> entries, boolean lastApplyExists, boolean pendingExists) {
		boolean noHistory = state == Journal.State.MISSING || state == Journal.State.OK && entries.isEmpty();
		return noHistory && !lastApplyExists && !pendingExists;
	}

	// Reads only: history.json once (Journal.holdsNoEntries, the same rule as above), and whether the helper's two files
	// exist.
	public static boolean isNew(Journal journal, Path configDir) {
		return journal.holdsNoEntries() && !Files.exists(ApplyResult.defaultPath(configDir)) && !Files.exists(PendingActions.defaultPath(configDir));
	}
}
