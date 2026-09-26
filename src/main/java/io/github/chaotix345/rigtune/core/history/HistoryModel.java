package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.history.ApplyFailures.Failure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// What the History screen shows (docs/v0.3/SPEC.md item 6): history.json's entries newest first, each with its kind,
// time, versions, a count of settings and mods, and its changes with their status; a staged change whose op failed at
// the last exit, or an abandoned one, carries the helper's reason (3e). Label keys are en_us.json keys; setting names and
// values go through Labels, as the Undo screen shows them.
public final class HistoryModel {
	private static final String PREFIX = "rigtune.history.";
	private static final Set<String> STATUSES = Set.of(JournalChange.APPLIED, JournalChange.STAGED, JournalChange.ABANDONED,
			JournalChange.DISCARDED, JournalChange.REVERTED);
	private static final Map<String, String> KINDS = Map.of(JournalEntry.APPLY, "apply", JournalEntry.BENCHMARK, "benchmark",
			JournalEntry.UNDO, "undo", JournalEntry.LEGACY_IMPORT, "legacy_import");

	private HistoryModel() {
	}

	public interface Labels {
		Labels RAW = new Labels() {
		};

		default String label(String key) {
			return UndoPlanner.label(key);
		}

		default String value(String key, String value) {
			return value;
		}

		static Labels of(UndoPlanner.State state) {
			return new Labels() {
				@Override
				public String label(String key) {
					return state.label(key);
				}

				@Override
				public String value(String key, String value) {
					return state.value(key, value);
				}
			};
		}
	}

	// SETTING: label: before -> after. ADDED/DISABLED/REENABLED: file. UPDATED (review B-L1): a disable and an enable of
	// the same mod in one group, file -> newFile.
	public enum Row { SETTING, ADDED, DISABLED, REENABLED, UPDATED }

	// before/after: shown values, null when the key was absent. failure: why its op wasn't applied at the last exit.
	// name (docs/v0.4/SPEC.md 2c): the mod's display name when staging recorded it (an update: the new jar's), else null.
	public record Change(Row row, List<String> changeIds, String status, String label, String before, String after, String file, String newFile,
			String modId, Failure failure, String name) {
		public Change {
			changeIds = List.copyOf(changeIds);
		}

		public Change(Row row, List<String> changeIds, String status, String label, String before, String after, String file, String newFile,
				String modId, Failure failure) {
			this(row, changeIds, status, label, before, after, file, newFile, modId, failure, null);
		}

		// What a file row names: the mod, else its file.
		public String shownName() {
			return name != null ? name : file;
		}

		public String statusKey() {
			return HistoryModel.statusKey(status);
		}
	}

	// undoOf/undoOfAt: for an undo entry, the undone entry's id (or "all") and when it was. undoable: Undo this is offered.
	public record Entry(String id, String kind, String at, String rigtuneVersion, String mcVersion, String undoOf, String undoOfAt, boolean undoable,
			List<Change> changes) {
		public Entry {
			changes = List.copyOf(changes);
		}

		public String kindKey() {
			return HistoryModel.kindKey(kind);
		}

		public int settings() {
			return (int) changes.stream().filter(c -> c.row() == Row.SETTING).count();
		}

		public int mods() {
			return changes.size() - settings();
		}
	}

	// state: what history.json holds (Journal.state()); entries: newest first, empty unless state is OK.
	public record View(Journal.State state, List<Entry> entries) {
		public View {
			entries = List.copyOf(entries);
		}
	}

	// docs/v0.4/SPEC.md 2a: whether Undo last / Undo all have anything to act on.
	public static boolean anyUndoable(View view) {
		return view != null && view.entries().stream().anyMatch(Entry::undoable);
	}

	public static String statusKey(String status) {
		return PREFIX + "status." + (status != null && STATUSES.contains(status) ? status.toLowerCase(Locale.ROOT) : "unknown");
	}

	public static String kindKey(String kind) {
		return PREFIX + "kind." + (kind == null ? "unknown" : KINDS.getOrDefault(kind, "unknown"));
	}

	// failures: ApplyFailures.byOpId of last-apply.json.
	public static View build(Journal.State state, List<JournalEntry> entries, Map<String, Failure> failures, Labels labels) {
		Set<String> undoable = UndoPlanner.undoable(entries);
		Map<String, String> atById = new HashMap<>();
		entries.forEach(e -> atById.putIfAbsent(e.id(), e.at()));
		List<Entry> out = new ArrayList<>();
		for (int i = entries.size() - 1; i >= 0; i--) {
			JournalEntry e = entries.get(i);
			String undoOfAt = e.undoOf() == null || UndoPlanner.ALL.equals(e.undoOf()) ? null : atById.get(e.undoOf());
			out.add(new Entry(e.id(), e.kind(), e.at(), e.rigtuneVersion(), e.mcVersion(), e.undoOf(), undoOfAt, undoable.contains(e.id()),
					rows(e, failures, labels)));
		}
		return new View(state, out);
	}

	private static List<Change> rows(JournalEntry entry, Map<String, Failure> failures, Labels labels) {
		List<JournalChange> changes = entry.changes();
		Set<JournalChange> paired = new HashSet<>();
		List<Change> out = new ArrayList<>();
		for (JournalChange c : changes) {
			if (paired.contains(c)) {
				continue;
			}
			if (c.isSetting()) {
				out.add(new Change(Row.SETTING, List.of(c.id()), c.status(), c.key() == null ? "?" : labels.label(c.key()),
						shown(labels, c.key(), c.before()), shown(labels, c.key(), c.after()), null, null, null, failure(c, failures)));
				continue;
			}
			JournalChange partner = partner(c, changes, paired);
			if (partner != null) {
				paired.add(partner);
				JournalChange off = JournalChange.DISABLE.equals(c.action()) ? c : partner;
				JournalChange on = off == c ? partner : c;
				// The disable runs first, so its reason is the cause ("Not applied because disabling x failed" for the enable).
				Failure failure = failure(off, failures);
				out.add(new Change(Row.UPDATED, List.of(off.id(), on.id()), c.status(), null, null, null, off.file(), on.file(), on.modId(),
						failure != null ? failure : failure(on, failures), name(on.modName() != null ? on : off)));
				continue;
			}
			Row row = JournalChange.DISABLE.equals(c.action()) ? Row.DISABLED : c.reverts() != null ? Row.REENABLED : Row.ADDED;
			out.add(new Change(row, List.of(c.id()), c.status(), null, null, null, c.file(), null, c.modId(), failure(c, failures), name(c)));
		}
		return out;
	}

	// The other half of an update: a disable and an added (not re-enabled) jar of the same mod, same group and status.
	private static JournalChange partner(JournalChange c, List<JournalChange> changes, Set<JournalChange> paired) {
		if (!c.isFile() || c.group() == null || c.modId() == null || c.reverts() != null && JournalChange.ENABLE.equals(c.action())) {
			return null;
		}
		String other = JournalChange.DISABLE.equals(c.action()) ? JournalChange.ENABLE : JournalChange.DISABLE;
		for (JournalChange p : changes) {
			if (p != c && !paired.contains(p) && p.isFile() && other.equals(p.action()) && c.group().equals(p.group())
					&& c.modId().equals(p.modId()) && Objects.equals(c.status(), p.status())
					&& !(JournalChange.ENABLE.equals(p.action()) && p.reverts() != null)) {
				return p;
			}
		}
		return null;
	}

	// history.json is the player's file too: a hand-edited name gets the same sanitising as one read from a jar.
	private static String name(JournalChange c) {
		return ModJars.sanitizeName(c.modName());
	}

	private static String shown(Labels labels, String key, String value) {
		return value == null || key == null ? value : labels.value(key, value);
	}

	// A staged change whose op failed at the last exit, or an abandoned one the helper gave up on.
	private static Failure failure(JournalChange c, Map<String, Failure> failures) {
		Failure f = c.opId() == null ? null : failures.get(c.opId());
		if (f == null) {
			return null;
		}
		boolean matches = JournalChange.STAGED.equals(c.status()) && f.status() == ApplyResult.Status.FAILED
				|| JournalChange.ABANDONED.equals(c.status()) && f.status() == ApplyResult.Status.ABANDONED;
		return matches ? f : null;
	}
}
