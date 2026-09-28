package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.LogSafe;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.apply.UnfinishedGroups;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.PartlyApplied;
import io.github.chaotix345.rigtune.core.history.StagedChanges;
import io.github.chaotix345.rigtune.core.history.StaleOps;
import net.minecraft.network.chat.Component;
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
import java.util.function.Predicate;
import java.util.stream.Stream;

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
			createJournal();
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

	// history.json's first write makes the 0.1.x legacy entry from pending.json (HistoryStartup.legacyEntry): it must see
	// pending.json as it was before this merge, not this Apply's ops (docs/v0.4/SPEC.md 2o L1). The caller holds the lock.
	private void createJournal() {
		if (journal.exists()) {
			return;
		}
		try {
			journal.update(entries -> entries);
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not create {} ({})", LogSafe.name(Journal.file(configDir)), LogSafe.error(e, Journal.file(configDir)));
		}
	}

	private List<JournalEntry> recorded(List<JournalEntry> entries, Merge merge, List<Op> ops, String entryId) {
		StagedChanges.Outcome outcome = StagedChanges.of(merge.base(), ops, merge.merged(), configKeys(), modIdOf, ModJars::nameOf, stagedOpIds(entries));
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
		return unstageLocked(opIds, op -> false);
	}

	// As above; the dropped ops `abandoned` accepts are journaled ABANDONED instead (docs/v0.5/SPEC.md 2H RW-3: the mod was
	// installed another way).
	private List<Op> unstageLocked(Collection<String> opIds, Predicate<Op> abandoned) throws IOException {
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
		markDiscarded(removed.removed().stream().filter(op -> op != null && !abandoned.test(op)).toList());
		markAbandoned(removed.removed().stream().filter(op -> op != null && abandoned.test(op)).toList());
		return removed.removed();
	}

	// docs/v0.5/SPEC.md 2H RW-3: unstages, with its group, every staged group that can never run (StaleOps: an enable's
	// download is gone, or its mod is already loaded from another jar), retiring its downloads; a mod installed another way
	// makes the group ABANDONED in History, a download that's gone DISCARDED. Never a group the helper left half done or
	// its records show started, and never an op for another instance's folders (the helper refuses those itself).
	// loadedFrom: a loaded mod id -> the file names of the top-level jars it was loaded from. Null when the lock is busy.
	public @Nullable StaleDrop dropStale(Map<String, Set<String>> loadedFrom) throws IOException {
		if (!Files.exists(pendingFile)) {
			return StaleDrop.NONE;
		}
		try (ApplyLock lock = lock()) {
			if (lock == null) {
				return null;
			}
			if (!Files.exists(pendingFile)) {
				return StaleDrop.NONE;
			}
			PendingActions here = PendingActions.load(pendingFile).relocated(InstanceDirs.modsDirOf(pendingFile), InstanceDirs.configDirOf(pendingFile));
			List<StaleOps.Stale> stale = StaleOps.find(here.ops(), Files::exists, loadedFrom, Staging::modIdOf, Set.of());
			if (stale.isEmpty()) {
				return StaleDrop.NONE;
			}
			// Only then the folder's names and the helper's records: never drop what might be a half-done group, or one
			// RigTune's records show started (review 11 APPLY-1); without the names, nothing is dropped this time.
			Set<String> halfDone = halfDoneGroupsOrNull(here);
			if (halfDone == null) {
				return StaleDrop.NONE;
			}
			stale = StaleOps.find(here.ops(), Files::exists, loadedFrom, Staging::modIdOf, halfDone);
			if (stale.isEmpty()) {
				return StaleDrop.NONE;
			}
			Set<String> installed = new HashSet<>();
			for (StaleOps.Stale s : stale) {
				if (s.why() == StaleOps.Why.INSTALLED) {
					here.ops().stream().filter(op -> op != null && s.opId().equals(op.id())).findFirst()
							.ifPresent(op -> installed.add(op.group() != null ? op.group() : "op:" + op.id()));
				}
			}
			List<Op> dropped = unstageLocked(stale.stream().map(StaleOps.Stale::opId).toList(),
					op -> installed.contains(op.group() != null ? op.group() : "op:" + op.id()));
			RigTune.LOGGER.info("Unstaged {} RigTune change(s) that can never run: {}", dropped.size(), stale);
			return new StaleDrop(dropped, stale);
		}
	}

	// dropped: the ops unstaged; stale: what StaleOps found, one per dropped group.
	public record StaleDrop(List<Op> dropped, List<StaleOps.Stale> stale) {
		public static final StaleDrop NONE = new StaleDrop(List.of(), List.of());
	}

	// Unstages (as unstageLocked), with its group, every staged enable of a loaded mod that has an update of its own
	// waiting in mods/update/ (ModJars.queuedUpdates): at exit it would race that mod's own updater for the jar (re-check
	// of review 4). An enable of a mod that isn't loaded (an addition, an undo's re-enable) stays: a stale jar in
	// mods/update/ must not cancel it (SPEC 3a). A group the helper left half done stays (PartlyApplied). Null when the
	// lock is busy.
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
			Map<String, String> readIds = new HashMap<>();
			PendingActions plan = PendingActions.load(pendingFile);
			// As for Discard pending: the next exit finishes such a group or rolls it back.
			Set<String> halfDone = halfDoneGroups(plan);
			for (Op op : plan.ops()) {
				if (op != null && op.type() == PendingActions.Type.ENABLE_FILE && op.id() != null && (op.group() == null || !halfDone.contains(op.group()))) {
					String modId = modIdOf(op);
					if (modId != null && queuedModIds.contains(modId) && loadedModIds.contains(modId)) {
						ids.add(op.id());
						if (op.modId() == null) {
							readIds.put(op.id(), modId);
						}
					}
				}
			}
			List<Op> dropped = unstageLocked(ids);
			// An enable matched by its jar's id carries that id, so the notice can name the mod.
			return dropped == null ? null : dropped.stream()
					.map(op -> op != null && op.modId() == null && readIds.containsKey(op.id()) ? op.withModId(readIds.get(op.id())) : op)
					.toList();
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

	// Cancels everything staged (the RigTune screen's Discard pending), except a group the helper left half done at the
	// last exit (a failed rollback, or a kill between two renames), which the next exit finishes or rolls back (audit M2,
	// review-8 AH-1; PartlyApplied). Returns the dropped ops; null when the lock is busy.
	public List<Op> discard() throws IOException {
		Discard discard = discardPending();
		return discard == null ? null : discard.dropped();
	}

	// docs/v0.5/SPEC.md 2H L7: Discard pending's outcome. keptGroup: a group the helper left half done stayed staged.
	public record Discard(List<Op> dropped, boolean keptGroup) {
		// The RigTune screen's status line, which says so when a change already under way was kept.
		public Component status() {
			return Component.translatable(keptGroup ? "rigtune.status.discarded_with_kept" : "rigtune.status.discarded", dropped.size());
		}
	}

	// As discard(), saying whether a half-done group was kept; null when the lock is busy.
	public @Nullable Discard discardPending() throws IOException {
		try (ApplyLock lock = lock()) {
			if (lock == null) {
				return null;
			}
			PendingActions plan = readable();
			Set<String> halfDone = plan == null ? Set.of() : halfDoneGroups(plan);
			if (!halfDone.isEmpty()) {
				return new Discard(discardExcept(plan, halfDone), true);
			}
			List<Op> dropped = PendingActions.discard(pendingFile, Duration.ZERO);
			if (dropped == null) {
				return null;
			}
			markDiscarded(dropped);
			return new Discard(dropped, false);
		}
	}

	private @Nullable PendingActions readable() {
		try {
			return Files.exists(pendingFile) ? PendingActions.load(pendingFile) : null;
		} catch (IOException e) {
			return null;
		}
	}

	private Set<String> halfDoneGroups(PendingActions plan) {
		Set<String> groups = halfDoneGroupsOrNull(plan);
		return groups == null ? Set.of() : groups;
	}

	// The groups the helper left half done (PartlyApplied) or RigTune's records show started (ApplyExecutor.startedGroups,
	// review 11 APPLY-1: both renames done, the helper killed before last-apply.json): the next exit finishes them, so
	// Discard, the queued-update drop and the stale drop keep them. Null when the mods folder can't be listed (then nobody
	// can tell which groups are half done).
	private @Nullable Set<String> halfDoneGroupsOrNull(PendingActions plan) {
		Set<String> names = new HashSet<>();
		try (Stream<Path> files = Files.list(InstanceDirs.modsDirOf(pendingFile))) {
			files.forEach(f -> names.add(f.getFileName().toString()));
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not list the mods folder to check for half-applied changes", e);
			return null;
		}
		Set<String> groups = new HashSet<>(PartlyApplied.groups(plan.ops(), names, unfinishedRenames()));
		groups.addAll(ApplyExecutor.startedGroups(plan, pendingFile));
		return groups;
	}

	// The helper's record of the renames it started and hasn't finished (review-8 AH-1: a helper killed mid-group leaves
	// no attempt counted, only this), from the config folder the helper uses for this pending.json.
	public List<UnfinishedGroups.Rename> unfinishedRenames() {
		try {
			return UnfinishedGroups.recorded(InstanceDirs.configDirOf(pendingFile));
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	// The caller holds the lock. Drops every op outside the half-done groups, as unstageLocked does.
	private List<Op> discardExcept(PendingActions plan, Set<String> halfDone) throws IOException {
		List<Op> kept = new ArrayList<>();
		List<Op> dropped = new ArrayList<>();
		for (Op op : plan.ops()) {
			(op != null && op.group() != null && halfDone.contains(op.group()) ? kept : dropped).add(op);
		}
		plan.withOps(kept).save(pendingFile);
		Path planMods = InstanceDirs.modsDirOf(pendingFile);
		for (Op op : dropped) {
			if (op != null && kept.stream().noneMatch(k -> op.from() != null && op.from().equals(k.from()))) {
				PendingActions.retireDownload(op, planMods);
			}
		}
		RigTune.LOGGER.info("Kept {} staged change(s) the helper left half done; the next exit finishes them", kept.size());
		markDiscarded(dropped);
		return dropped;
	}

	private void markAbandoned(List<Op> ops) {
		List<ApplyResult.OpResult> results = ops.stream().filter(op -> op.id() != null)
				.map(op -> new ApplyResult.OpResult(op, ApplyResult.Status.ABANDONED, "installed another way")).toList();
		if (results.isEmpty() || !journal.exists()) {
			return;
		}
		try {
			journal.updateExisting(entries -> HistoryUpdates.applyResults(entries, results));
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not mark abandoned changes in {}", Journal.file(configDir), e);
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
