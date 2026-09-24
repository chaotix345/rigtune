package io.github.chaotix345.rigtune.core.history;

import java.util.List;

// How any feature records a change in config/rigtune/history.json without depending on the journal's internals.
// The undo workstream installs the real recorder at client start; until then changes go nowhere.
public interface ChangeRecorder {
	ChangeRecorder NONE = (kind, changes) -> {
	};

	void record(String kind, List<JournalChange> changes);

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
