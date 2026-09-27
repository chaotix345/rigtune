package io.github.chaotix345.rigtune.core.history;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

// One Apply, benchmark "Keep", undo or legacy import. kind: "apply", "benchmark", "undo" or "legacy-import".
// undoOf: for kind "undo", the undone entry's id or "all".
// v0.5 (docs/v0.5/SPEC.md C1, L8; optional): foldedEntryIds, on a baseline entry, the ids of the entries folded into it,
// so their profile labels survive the fold. Null when absent (older files); a null isn't written; 0.4.0/0.3.0 ignore it
// and drop it on rewrite.
public record JournalEntry(String id, String at, String kind, String rigtuneVersion, String mcVersion, String undoOf,
		List<JournalChange> changes, @Nullable List<String> foldedEntryIds) {
	public static final String APPLY = "apply";
	public static final String BENCHMARK = "benchmark";
	public static final String UNDO = "undo";
	public static final String LEGACY_IMPORT = "legacy-import";

	public JournalEntry {
		changes = changes == null ? List.of() : changes.stream().filter(Objects::nonNull).toList();
		foldedEntryIds = foldedEntryIds == null ? null : foldedEntryIds.stream().filter(Objects::nonNull).toList();
	}

	public JournalEntry(String id, String at, String kind, String rigtuneVersion, String mcVersion, String undoOf, List<JournalChange> changes) {
		this(id, at, kind, rigtuneVersion, mcVersion, undoOf, changes, null);
	}

	public JournalEntry withFoldedEntryIds(@Nullable List<String> ids) {
		return new JournalEntry(id, at, kind, rigtuneVersion, mcVersion, undoOf, changes, ids);
	}
}
