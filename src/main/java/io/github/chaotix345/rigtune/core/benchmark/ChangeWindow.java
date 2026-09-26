package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

// What History recorded between two benchmark runs (docs/v0.4/SPEC.md 7, "Changes since then (may be related)"): the
// entries after the baseline's journalCursor up to the latest run's (both still in history.json), else the entries whose
// time is after the baseline and no later than the latest run. Only changes that took effect count (APPLIED, or
// REVERTED later): a staged, cancelled or abandoned change never ran during either benchmark. Also the RigTune version
// of each run, and whether the mod set changed without a mod change in the window ("something outside RigTune changed
// too"). Never a cause: the screens list these as possibly related.
// A staged change (a mod file, a Sodium/DH/Iris setting) is journaled when it's staged but only takes effect at the next
// start, which may be after the baseline run (review M1): so staged changes that took effect are also listed from the
// entries between the comparable run before the baseline and the baseline (or, without one, the entry at the baseline's
// cursor), before the window's own.
public record ChangeWindow(boolean byCursor, List<Item> items, @Nullable String rigtuneFrom, @Nullable String rigtuneTo, boolean outsideChange) {
	// One change row of a History entry, labelled as the History screen labels it.
	public record Item(String entryId, String entryKind, String at, HistoryModel.Change change) {
	}

	public ChangeWindow {
		items = List.copyOf(items);
	}

	public boolean rigtuneChanged() {
		return rigtuneFrom != null && rigtuneTo != null && !rigtuneFrom.equals(rigtuneTo);
	}

	public boolean nothingRecorded() {
		return items.isEmpty() && !rigtuneChanged() && !outsideChange;
	}

	// newestFirst: HistoryModel.View's entries (newest first, as History lists them).
	public static ChangeWindow between(BenchmarkRecord baseline, BenchmarkRecord latest, List<HistoryModel.Entry> newestFirst) {
		return between(null, baseline, latest, newestFirst, Set.of());
	}

	// previous: the comparable run before the baseline, or null. stagedChangeIds: the ids of journal changes that were
	// staged for the next start (a JournalChange with an opId); mod-file rows always were.
	public static ChangeWindow between(@Nullable BenchmarkRecord previous, BenchmarkRecord baseline, BenchmarkRecord latest,
			List<HistoryModel.Entry> newestFirst, Set<String> stagedChangeIds) {
		List<HistoryModel.Entry> oldestFirst = newestFirst.reversed();
		List<HistoryModel.Entry> window = byCursor(baseline, latest, oldestFirst);
		boolean byCursor = window != null;
		if (window == null) {
			window = byTime(baseline, latest, oldestFirst);
		}
		List<Item> items = new ArrayList<>();
		for (HistoryModel.Entry entry : carried(previous, baseline, oldestFirst)) {
			for (HistoryModel.Change change : entry.changes()) {
				boolean staged = change.row() != HistoryModel.Row.SETTING || change.changeIds().stream().anyMatch(stagedChangeIds::contains);
				if (staged && tookEffect(change)) {
					items.add(new Item(entry.id(), entry.kind(), entry.at(), change));
				}
			}
		}
		for (HistoryModel.Entry entry : window) {
			for (HistoryModel.Change change : entry.changes()) {
				if (tookEffect(change)) {
					items.add(new Item(entry.id(), entry.kind(), entry.at(), change));
				}
			}
		}
		String before = baseline.context() == null ? null : baseline.context().modSetHash();
		String after = latest.context() == null ? null : latest.context().modSetHash();
		boolean modChange = items.stream().anyMatch(i -> i.change().row() != HistoryModel.Row.SETTING);
		boolean outside = before != null && after != null && !before.equals(after) && !modChange;
		return new ChangeWindow(byCursor, items, baseline.rigtuneVersion(), latest.rigtuneVersion(), outside);
	}

	private static boolean tookEffect(HistoryModel.Change change) {
		return JournalChange.APPLIED.equals(change.status()) || JournalChange.REVERTED.equals(change.status());
	}

	// The entries whose staged changes may have taken effect only after the baseline run started.
	private static List<HistoryModel.Entry> carried(@Nullable BenchmarkRecord previous, BenchmarkRecord baseline, List<HistoryModel.Entry> oldestFirst) {
		if (previous != null) {
			List<HistoryModel.Entry> between = byCursor(previous, baseline, oldestFirst);
			return between != null ? between : byTime(previous, baseline, oldestFirst);
		}
		String cursor = baseline.context() == null ? null : baseline.context().journalCursor();
		int at = cursor == null ? -1 : indexOf(oldestFirst, cursor);
		if (at >= 0) {
			return oldestFirst.subList(at, at + 1);
		}
		// No cursor: the newest entry no later than the baseline run.
		Instant when = instant(baseline.createdAt());
		for (int i = oldestFirst.size() - 1; i >= 0 && when != null; i--) {
			Instant entryAt = instant(oldestFirst.get(i).at());
			if (entryAt != null && !entryAt.isAfter(when)) {
				return oldestFirst.subList(i, i + 1);
			}
		}
		return List.of();
	}

	// Null when either cursor is missing or no longer in the journal (capped at 50 entries), or they're out of order.
	private static @Nullable List<HistoryModel.Entry> byCursor(BenchmarkRecord baseline, BenchmarkRecord latest, List<HistoryModel.Entry> oldestFirst) {
		String from = baseline.context() == null ? null : baseline.context().journalCursor();
		String to = latest.context() == null ? null : latest.context().journalCursor();
		if (from == null || to == null) {
			return null;
		}
		int fromIndex = indexOf(oldestFirst, from);
		int toIndex = indexOf(oldestFirst, to);
		if (fromIndex < 0 || toIndex < fromIndex) {
			return null;
		}
		return oldestFirst.subList(fromIndex + 1, toIndex + 1);
	}

	private static int indexOf(List<HistoryModel.Entry> entries, String id) {
		for (int i = 0; i < entries.size(); i++) {
			if (Objects.equals(entries.get(i).id(), id)) {
				return i;
			}
		}
		return -1;
	}

	private static List<HistoryModel.Entry> byTime(BenchmarkRecord baseline, BenchmarkRecord latest, List<HistoryModel.Entry> oldestFirst) {
		Instant from = instant(baseline.createdAt());
		Instant to = instant(latest.createdAt());
		if (from == null || to == null) {
			return List.of();
		}
		List<HistoryModel.Entry> out = new ArrayList<>();
		for (HistoryModel.Entry entry : oldestFirst) {
			Instant at = instant(entry.at());
			if (at != null && at.isAfter(from) && !at.isAfter(to)) {
				out.add(entry);
			}
		}
		return out;
	}

	private static @Nullable Instant instant(@Nullable String text) {
		if (text == null) {
			return null;
		}
		try {
			return Instant.parse(text);
		} catch (DateTimeException e) {
			return null;
		}
	}
}
