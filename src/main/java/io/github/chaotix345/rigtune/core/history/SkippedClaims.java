package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.UnfinishedGroups;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

// docs/v0.5/SPEC.md 4f, real world RW-20: before RW-1's helper fix, a helper that found a group already in place counted
// it as done (SKIPPED_ALREADY_DONE) whoever had put it there, and History has claimed it as RigTune's ever since (the
// user's DH pair, installed by the Modrinth App). While last-apply.json is still the run that made such a claim, an enable
// it names as SKIPPED_ALREADY_DONE, with no resultPath (a rename RigTune redid from its record has one) and no
// unfinished-groups.json record, is relabelled ABANDONED "installed another way", with its group's disables reported
// that way (review 11 APPLY-2): in the journal (APPLIED -> ABANDONED) and in that result, whose finishedAt stays, so
// nothing is shown or logged again. Only statuses 0.4.0 knows. Idempotent: afterwards no such result is left; a later
// helper run replaces last-apply.json, whose ops the old changes don't match. A change already ABANDONED next to such a
// result (a start that died between the two writes) is completed. An undo's change stays (the change it reverted stays
// REVERTED), and so do settings.
public final class SkippedClaims {
	// Staging's word for RW-3's drop too.
	public static final String REASON = "installed another way";

	private SkippedClaims() {
	}

	// entries: history.json's (the journal writes them first); lastApply: last-apply.json's rewrite; opIds: the ops
	// relabelled.
	public record Relabel(List<JournalEntry> entries, ApplyResult lastApply, List<String> opIds) {
	}

	// Null when there's nothing to relabel. recorded: the helper's unfinished-groups.json record, read only when some
	// result could be a claim. Only RW-1's claim shape (review 11 APPLY-2): an enable found in place, with its group's
	// disables when that enable is relabelled; 0.5's helper still reports a disable whose jar is gone that way, and that
	// one stays.
	public static @Nullable Relabel of(List<JournalEntry> entries, @Nullable ApplyResult lastApply,
			Supplier<? extends Collection<UnfinishedGroups.Rename>> recorded) {
		if (lastApply == null) {
			return null;
		}
		Set<String> enables = new LinkedHashSet<>();
		Map<String, String> disableGroups = new LinkedHashMap<>();
		for (ApplyResult.OpResult r : lastApply.results()) {
			if (r != null && r.status() == ApplyResult.Status.SKIPPED_ALREADY_DONE && r.resultPath() == null && r.op() != null && r.op().id() != null) {
				if (r.op().type() == PendingActions.Type.ENABLE_FILE) {
					enables.add(r.op().id());
				} else if (r.op().type() == PendingActions.Type.DISABLE_FILE && r.op().group() != null) {
					disableGroups.put(r.op().id(), r.op().group());
				}
			}
		}
		if (enables.isEmpty()) {
			return null;
		}
		Collection<UnfinishedGroups.Rename> renames = recorded.get();
		if (renames != null) {
			renames.stream().filter(rename -> rename != null && rename.op() != null).forEach(rename -> {
				enables.remove(rename.op());
				disableGroups.remove(rename.op());
			});
		}
		// The claims: file changes of this run's ops, APPLIED, or already ABANDONED by an interrupted relabel.
		Set<String> claimed = new LinkedHashSet<>();
		Set<String> changeIds = new HashSet<>();
		claim(entries, enables, claimed, changeIds);
		if (claimed.isEmpty()) {
			return null;
		}
		Set<String> groups = new HashSet<>();
		lastApply.results().stream().filter(r -> r != null && r.op() != null && claimed.contains(r.op().id()) && r.op().group() != null)
				.forEach(r -> groups.add(r.op().group()));
		Set<String> disables = new LinkedHashSet<>();
		disableGroups.forEach((id, group) -> {
			if (groups.contains(group)) {
				disables.add(id);
			}
		});
		claim(entries, disables, claimed, changeIds);
		List<JournalEntry> relabelled = HistoryUpdates.map(entries, c -> changeIds.contains(c.id()) ? c.withStatus(JournalChange.ABANDONED) : c);
		List<ApplyResult.OpResult> results = new ArrayList<>();
		for (ApplyResult.OpResult r : lastApply.results()) {
			results.add(r != null && r.op() != null && claimed.contains(r.op().id()) ? new ApplyResult.OpResult(r.op(), ApplyResult.Status.ABANDONED, REASON)
					: r);
		}
		List<String> opIds = lastApply.results().stream().filter(r -> r != null && r.op() != null && claimed.contains(r.op().id())).map(r -> r.op().id())
				.toList();
		return new Relabel(relabelled, new ApplyResult(lastApply.finishedAt(), results), opIds);
	}

	private static void claim(List<JournalEntry> entries, Set<String> candidates, Set<String> claimed, Set<String> changeIds) {
		for (JournalEntry entry : entries) {
			for (JournalChange c : entry.changes()) {
				if (c.isFile() && c.reverts() == null && c.opId() != null && candidates.contains(c.opId())
						&& (JournalChange.APPLIED.equals(c.status()) || JournalChange.ABANDONED.equals(c.status()))) {
					claimed.add(c.opId());
					if (JournalChange.APPLIED.equals(c.status())) {
						changeIds.add(c.id());
					}
				}
			}
		}
	}
}
