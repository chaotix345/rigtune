package io.github.chaotix345.rigtune.v030.core.history;

import java.util.List;
import java.util.UUID;

// How any feature records a change in config/rigtune/history.json without depending on the journal's internals.
// Calls with the same entryId add to one journal entry, so one Apply (immediate settings, staged config patches and
// downloads that finish later) is one entry. The undo workstream installs the real recorder at client start; until then
// changes go nowhere.
public interface ChangeRecorder {
	ChangeRecorder NONE = (entryId, kind, changes) -> {
	};

	void record(String entryId, String kind, List<JournalChange> changes);

	static String newEntryId() {
		return UUID.randomUUID().toString();
	}

	static ChangeRecorder current() {
		return Holder.current;
	}

	static void install(ChangeRecorder recorder) {
		Holder.current = recorder == null ? NONE : recorder;
	}

	final class Holder {
		private static volatile ChangeRecorder current = NONE;

		private Holder() {
		}
	}
}
