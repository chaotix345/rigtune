package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.StagedChanges;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

// Staging into config/rigtune/pending.json, and the journal records that go with it, under the apply lock: the
// changes are recorded after the merge, with the ids the ops have in pending.json (review H5).
public final class Staging {
	public static final Duration LOCK_WAIT = Duration.ofSeconds(2);

	// base: pending.json before the merge (as seen from this instance). relocatedAway: staged ops dropped because they
	// belong to another instance's folders.
	public record Merge(PendingActions base, PendingActions.Merged merged, List<Op> relocatedAway) {
	}

	private final Path configDir;
	private final Path pendingFile;
	private final List<ConfigTargets.Target> targets;
	private final Journal journal;
	private final Duration lockWait;
	private final Function<Path, String> modIdOf;

	public Staging(Path configDir, Path pendingFile, List<ConfigTargets.Target> targets, Journal journal) {
		this(configDir, pendingFile, targets, journal, LOCK_WAIT);
	}

	Staging(Path configDir, Path pendingFile, List<ConfigTargets.Target> targets, Journal journal, Duration lockWait) {
		this.configDir = configDir;
		this.pendingFile = pendingFile;
		this.targets = targets;
		this.journal = journal;
		this.lockWait = lockWait;
		this.modIdOf = ModJars::modIdOf;
	}

	public Path pendingFile() {
		return pendingFile;
	}

	public List<ConfigTargets.Target> targets() {
		return targets;
	}

	// Null when someone else still holds it after the wait.
	public ApplyLock lock() throws IOException {
		return ApplyLock.acquire(ApplyLock.defaultPath(configDir), lockWait);
	}

	// Merges the ops into pending.json and records them in the journal entry `entryId` (kind apply). Returns the merge
	// (its survivingIds say which id each op has in pending.json: plan review A-M1), or null when nothing was staged.
	public @Nullable Merge stage(List<Op> ops, String entryId) {
		try (ApplyLock lock = lock()) {
			if (lock == null) {
				RigTune.LOGGER.error("Could not stage RigTune changes: the apply helper still holds {}", ApplyLock.defaultPath(configDir));
				return null;
			}
			Merge merge = mergeLocked(ops);
			try {
				if (!journal.update(entries -> recorded(entries, merge, ops, entryId))) {
					RigTune.LOGGER.warn("Could not record the staged RigTune changes in {}", Journal.file(configDir));
				}
			} catch (IOException | RuntimeException e) {
				RigTune.LOGGER.warn("Could not record the staged RigTune changes in {}", Journal.file(configDir), e);
			}
			return merge;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.error("Could not write {}", pendingFile, e);
			return null;
		}
	}

	private List<JournalEntry> recorded(List<JournalEntry> entries, Merge merge, List<Op> ops, String entryId) {
		StagedChanges.Outcome outcome = StagedChanges.of(merge.base(), ops, merge.merged(), configKeys(), modIdOf, stagedOpIds(entries));
		List<JournalEntry> out = HistoryUpdates.discard(entries, droppedIds(merge));
		return outcome.changes().isEmpty() ? out : journal.withChanges(out, entryId, JournalEntry.APPLY, outcome.changes());
	}

	// The staged ops a merge dropped: replaced by a newer enable, or for another instance's folders.
	public static List<String> droppedIds(Merge merge) {
		List<String> ids = new ArrayList<>();
		merge.merged().replaced().forEach(op -> ids.add(op.id()));
		merge.relocatedAway().forEach(op -> ids.add(op == null ? null : op.id()));
		ids.removeIf(Objects::isNull);
		return ids;
	}

	public static Set<String> stagedOpIds(List<JournalEntry> entries) {
		Set<String> ids = new HashSet<>();
		for (JournalEntry entry : entries) {
			for (JournalChange c : entry.changes()) {
				if (JournalChange.STAGED.equals(c.status()) && c.opId() != null) {
					ids.add(c.opId());
				}
			}
		}
		return ids;
	}

	// The caller holds the lock. The plan's folders come from where pending.json is, not from what an existing plan
	// records (the instance may be a copy); an unreadable plan is replaced.
	public Merge mergeLocked(List<Op> ops) throws IOException {
		PendingActions plan = null;
		if (Files.exists(pendingFile)) {
			try {
				plan = PendingActions.load(pendingFile);
			} catch (IOException e) {
				RigTune.LOGGER.warn("Replacing unreadable {}", pendingFile, e);
			}
		}
		Path planMods = InstanceDirs.modsDirOf(pendingFile);
		Path planConfig = InstanceDirs.configDirOf(pendingFile);
		PendingActions base = plan != null ? plan.relocated(planMods, planConfig)
				: PendingActions.create(ProcessHandle.current().pid(), planMods, planConfig, List.of());
		List<Op> relocatedAway = new ArrayList<>();
		if (plan != null && base.ops().size() < plan.ops().size()) {
			RigTune.LOGGER.warn("Dropped {} staged change(s) for another instance's folders ({})", plan.ops().size() - base.ops().size(), plan.modsDir());
			for (Op op : plan.ops()) {
				if (base.ops().stream().noneMatch(kept -> kept == op)) {
					relocatedAway.add(op);
				}
			}
		}
		PendingActions.Merged merged = base.merge(ops);
		merged.plan().save(pendingFile);
		for (Path old : merged.superseded()) {
			// Only downloads: a replaced enable of a user's x.jar.disabled (an undo's re-enable) leaves that jar alone.
			if (!SafeFileNames.isDirectChild(planMods, old) || !old.getFileName().toString().endsWith(PendingActions.PENDING_SUFFIX)) {
				continue;
			}
			try {
				Path retired = PendingActions.retire(old);
				if (retired != null) {
					RigTune.LOGGER.info("Replaced staged {}; kept it as {}", old.getFileName(), retired.getFileName());
				}
			} catch (IOException e) {
				RigTune.LOGGER.warn("Could not retire replaced {}; it stays inert", old, e);
			}
		}
		return new Merge(base, merged, List.copyOf(relocatedAway));
	}

	// The caller holds the lock. Drops these ops and every op in their groups from pending.json (deleting it when
	// nothing is left), retires their downloads and marks their journal changes DISCARDED. Returns the dropped ops.
	public List<Op> unstageLocked(Collection<String> opIds) throws IOException {
		if (opIds.isEmpty() || !Files.exists(pendingFile)) {
			return List.of();
		}
		PendingActions.Removed removed = PendingActions.load(pendingFile).remove(opIds);
		if (removed.removed().isEmpty()) {
			return List.of();
		}
		if (removed.plan().ops().isEmpty()) {
			Files.deleteIfExists(pendingFile);
		} else {
			removed.plan().save(pendingFile);
		}
		Path planMods = InstanceDirs.modsDirOf(pendingFile);
		removed.removed().forEach(op -> {
			if (removed.plan().ops().stream().noneMatch(o -> o != null && op.from() != null && op.from().equals(o.from()))) {
				PendingActions.retireDownload(op, planMods);
			}
		});
		markDiscarded(removed.removed());
		return removed.removed();
	}

	// Unstages (as unstageLocked), with its group, every staged enable of a loaded mod that has an update of its own
	// waiting in mods/update/ (ModJars.queuedUpdates): at exit it would race that mod's own updater for the jar (re-check
	// of review 4). An enable of a mod that isn't loaded (an addition, an undo's re-enable) stays: a stale jar in
	// mods/update/ must not cancel it (SPEC 3a). Null when the lock is busy.
	public @Nullable List<Op> dropQueuedUpdates(Set<String> queuedModIds, Set<String> loadedModIds) throws IOException {
		if (queuedModIds.isEmpty() || !Files.exists(pendingFile)) {
			return List.of();
		}
		try (ApplyLock lock = lock()) {
			if (lock == null) {
				return null;
			}
			if (!Files.exists(pendingFile)) {
				return List.of();
			}
			List<String> ids = new ArrayList<>();
			for (Op op : PendingActions.load(pendingFile).ops()) {
				if (op != null && op.type() == PendingActions.Type.ENABLE_FILE && op.id() != null) {
					String modId = modIdOf(op);
					if (modId != null && queuedModIds.contains(modId) && loadedModIds.contains(modId)) {
						ids.add(op.id());
					}
				}
			}
			return unstageLocked(ids);
		}
	}

	// The staged mod id, or, for an enable staged without one (by 0.1.0, or an undo of a jar it couldn't read), the id
	// in the jar itself, as ApplyExecutor reads it at apply time (review 5, apply-safety-1).
	private static @Nullable String modIdOf(Op op) {
		if (op.modId() != null) {
			return op.modId();
		}
		try {
			return op.from() == null ? null : ModJars.modIdOf(Path.of(op.from()));
		} catch (InvalidPathException e) {
			return null;
		}
	}

	// Cancels everything staged (the RigTune screen's Discard pending). Null when the lock is busy.
	public List<Op> discard() throws IOException {
		try (ApplyLock lock = lock()) {
			if (lock == null) {
				return null;
			}
			List<Op> dropped = PendingActions.discard(pendingFile, Duration.ZERO);
			if (dropped != null) {
				markDiscarded(dropped);
			}
			return dropped;
		}
	}

	private void markDiscarded(List<Op> ops) {
		List<String> ids = ops.stream().filter(Objects::nonNull).map(Op::id).filter(Objects::nonNull).toList();
		if (ids.isEmpty() || !journal.exists()) {
			return;
		}
		try {
			journal.updateExisting(entries -> HistoryUpdates.discard(entries, ids));
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not mark discarded changes in {}", Journal.file(configDir), e);
		}
	}

	// Settings keys for config-file ops, from the ConfigTargets: the op's file picks the namespace. Each file is read
	// once per instance of the returned object.
	public StagedChanges.ConfigKeys configKeys() {
		return configKeys(targets);
	}

	public static StagedChanges.ConfigKeys configKeys(List<ConfigTargets.Target> targets) {
		Map<Path, Map<String, String>> read = new HashMap<>();
		return new StagedChanges.ConfigKeys() {
			@Override
			public String key(Op op, String keyInFile) {
				ConfigTargets.Target target = targetOf(targets, op);
				return target == null ? null : target.prefix() + keyInFile;
			}

			@Override
			public String current(Op op, String keyInFile) {
				ConfigTargets.Target target = targetOf(targets, op);
				return target == null ? null : read.computeIfAbsent(target.file(), f -> target.reader().read(f)).get(keyInFile);
			}
		};
	}

	static ConfigTargets.Target targetOf(List<ConfigTargets.Target> targets, Op op) {
		if (op == null || op.path() == null) {
			return null;
		}
		try {
			Path path = Path.of(op.path()).toAbsolutePath().normalize();
			for (ConfigTargets.Target target : targets) {
				if (target.file().toAbsolutePath().normalize().equals(path)) {
					return target;
				}
			}
		} catch (InvalidPathException ignored) {
		}
		return null;
	}
}
