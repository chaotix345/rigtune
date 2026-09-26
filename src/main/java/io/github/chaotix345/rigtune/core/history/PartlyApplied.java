package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Audit M2 (docs/v0.4/SPEC.md 2o): staged groups the helper left half done at the last exit (the enable failed and so
// did the rollback): a DISABLE the helper already tried (attempts > 0) that is done (its jar is gone and a .disabled copy
// is there) while an ENABLE of the same group isn't (its download is still there and its target isn't). A group the
// helper never ran isn't one, even next to an old .disabled copy of the jar. Dropping such a group (Undo, Discard
// pending) would leave the disabled jar disabled and its replacement never enabled; the next exit finishes it instead.
public final class PartlyApplied {
	private PartlyApplied() {
	}

	// files: the names in the mods folder now.
	public static Set<String> groups(List<Op> ops, Set<String> files) {
		Set<String> disabledDone = new HashSet<>();
		Set<String> enablePending = new HashSet<>();
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
		}
		disabledDone.retainAll(enablePending);
		return disabledDone;
	}
}
