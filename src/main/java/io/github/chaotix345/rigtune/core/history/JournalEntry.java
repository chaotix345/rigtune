package io.github.chaotix345.rigtune.core.history;

import java.util.List;

// One Apply, benchmark "Keep", undo or legacy import. kind: "apply", "benchmark", "undo" or "legacy-import".
// undoOf: for kind "undo", the undone entry's id or "all".
public record JournalEntry(String id, String at, String kind, String rigtuneVersion, String mcVersion, String undoOf,
		List<JournalChange> changes) {
	public static final String APPLY = "apply";
	public static final String BENCHMARK = "benchmark";
	public static final String UNDO = "undo";
	public static final String LEGACY_IMPORT = "legacy-import";

	public JournalEntry {
		changes = changes == null ? List.of() : List.copyOf(changes);
	}
}
