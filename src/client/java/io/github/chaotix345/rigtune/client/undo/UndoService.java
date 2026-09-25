package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

// Undo last apply / Undo everything (docs/v0.2/SPEC.md item 3). undo() carries out the plan that was shown,
// re-checked under the apply lock (review M8): staged groups are dropped from pending.json, vanilla settings are put
// back now, config settings and mod files are staged for the helper after a restart, and the undo is journaled with
// `reverts` on each change.
public final class UndoService {
	public interface VanillaWriter {
		// Sets these vanilla options now; returns key -> whether it took.
		Map<String, Boolean> write(Map<String, String> values);
	}

	// now: settings put back now; afterRestart: staged reversal ops; cancelled: staged ops dropped; skipped: items
	// that were shown but couldn't be done.
	public record Outcome(boolean busy, int now, int afterRestart, int cancelled, int skipped) {
		static final Outcome BUSY = new Outcome(true, 0, 0, 0, 0);
	}

	private final Staging staging;
	private final Journal journal;
	private final Supplier<UndoPlanner.State> state;
	private final VanillaWriter vanilla;

	public UndoService(Staging staging, Journal journal, Supplier<UndoPlanner.State> state, VanillaWriter vanilla) {
		this.staging = staging;
		this.journal = journal;
		this.state = state;
		this.vanilla = vanilla;
	}

	// Null when history.json was written by a newer RigTune (it is never overwritten, so nothing can be undone).
	public UndoPlan plan(boolean all) {
		if (journal.readOnly()) {
			return null;
		}
		return UndoPlanner.plan(journal.entries(), pendingOps(), state.get(), all).plan();
	}

	public Outcome undo(UndoPlan shown) throws IOException {
		try (ApplyLock lock = staging.lock()) {
			if (lock == null) {
				return Outcome.BUSY;
			}
			UndoPlanner.State now = state.get();
			UndoPlanner.Result result = UndoPlanner.recheck(shown, journal.entries(), pendingOps(), now);
			UndoPlanner.Script script = result.script();
			// Items the screen already listed as skipped, plus the ones that became skips since.
			int skipped = (int) (shown.items().stream().filter(i -> i.action() == UndoPlan.Action.SKIP).count()
					+ result.plan().items().stream().filter(i -> i.action() == UndoPlan.Action.SKIP).count());

			List<Op> dropped = staging.unstageLocked(script.discardOpIds());
			Map<String, Boolean> written = script.immediate().isEmpty() ? Map.of() : vanilla.write(script.immediate());
			Map<String, Op> configOps = stageConfig(script.staged());
			List<Op> toStage = new ArrayList<>(configOps.values());
			toStage.addAll(script.fileOps());
			Staging.Merge merge = toStage.isEmpty() ? null : staging.mergeLocked(toStage);

			List<JournalChange> undoChanges = new ArrayList<>();
			Set<String> revertedNow = new HashSet<>();
			Set<String> failed = new HashSet<>();
			Set<String> settingsNow = new HashSet<>();
			for (UndoPlanner.Revert revert : script.reverts()) {
				JournalChange undo = revert.undo();
				String opId;
				if (undo.isSetting() && now.immediate(undo.key())) {
					if (Boolean.TRUE.equals(written.get(undo.key()))) {
						undoChanges.add(undo.withStatus(JournalChange.APPLIED));
						revertedNow.add(revert.changeId());
						settingsNow.add(undo.key());
					} else {
						failed.add(undo.key());
					}
					continue;
				}
				if (undo.isFile() && revert.opRef() == null) {
					undoChanges.add(undo.withStatus(JournalChange.APPLIED));
					revertedNow.add(revert.changeId());
					continue;
				}
				Op op = undo.isSetting() ? configOps.get(undo.key()) : null;
				String ref = undo.isSetting() ? (op == null ? null : op.id()) : revert.opRef();
				opId = ref == null || merge == null ? null : merge.merged().survivingIds().get(ref);
				if (opId == null) {
					failed.add(undo.isSetting() ? undo.key() : revert.changeId());
					continue;
				}
				undoChanges.add(undo.withStatus(JournalChange.STAGED).withOpId(opId).withGroup(undo.isFile() ? groupOf(merge, opId) : null));
			}

			if (shown.undoOf() != null) {
				Staging.Merge merged = merge;
				if (!journal.update(entries -> {
					List<JournalEntry> out = merged == null ? entries : HistoryUpdates.discard(entries, Staging.droppedIds(merged));
					out = HistoryUpdates.revert(out, revertedNow);
					return HistoryUpdates.append(out, journal.newEntry(JournalEntry.UNDO, shown.undoOf(), undoChanges));
				})) {
					RigTune.LOGGER.warn("Could not record the undo in {}", Journal.file(staging.pendingFile().getParent().getParent()));
				}
			}
			int afterRestart = (int) undoChanges.stream().filter(c -> JournalChange.STAGED.equals(c.status())).map(JournalChange::opId).distinct().count();
			return new Outcome(false, settingsNow.size(), afterRestart, dropped.size(), skipped + failed.size());
		}
	}

	private static String groupOf(Staging.Merge merge, String opId) {
		return merge.merged().plan().ops().stream().filter(o -> o != null && opId.equals(o.id())).map(Op::group).filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	// Each config value goes through its file's stager, which checks it against the file as it is now.
	private Map<String, Op> stageConfig(Map<String, String> values) {
		Map<String, Op> out = new LinkedHashMap<>();
		for (ConfigTargets.Target target : staging.targets()) {
			Map<String, String> patches = new LinkedHashMap<>();
			values.forEach((key, value) -> {
				if (key.startsWith(target.prefix())) {
					patches.put(key.substring(target.prefix().length()), value);
				}
			});
			if (patches.isEmpty()) {
				continue;
			}
			SodiumConfigPatcher.Staged staged = target.stager().stage(target.file(), patches);
			staged.refused().forEach((key, problem) -> RigTune.LOGGER.warn("Not undoing setting {}{}: {}", target.prefix(), key, problem));
			for (Op op : staged.ops()) {
				if (op.patches() != null && !op.patches().isEmpty()) {
					out.put(target.prefix() + op.patches().keySet().iterator().next(), op);
				}
			}
		}
		return out;
	}

	private List<Op> pendingOps() {
		if (!Files.exists(staging.pendingFile())) {
			return List.of();
		}
		try {
			return PendingActions.load(staging.pendingFile()).ops();
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not read {}", staging.pendingFile(), e);
			return List.of();
		}
	}
}
