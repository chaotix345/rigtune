package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.client.undo.HistoryStartup;
import io.github.chaotix345.rigtune.client.undo.StaleGroups;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.UnfinishedGroups;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.PartlyApplied;
import io.github.chaotix345.rigtune.core.history.StaleOps;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class RigTunePreLaunch implements PreLaunchEntrypoint {
	private static volatile @Nullable ApplyResult unseenResult;
	private static volatile int leftoverOps;
	private static volatile int leftoverFileOps;
	// The ids of staged ops that can never run (readState), whose old failures warnOnce doesn't replay.
	static volatile Set<String> staleOps = Set.of();
	private static volatile boolean helperBusy;
	static final Duration HELPER_WAIT = Duration.ofSeconds(5);

	@Override
	public void onPreLaunch() {
		long footprint = FootprintStats.preLaunchStart();
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path lockFile = ApplyLock.defaultPath(configDir);
		ApplyLock lock = null;
		boolean busy = false;
		try {
			lock = ApplyLock.acquire(lockFile, Duration.ZERO);
			if (lock == null) {
				// Fabric has already picked the mod jars by now, so whatever the helper still renames loads next time.
				busy = true;
				RigTune.LOGGER.warn("RigTune's apply helper from the last session is still running; waiting up to {} s", HELPER_WAIT.toSeconds());
				lock = ApplyLock.acquire(lockFile, HELPER_WAIT);
			}
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not check {}", lockFile, e);
		}
		try {
			readState(configDir, busy && lock == null);
			// The journal's legacy import and reconciliation need the lock the helper held (reentrant: review M4). The
			// journal must never stop the game from starting.
			try {
				HistoryStartup.run(configDir, ClientJournal.get(), lock != null);
			} catch (Throwable t) {
				RigTune.LOGGER.warn("Could not update RigTune's change history", t);
			}
		} finally {
			if (lock != null) {
				lock.close();
			}
		}
		// A helper still running writes a newer result; its failures are logged at the next start.
		if (!busy || lock != null) {
			try {
				warnOnce(configDir, InstanceDirs.modsDir(FabricLoader.getInstance().getGameDir()), ClientState.shared(configDir), RigTune.LOGGER::warn,
						staleOps);
			} catch (Throwable t) {
				RigTune.LOGGER.warn("Could not check the last RigTune apply result for failures", t);
			}
		}
		if (busy) {
			helperBusy = true;
			RigTune.LOGGER.warn(lock == null
					? "RigTune's apply helper is still running; its changes take effect after the next restart"
					: "RigTune's apply helper finished while the game was starting; mod file changes take effect after the next restart");
		}
		FootprintStats.preLaunchEnd(footprint);
	}

	// docs/v0.3/SPEC.md 3e (review B-M1): one WARN line per op the last helper run didn't apply, so the reason is in
	// latest.log and not only in helper.log; each run (finishedAt) is logged once.
	static void warnOnce(Path configDir, Path modsDir, ClientState state, Consumer<String> log) {
		warnOnce(configDir, modsDir, state, log, Set.of());
	}

	// stale: the ids of staged ops that can never run (readState, docs/v0.5/SPEC.md 2H RW-3), whose old failures aren't
	// replayed: RigTune drops them once the game has started.
	static void warnOnce(Path configDir, Path modsDir, ClientState state, Consumer<String> log, Set<String> stale) {
		Path last = ApplyResult.defaultPath(configDir);
		ApplyResult result;
		try {
			if (!Files.isRegularFile(last)) {
				return;
			}
			result = ApplyResult.load(last);
		} catch (Exception e) {
			return;
		}
		if (result.finishedAt() == null || result.finishedAt().equals(state.lastWarnedApply)) {
			return;
		}
		List<ApplyFailures.Failure> failures = ApplyFailures.of(result, List.of(modsDir, configDir));
		if (failures.isEmpty()) {
			return;
		}
		// The run counts as logged even when all its failures were stale: they're dropped, never retried.
		failures.stream().filter(f -> f.opId() == null || !stale.contains(f.opId())).forEach(f -> log.accept(ApplyFailures.warnLine(f, result.finishedAt())));
		state.lastWarnedApply = result.finishedAt();
		state.save(configDir);
	}

	private static void readState(Path configDir, boolean stillRunning) {
		readState(configDir, stillRunning, ClientState.shared(configDir).lastShownApply,
				() -> StaleGroups.loadedFrom(FabricLoader.getInstance().getAllMods()), line -> RigTune.LOGGER.warn(line));
	}

	// Every staged op counted, stale or not (0.4's count).
	static void readState(Path configDir, boolean stillRunning, @Nullable String lastShownApply) {
		readState(configDir, stillRunning, lastShownApply, null, line -> RigTune.LOGGER.warn(line));
	}

	static void readState(Path configDir, boolean stillRunning, @Nullable String lastShownApply, @Nullable Supplier<Map<String, Set<String>>> loadedFrom) {
		readState(configDir, stillRunning, lastShownApply, loadedFrom, line -> RigTune.LOGGER.warn(line));
	}

	// loadedFrom: a loaded mod id -> the jar files it was loaded from, asked only when pending.json exists; null: no op is
	// judged stale. warn: the WARN lines.
	static void readState(Path configDir, boolean stillRunning, @Nullable String lastShownApply, @Nullable Supplier<Map<String, Set<String>>> loadedFrom,
			Consumer<String> warn) {
		try {
			Path last = ApplyResult.defaultPath(configDir);
			if (Files.isRegularFile(last)) {
				ApplyResult result = ApplyResult.load(last);
				if (!Objects.equals(result.finishedAt(), lastShownApply)) {
					unseenResult = result;
				}
			}
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not read the last RigTune apply result", e);
		}
		try {
			Path pending = PendingActions.defaultPath(configDir);
			if (!stillRunning && Files.isRegularFile(pending)) {
				// docs/v0.5/SPEC.md 2H RW-3: a group that can never run (its download gone, or its mod loaded from another jar)
				// isn't "retried": the first rebuild drops it and says so.
				PendingActions plan = PendingActions.load(pending);
				Set<String> staleGroups = loadedFrom == null ? Set.of() : staleGroups(plan, pending, loadedFrom);
				Set<String> staleIds = new HashSet<>();
				List<Op> runnable = new ArrayList<>();
				for (Op op : plan.ops()) {
					if (op != null && op.id() != null && staleGroups.contains(op.group() != null ? op.group() : "op:" + op.id())) {
						staleIds.add(op.id());
					} else {
						runnable.add(op);
					}
				}
				staleOps = Set.copyOf(staleIds);
				leftoverOps = runnable.size();
				// v0.5 (docs/v0.5/SPEC.md 4d): runnable ops in mod-file groups are retried at the next exit, or held for the
				// player's choice where the launcher keeps its own list of mods; that is known only after launcher detection, so
				// their WARN comes with the title screen's toast (LauncherRepairService.leftoverAtTitle).
				leftoverFileOps = ApplyExecutor.fileGroupOps(runnable).size();
				if (!runnable.isEmpty() && leftoverFileOps == 0) {
					warn.accept(runnable.size() + " staged RigTune change(s) were not applied; they will be retried at the next exit");
				}
				if (!staleIds.isEmpty()) {
					RigTune.LOGGER.info("{} staged RigTune change(s) can never run (the download is gone, or the mod is installed another way); "
							+ "RigTune drops them once the game has started", staleIds.size());
				}
			}
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not read pending RigTune changes", e);
		}
	}

	// The groups (an ungrouped op: "op:<id>") StaleOps finds, as Staging.dropStale will at the first rebuild: from
	// Files.exists and FabricLoader's origins, no jar opened (an enable staged without a mod id is judged by its files
	// alone). Only when something looks stale are the mods folder's names and the helper's records read, for the groups it
	// left half done or started (never stale; review 11 APPLY-1). Nothing on an error: every op is then counted, as before.
	private static Set<String> staleGroups(PendingActions plan, Path pending, Supplier<Map<String, Set<String>>> loadedFrom) {
		try {
			PendingActions here = plan.relocated(InstanceDirs.modsDirOf(pending), InstanceDirs.configDirOf(pending));
			Map<String, Set<String>> origins = loadedFrom.get();
			List<StaleOps.Stale> found = StaleOps.find(here.ops(), Files::exists, origins, Op::modId, Set.of());
			if (!found.isEmpty()) {
				Set<String> names = new HashSet<>();
				try (Stream<Path> files = Files.list(InstanceDirs.modsDirOf(pending))) {
					files.forEach(f -> names.add(f.getFileName().toString()));
				}
				Set<String> halfDone = new HashSet<>(PartlyApplied.groups(here.ops(), names, UnfinishedGroups.recorded(InstanceDirs.configDirOf(pending))));
				halfDone.addAll(ApplyExecutor.startedGroups(here, pending));
				found = StaleOps.find(here.ops(), Files::exists, origins, Op::modId, halfDone);
			}
			Set<String> out = new HashSet<>();
			for (StaleOps.Stale stale : found) {
				here.ops().stream().filter(op -> op != null && stale.opId().equals(op.id())).findFirst()
						.ifPresent(op -> out.add(op.group() != null ? op.group() : "op:" + op.id()));
			}
			return out;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not check RigTune's staged changes for ones that can never run", e);
			return Set.of();
		}
	}

	public static @Nullable ApplyResult takeUnseenResult() {
		ApplyResult result = unseenResult;
		unseenResult = null;
		return result;
	}

	public static boolean takeHelperBusy() {
		boolean busy = helperBusy;
		helperBusy = false;
		return busy;
	}

	public static int takeLeftoverOps() {
		int count = leftoverOps;
		leftoverOps = 0;
		return count;
	}

	// The leftover ops in groups with a mod-file op (docs/v0.5/SPEC.md 4d).
	public static int takeLeftoverFileOps() {
		int count = leftoverFileOps;
		leftoverFileOps = 0;
		return count;
	}
}
