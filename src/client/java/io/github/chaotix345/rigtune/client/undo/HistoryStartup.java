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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// preLaunch, while it holds the apply lock (docs/v0.2/SPEC.md item 3): the first 0.2 run creates history.json with a
// legacy-import entry of what 0.1.x did; later runs reconcile the journal with the helper's last result and with
// pending.json (review H5). Runs before the game's classes load, so it touches only core classes and files.
public final class HistoryStartup {
	private HistoryStartup() {
	}

	// lockHeld false: the helper may still be running and last-apply.json may be stale, so nothing is done now.
	public static void run(Path configDir, Journal journal, boolean lockHeld) {
		if (!lockHeld) {
			return;
		}
		Path pendingFile = PendingActions.defaultPath(configDir);
		ApplyResult lastApply = null;
		try {
			Path last = ApplyResult.defaultPath(configDir);
			if (Files.isRegularFile(last)) {
				lastApply = ApplyResult.load(last);
			}
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not read the last RigTune apply result for the history", e);
		}
		PendingActions plan = null;
		boolean planReadable = true;
		try {
			if (Files.isRegularFile(pendingFile)) {
				plan = PendingActions.load(pendingFile);
			}
		} catch (IOException e) {
			planReadable = false;
			RigTune.LOGGER.warn("Could not read {} for the history", pendingFile, e);
		}
		try {
			if (!journal.exists()) {
				PendingActions leftover = plan == null ? null : plan.relocated(InstanceDirs.modsDirOf(pendingFile), InstanceDirs.configDirOf(pendingFile));
				JournalEntry imported = LegacyImport.entry(lastApply, leftover, ModJars::modIdOf, sodiumKeys(configDir), journal.mcVersion());
				journal.update(entries -> imported == null ? entries : HistoryUpdates.append(entries, imported));
				return;
			}
			if (!planReadable) {
				return;
			}
			Set<String> pendingIds = new HashSet<>();
			if (plan != null) {
				plan.ops().stream().filter(Objects::nonNull).map(Op::id).filter(Objects::nonNull).forEach(pendingIds::add);
			}
			List<ApplyResult.OpResult> results = lastApply == null ? List.of() : lastApply.results();
			journal.updateExisting(entries -> HistoryUpdates.reconcile(entries, pendingIds, results));
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not update {}", Journal.file(configDir), e);
		}
	}

	// 0.1.x only ever patched sodium-options.json. Read with core classes only: this runs before the game loads.
	static StagedChanges.ConfigKeys sodiumKeys(Path configDir) {
		Path file = configDir.resolve("sodium-options.json").toAbsolutePath().normalize();
		Map<String, String>[] values = new Map[1];
		return new StagedChanges.ConfigKeys() {
			@Override
			public String key(Op op, String keyInFile) {
				return isSodium(op) ? "sodium." + keyInFile : null;
			}

			@Override
			public String current(Op op, String keyInFile) {
				if (!isSodium(op)) {
					return null;
				}
				if (values[0] == null) {
					values[0] = read(file);
				}
				return values[0].get(keyInFile);
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
		} catch (IOException | RuntimeException e) {
			return Map.of();
		}
	}
}
