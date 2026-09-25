package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import net.minecraft.client.Options;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The live values the undo planner checks against: vanilla options now (read with the same encoding the journal
// recorded), config files as they are on disk, and the mods folder.
public final class GameState implements UndoPlanner.State {
	private final Map<String, String> vanilla;
	private final List<ConfigTargets.Target> targets;
	private final Path modsDir;
	private final Map<ConfigTargets.Target, Map<String, String>> config = new HashMap<>();
	private ModsFolder folder;

	public GameState(Options options, List<ConfigTargets.Target> targets, Path modsDir) {
		Map<String, String> read;
		try {
			read = SettingsBridge.readVanilla(options);
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read the vanilla options for undo", e);
			read = Map.of();
		}
		this.vanilla = read;
		this.targets = targets;
		this.modsDir = modsDir;
	}

	@Override
	public String setting(String key) {
		if (key.startsWith(SettingsBridge.VANILLA_PREFIX)) {
			return vanilla.get(key.substring(SettingsBridge.VANILLA_PREFIX.length()));
		}
		ConfigTargets.Target target = ConfigTargets.forKey(targets, key);
		return target == null ? null : config.computeIfAbsent(target, t -> t.reader().read(t.file())).get(key.substring(target.prefix().length()));
	}

	@Override
	public boolean immediate(String key) {
		return key.startsWith(SettingsBridge.VANILLA_PREFIX);
	}

	@Override
	public boolean changeable(String key) {
		return immediate(key) ? SettingKeys.changeable(key) : ConfigTargets.forKey(targets, key) != null;
	}

	@Override
	public UndoPlanner.Folder folder() {
		if (folder == null) {
			folder = ModsFolder.current(modsDir);
		}
		return folder;
	}
}
