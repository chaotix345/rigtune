package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.UnfinishedGroups.Rename;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Audit M2 (docs/v0.4/SPEC.md 2o): staged groups the helper left half done at the last exit. Dropping such a group (Undo,
// Discard pending) would leave, say, the disabled jar disabled and its replacement never enabled; the next exit finishes
// it or rolls it back instead. Two ways to tell:
// - A failed rollback: a DISABLE the helper already tried (attempts > 0) that is done (its jar is gone and a .disabled
//   copy is there) while an ENABLE of the same group isn't (its download is still there and its target isn't).
// - A helper killed between two renames (review-8 AH-1), which counts no attempt: its record (UnfinishedGroups) names a
//   rename of the group that is in effect (exactly the recorded new name there, the old one gone) while another file op of
//   the group is left (an enable's target missing, a disable's jar still there).
// A group the helper never ran isn't one, even next to an old .disabled copy of the jar.
public final class PartlyApplied {
	private PartlyApplied() {
	}

	// files: the names in the mods folder now.
	public static Set<String> groups(List<Op> ops, Set<String> files) {
		return groups(ops, files, List.of());
	}

	// recorded: the helper's record of the renames it started (UnfinishedGroups.recorded).
	public static Set<String> groups(List<Op> ops, Set<String> files, Collection<Rename> recorded) {
		Set<String> disabledDone = new HashSet<>();
		Set<String> enablePending = new HashSet<>();
		Set<String> recordedDone = new HashSet<>();
		Set<String> left = new HashSet<>();
		for (Op op : ops) {
			if (op == null || op.type() == null || op.group() == null) {
				continue;
			}
			if (op.type() == PendingActions.Type.DISABLE_FILE && op.path() != null && op.attempts() > 0) {
				String name = HistoryUpdates.fileName(op.path());
				String disabled = name + ".disabled";
				if (!files.contains(name) && files.stream().anyMatch(f -> f.equals(disabled) || f.startsWith(disabled + "."))) {
					disabledDone.add(op.group());
				}
			} else if (op.type() == PendingActions.Type.ENABLE_FILE && op.from() != null && op.to() != null) {
				if (files.contains(HistoryUpdates.fileName(op.from())) && !files.contains(HistoryUpdates.fileName(op.to()))) {
					enablePending.add(op.group());
				}
			}
			if (inEffect(op, files, recorded)) {
				recordedDone.add(op.group());
			} else if (isLeft(op, files)) {
				left.add(op.group());
			}
		}
		disabledDone.retainAll(enablePending);
		recordedDone.retainAll(left);
		disabledDone.addAll(recordedDone);
		return disabledDone;
	}

	// The record names this op's rename, and it is in effect: the recorded new name is there and the old one isn't.
	private static boolean inEffect(Op op, Set<String> files, Collection<Rename> recorded) {
		if (op.id() == null) {
			return false;
		}
		for (Rename r : recorded) {
			if (r == null || !op.id().equals(r.op()) || r.from() == null || r.to() == null) {
				continue;
			}
			String from = HistoryUpdates.fileName(r.from());
			String to = HistoryUpdates.fileName(r.to());
			boolean same = switch (op.type()) {
				case ENABLE_FILE -> op.from() != null && op.to() != null && from.equals(HistoryUpdates.fileName(op.from()))
						&& to.equals(HistoryUpdates.fileName(op.to()));
				case DISABLE_FILE -> op.path() != null && from.equals(HistoryUpdates.fileName(op.path())) && to.startsWith(from + ".disabled");
				case PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES -> false;
			};
			if (same && files.contains(to) && !files.contains(from)) {
				return true;
			}
		}
		return false;
	}

	// A file op whose rename hasn't happened: an enable whose target isn't there, a disable whose jar still is.
	private static boolean isLeft(Op op, Set<String> files) {
		return switch (op.type()) {
			case ENABLE_FILE -> op.to() != null && !files.contains(HistoryUpdates.fileName(op.to()));
			case DISABLE_FILE -> op.path() != null && files.contains(HistoryUpdates.fileName(op.path()));
			case PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES -> false;
		};
	}
}
