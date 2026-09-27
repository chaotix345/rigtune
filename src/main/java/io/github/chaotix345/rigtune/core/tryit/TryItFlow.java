package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
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
		if (history.state() != Journal.State.OK && history.state() != Journal.State.MISSING) {
			return new TryItView(Stage.HISTORY_UNREADABLE, t, before, after, null, null, null, same);
		}
		List<JournalEntry> entries = history.state() == Journal.State.OK ? history.entries() : List.of();
		int at = indexOf(entries, t.entryId());
		if (at < 0) {
			// Nothing journaled under the try's entry: the apply hasn't happened (or took nothing), unless the entry was
			// folded into a baseline or something shows the change was in effect (an after run; an after snapshot, taken
			// only when an after run starts).
			Stage stage = folded(entries, t.entryId()) || after != null || t.settingsAfter() != null ? Stage.NO_ENTRY
					: before == null ? (live.measuring() ? Stage.MEASURING_BEFORE : Stage.STOPPED_BEFORE)
					: live.applying() ? Stage.APPLYING : Stage.STOPPED_BEFORE;
			return new TryItView(stage, t, before, after, null, null, null, same);
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
			stage = failure != null && failure.status() == ApplyResult.Status.FAILED ? Stage.RETRYING : Stage.AWAITING_RESTART;
		} else if (JournalChange.DISCARDED.equals(status)) {
			stage = cancelled(entries, at, t.entryId()) ? Stage.CANCELLED : Stage.NOT_APPLIED;
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

	// Undo this on the entry (or Undo all) after it discarded the staged change: the player cancelled the try.
	private static boolean cancelled(List<JournalEntry> entries, int at, String entryId) {
		for (JournalEntry e : entries.subList(at + 1, entries.size())) {
			if (JournalEntry.UNDO.equals(e.kind()) && (entryId.equals(e.undoOf()) || UndoPlanner.ALL.equals(e.undoOf()))) {
				return true;
			}
		}
		return false;
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
