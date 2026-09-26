package io.github.chaotix345.rigtune.client.undo;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.LegacyImport;
import io.github.chaotix345.rigtune.core.history.StagedChanges;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// The journal at startup (docs/v0.2/SPEC.md item 3). history.json is created with a legacy-import entry of what 0.1.x
// did: the game's Journal makes it whenever the file is first created, under the lock, so an import preLaunch couldn't
// do (the 0.1.x helper still held the lock) happens at the first record instead. Later starts reconcile the journal
// with the helper's last result and pending.json (review H5). Runs from preLaunch, before the game's classes load, so
// it touches only core classes and files, and nothing here may stop the game from starting.
public final class HistoryStartup {
	private HistoryStartup() {
	}

	// preLaunch, holding the apply lock. lockHeld false: the helper may still be running and last-apply.json may be
	// stale, so nothing is done now.
	public static void run(Path configDir, Journal journal, boolean lockHeld) {
		if (!lockHeld) {
			return;
		}
		try {
			if (!journal.exists()) {
				journal.update(entries -> entries);
				return;
			}
			// An unreadable pending.json throws here and isn't reconciled: which ops it holds is unknown.
			Path pendingFile = PendingActions.defaultPath(configDir);
			PendingActions plan = Files.isRegularFile(pendingFile) ? PendingActions.load(pendingFile) : null;
			ApplyResult lastApply = lastApply(configDir);
			Set<String> pendingIds = new HashSet<>();
			if (plan != null) {
				plan.ops().stream().filter(Objects::nonNull).map(Op::id).filter(Objects::nonNull).forEach(pendingIds::add);
			}
			List<ApplyResult.OpResult> results = lastApply == null ? List.of() : lastApply.results();
			journal.updateExisting(entries -> HistoryUpdates.reconcile(entries, pendingIds, results));
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not update {}", Journal.file(configDir), e);
		}
	}

	// The legacy-import entry for 0.1.x's last run and leftover staged ops, or null. The game's journal calls this under
	// the apply lock when it first creates history.json. Only when last-apply.json is 0.1.x's (docs/v0.4/SPEC.md 2o L1):
	// a fresh install, or a history.json deleted after a later RigTune ran, imports nothing.
	public static JournalEntry legacyEntry(Path configDir, String mcVersion) {
		ApplyResult lastApply = lastApply(configDir);
		if (!LegacyImport.fromV010(lastApply)) {
			return null;
		}
		PendingActions leftover = null;
		Path pendingFile = PendingActions.defaultPath(configDir);
		try {
			if (Files.isRegularFile(pendingFile)) {
				leftover = PendingActions.load(pendingFile).relocated(InstanceDirs.modsDirOf(pendingFile), InstanceDirs.configDirOf(pendingFile));
			}
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not read {} for the history", pendingFile, e);
		}
		return LegacyImport.entry(lastApply, leftover, ModJars::modIdOf, ModJars::nameOf, sodiumKeys(configDir), mcVersion);
	}

	private static ApplyResult lastApply(Path configDir) {
		Path last = ApplyResult.defaultPath(configDir);
		try {
			return Files.isRegularFile(last) ? ApplyResult.load(last) : null;
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not read {} for the history", last, e);
			return null;
		}
	}

	// 0.1.x only ever patched sodium-options.json. Read with core classes only: this runs before the game loads.
	static StagedChanges.ConfigKeys sodiumKeys(Path configDir) {
		Path file = configDir.resolve("sodium-options.json").toAbsolutePath().normalize();
		return new StagedChanges.ConfigKeys() {
			private Map<String, String> values;

			@Override
			public String key(Op op, String keyInFile) {
				return isSodium(op) ? "sodium." + keyInFile : null;
			}

			@Override
			public String current(Op op, String keyInFile) {
				if (!isSodium(op)) {
					return null;
				}
				if (values == null) {
					values = read(file);
				}
				return values.get(keyInFile);
			}

			private boolean isSodium(Op op) {
				try {
					return op.path() != null && Path.of(op.path()).toAbsolutePath().normalize().equals(file);
				} catch (InvalidPathException e) {
					return false;
				}
			}
		};
	}

	private static Map<String, String> read(Path file) {
		try {
			if (!Files.isRegularFile(file)) {
				return Map.of();
			}
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			return root.isJsonObject() ? SodiumConfigPatcher.flatten(root.getAsJsonObject(), "") : Map.of();
		} catch (Exception e) {
			return Map.of();
		}
	}
}
