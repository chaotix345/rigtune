package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.SettingLabel;
import net.minecraft.client.Options;
import net.minecraft.network.chat.CommonComponents;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// The live values the undo planner checks against: vanilla options now (read with the same encoding the journal
// recorded), config files as they are on disk, and the mods folder.
public final class GameState implements UndoPlanner.State {
	private final Map<String, String> vanilla;
	private final Map<String, String> captions;
	private final Map<String, SettingLabel> labels;
	private final List<ConfigTargets.Target> targets;
	private final Path modsDir;
	private final Map<ConfigTargets.Target, Map<String, String>> config = new HashMap<>();
	private ModsFolder folder;

	// labels: the rules' settingLabels, so a mod's key is named as in its recommendation.
	public GameState(Options options, List<ConfigTargets.Target> targets, Path modsDir, Map<String, SettingLabel> labels) {
		Map<String, String> read;
		try {
			read = SettingsBridge.readVanilla(options);
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read the vanilla options for undo", e);
			read = Map.of();
		}
		this.vanilla = read;
		this.captions = SettingsBridge.captions(options);
		this.labels = labels;
		this.targets = targets;
		this.modsDir = modsDir;
	}

	// As the RigTune screen shows settings: a vanilla option's caption, a mod's key as its recommendation names it, and
	// On/Off for booleans.
	@Override
	public String label(String key) {
		return label(key, captions, labels);
	}

	static String label(String key, Map<String, String> captions, Map<String, SettingLabel> labels) {
		if (!key.startsWith(SettingsBridge.VANILLA_PREFIX)) {
			return SettingValues.name(labels.get(key), key);
		}
		String option = key.substring(SettingsBridge.VANILLA_PREFIX.length());
		String caption = captions.get(option);
		return caption == null || caption.isBlank() ? option : caption;
	}

	static String valueLabel(String key, String value, Map<String, SettingLabel> labels) {
		return SettingValues.valueLabel(labels.get(key), value);
	}

	@Override
	public String value(String key, String value) {
		String labelled = valueLabel(key, value, labels);
		if (!labelled.equals(value)) {
			return labelled;
		}
		return switch (value) {
			case "true" -> CommonComponents.OPTION_ON.getString();
			case "false" -> CommonComponents.OPTION_OFF.getString();
			default -> value;
		};
	}

	@Override
	public String setting(String key) {
		if (key.startsWith(SettingsBridge.VANILLA_PREFIX)) {
			return vanilla.get(key.substring(SettingsBridge.VANILLA_PREFIX.length()));
		}
		ConfigTargets.Target target = ConfigTargets.forKey(targets, key);
		return target == null ? null : config.computeIfAbsent(target, t -> t.reader().read(t.file())).get(key.substring(target.prefix().length()));
	}

	// docs/v0.4/SPEC.md 2n: a staged op's key, as Staging maps it when journaling (the op's file picks the namespace).
	@Override
	public String keyOf(PendingActions.Op op, String keyInFile) {
		ConfigTargets.Target target = Staging.targetOf(targets, op);
		return target == null ? null : target.prefix() + keyInFile;
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
