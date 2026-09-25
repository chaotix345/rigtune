package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

// The status changes of journal entries (docs/v0.2/SPEC.md item 3). Pure, and safe on the helper's classpath.
public final class HistoryUpdates {
	private HistoryUpdates() {
	}

	// What the helper did, by op id: OK / SKIPPED_ALREADY_DONE -> APPLIED (with the group the op ran in, and a
	// disable's actual file name), ABANDONED -> ABANDONED, FAILED -> unchanged (it stays staged and retries). Only
	// STAGED changes move. An undo change that becomes APPLIED marks the change it reverts REVERTED.
	public static List<JournalEntry> applyResults(List<JournalEntry> entries, List<OpResult> results) {
		Map<String, OpResult> byOp = new HashMap<>();
		for (OpResult r : results) {
			if (r != null && r.op() != null && r.op().id() != null && r.status() != null) {
				byOp.put(r.op().id(), r);
			}
		}
		Set<String> reverted = new HashSet<>();
		List<JournalEntry> out = map(entries, c -> {
			OpResult r = JournalChange.STAGED.equals(c.status()) && c.opId() != null ? byOp.get(c.opId()) : null;
			if (r == null) {
				return c;
			}
			return switch (r.status()) {
				case OK, SKIPPED_ALREADY_DONE -> {
					if (c.reverts() != null) {
						reverted.add(c.reverts());
					}
					JournalChange applied = c.withStatus(JournalChange.APPLIED);
					if (c.isFile()) {
						applied = applied.withGroup(r.op().group());
						// Only from an op on this change's file: an undo change can follow its group's first op instead.
						if (JournalChange.DISABLE.equals(c.action()) && r.resultPath() != null && r.op().path() != null
								&& fileName(r.op().path()).equals(c.file())) {
							applied = applied.withResultFile(fileName(r.resultPath()));
						}
					}
					yield applied;
				}
				case ABANDONED -> c.withStatus(JournalChange.ABANDONED);
				case FAILED -> c;
			};
		});
		return reverted.isEmpty() ? out : revert(out, reverted);
	}

	// Staged changes whose op was dropped from pending.json.
	public static List<JournalEntry> discard(List<JournalEntry> entries, Collection<String> opIds) {
		Set<String> ids = new HashSet<>(opIds);
		return map(entries, c -> JournalChange.STAGED.equals(c.status()) && c.opId() != null && ids.contains(c.opId())
				? c.withStatus(JournalChange.DISCARDED) : c);
	}

	// Applied changes an undo has put back.
	public static List<JournalEntry> revert(List<JournalEntry> entries, Collection<String> changeIds) {
		Set<String> ids = new HashSet<>(changeIds);
		return map(entries, c -> JournalChange.APPLIED.equals(c.status()) && ids.contains(c.id()) ? c.withStatus(JournalChange.REVERTED) : c);
	}

	// At startup (review H5): first what the last helper run did (in case its journal update failed), then any change
	// still STAGED whose op isn't in pending.json any more is lost (a killed helper, a downgrade) -> ABANDONED.
	public static List<JournalEntry> reconcile(List<JournalEntry> entries, Set<String> pendingOpIds, List<OpResult> lastApply) {
		return map(applyResults(entries, lastApply), c -> JournalChange.STAGED.equals(c.status())
				&& (c.opId() == null || !pendingOpIds.contains(c.opId())) ? c.withStatus(JournalChange.ABANDONED) : c);
	}

	public static List<JournalEntry> append(List<JournalEntry> entries, JournalEntry entry) {
		List<JournalEntry> out = new ArrayList<>(entries);
		out.add(entry);
		return out;
	}

	static List<JournalEntry> map(List<JournalEntry> entries, UnaryOperator<JournalChange> change) {
		List<JournalEntry> out = new ArrayList<>(entries.size());
		for (JournalEntry e : entries) {
			List<JournalChange> changes = e.changes().stream().map(change).toList();
			out.add(changes.equals(e.changes()) ? e
					: new JournalEntry(e.id(), e.at(), e.kind(), e.rigtuneVersion(), e.mcVersion(), e.undoOf(), changes));
		}
		return out;
	}

	static String fileName(String path) {
		try {
			Path name = Path.of(path).getFileName();
			return name == null ? path : name.toString();
		} catch (InvalidPathException e) {
			return path;
		}
	}
}
