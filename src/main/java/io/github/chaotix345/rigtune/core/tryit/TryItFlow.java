package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

// A try's stage (docs/v0.5/SPEC.md 6, docs/research/v0.5/feature-try-it.md §2.5), derived, never stored: from the try
// in tryit.json, its pair in benchmarks.json, its change in history.json (with the last helper run's failures), this
// game session and what the client is doing right now. So a crash or a quit at any point resumes or closes cleanly.
// The journal is read directly (not HistoryModel's view): the change's key, an undo's `reverts` link and a baseline's
// `foldedEntryIds` are needed. Pure; the client runs it on Probes.EXECUTOR (Journal.entries() reads the file).
public final class TryItFlow {
	// state: Journal.state(); entries: Journal.entries(), oldest first (ignored unless state is OK); failures:
	// ApplyFailures.byOpId(last-apply.json).
	public record History(Journal.State state, List<JournalEntry> entries, Map<String, ApplyFailures.Failure> failures) {
		public History {
			entries = List.copyOf(entries);
			failures = Map.copyOf(failures);
		}
	}

	// session: this game session's id. measuring: a run of the try's pair is running, queued, or finished with its
	// outcome not handled yet (so a derive never mistakes the moment between a run's save and its handling for a stop).
	// applying: the try's apply is waiting to run on the render thread.
	public record Live(String session, boolean measuring, boolean applying) {
	}

	private TryItFlow() {
	}

	// runs: benchmarks.json, oldest first.
	public static TryItView derive(@Nullable TryIt t, List<BenchmarkRecord> runs, History history, Live live) {
		return derive(t, runs, true, history, live);
	}

	// runsReadable: false while benchmarks.json can't be read (or is from a newer RigTune): its runs are unknown, not
	// gone, so nothing closes (as with an unreadable History; review BENCH-4).
	public static TryItView derive(@Nullable TryIt t, List<BenchmarkRecord> runs, boolean runsReadable, History history, Live live) {
		if (t == null) {
			return TryItView.EMPTY;
		}
		boolean same = t.session().equals(live.session());
		BenchmarkRecord before = null;
		BenchmarkRecord after = null;
		for (BenchmarkRecord r : runs) {
			if (!t.pairId().equals(r.pairId())) {
				continue;
			}
			if (BenchmarkRecord.BEFORE.equals(r.phase()) && before == null) {
				before = r;
			} else if (BenchmarkRecord.AFTER.equals(r.phase())) {
				after = r;
			}
		}
		if (!runsReadable || history.state() != Journal.State.OK && history.state() != Journal.State.MISSING) {
			return new TryItView(Stage.HISTORY_UNREADABLE, t, before, after, null, null, null, same);
		}
		List<JournalEntry> entries = history.state() == Journal.State.OK ? history.entries() : List.of();
		int at = indexOf(entries, t.entryId());
		if (at < 0) {
			return new TryItView(missing(t, before, after, entries, live), t, before, after, null, null, null, same);
		}
		JournalChange change = change(entries.get(at), t.key());
		if (change == null) {
			return new TryItView(Stage.NOT_APPLIED, t, before, after, null, null, null, same);
		}
		ApplyFailures.Failure failure = change.opId() == null ? null : history.failures().get(change.opId());
		String status = change.status();
		Stage stage;
		TryItVerdict.Verdict verdict = null;
		if (JournalChange.REVERTED.equals(status)) {
			stage = Stage.REVERTED;
		} else if (JournalChange.STAGED.equals(status)) {
			// The helper's last run: FAILED is retried at the next exit; ABANDONED means it gave up (the journal says so at
			// the next start).
			stage = failure == null ? Stage.AWAITING_RESTART : failure.abandoned() ? Stage.NOT_APPLIED : Stage.RETRYING;
		} else if (JournalChange.DISCARDED.equals(status)) {
			// Taken out of pending.json by the player (Cancel try, Undo this or all, Discard), whether or not the undo's own
			// entry was written.
			stage = Stage.CANCELLED;
		} else if (!JournalChange.APPLIED.equals(status)) {
			// ABANDONED (the helper gave up, or the op was lost), or a status a hand edit left.
			stage = Stage.NOT_APPLIED;
		} else if (change.id() != null && revertStaged(entries, change.id())) {
			stage = Stage.REVERT_PENDING;
		} else if (before == null) {
			stage = Stage.NO_BEFORE;
		} else if (live.measuring()) {
			stage = Stage.MEASURING_AFTER;
		} else if (after != null) {
			stage = Stage.RESULT;
			verdict = TryItVerdict.of(t, before, after, runs, entries);
		} else if (t.kind() == TryIt.Kind.NOW && same) {
			stage = Stage.READY;
		} else if (t.kind() == TryIt.Kind.NOW && t.scene() == BenchmarkRequest.Scene.CURRENT) {
			// The player's own spot, time and weather can't be matched after a restart.
			stage = Stage.INTERRUPTED;
		} else {
			stage = Stage.READY;
		}
		ApplyFailures.Failure shown = stage == Stage.RETRYING || stage == Stage.NOT_APPLIED ? failure : null;
		return new TryItView(stage, t, before, after, verdict, status, shown, same);
	}

	private static int indexOf(List<JournalEntry> entries, String id) {
		for (int i = 0; i < entries.size(); i++) {
			if (id.equals(entries.get(i).id())) {
				return i;
			}
		}
		return -1;
	}

	// The entry's change of the tried key (a Try it apply journals exactly one).
	private static @Nullable JournalChange change(JournalEntry entry, String key) {
		for (JournalChange c : entry.changes()) {
			if (c.isSetting() && key.equals(c.key())) {
				return c;
			}
		}
		return null;
	}

	// L8 (docs/v0.5/SPEC.md 2H): a baseline lists the entries folded into it.
	private static boolean folded(List<JournalEntry> entries, String entryId) {
		for (JournalEntry e : entries) {
			if (Journal.isBaseline(e) && e.foldedEntryIds() != null && e.foldedEntryIds().contains(entryId)) {
				return true;
			}
		}
		return false;
	}

	// Nothing journaled under the try's entry. In order: an undo of it is (the journal's cap drops a reverted or cancelled
	// entry before its undo); it was folded into a baseline (L8's foldedEntryIds); no before run yet; the chain is between
	// the before run and the apply; the apply never happened, proven only when nothing shows it did (an after run or an
	// after snapshot, both taken once the entry existed) and the journal never reached its cap (a cap leaves exactly
	// MAX_ENTRIES, so fewer means nothing was ever dropped); else the change may be in effect and the entry lost.
	private static Stage missing(TryIt t, @Nullable BenchmarkRecord before, @Nullable BenchmarkRecord after, List<JournalEntry> entries, Live live) {
		Stage undone = undone(entries, t);
		if (undone != null) {
			return undone;
		}
		if (folded(entries, t.entryId())) {
			return Stage.NO_ENTRY;
		}
		if (before == null) {
			return live.measuring() ? Stage.MEASURING_BEFORE : Stage.STOPPED_BEFORE;
		}
		if (live.measuring() || live.applying()) {
			return Stage.APPLYING;
		}
		boolean neverApplied = after == null && t.settingsAfter() == null && entries.size() < Journal.MAX_ENTRIES;
		return neverApplied ? Stage.STOPPED_BEFORE : Stage.ENTRY_MISSING;
	}

	// An undo of the try's entry: its change of the key applied -> REVERTED, staged -> REVERT_PENDING, else (nothing of
	// the key, or dropped) the try was cancelled.
	private static @Nullable Stage undone(List<JournalEntry> entries, TryIt t) {
		Stage out = null;
		for (JournalEntry e : entries) {
			if (!JournalEntry.UNDO.equals(e.kind()) || !t.entryId().equals(e.undoOf())) {
				continue;
			}
			for (JournalChange c : e.changes()) {
				if (c.isSetting() && t.key().equals(c.key())) {
					if (JournalChange.APPLIED.equals(c.status())) {
						return Stage.REVERTED;
					}
					if (JournalChange.STAGED.equals(c.status())) {
						out = Stage.REVERT_PENDING;
					}
				}
			}
			if (out == null) {
				out = Stage.CANCELLED;
			}
		}
		return out;
	}

	// An undo's change that reverts the try's change is staged for the next restart.
	private static boolean revertStaged(List<JournalEntry> entries, String changeId) {
		for (JournalEntry e : entries) {
			for (JournalChange c : e.changes()) {
				if (changeId.equals(c.reverts()) && JournalChange.STAGED.equals(c.status())) {
					return true;
				}
			}
		}
		return false;
	}
}
