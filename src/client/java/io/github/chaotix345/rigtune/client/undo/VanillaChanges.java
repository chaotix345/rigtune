package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// Vanilla settings are applied at once, so they're journaled as APPLIED with the values read from Options right
// before and after the write. graphicsPreset rewrites a dozen other options, so the whole snapshot is compared and
// every option that changed is recorded (review M7); a write that failed changed nothing and isn't recorded.
public final class VanillaChanges {
	private VanillaChanges() {
	}

	public static Map<String, SettingsBridge.Result> apply(String entryId, Map<String, String> values) {
		Options options = Minecraft.getInstance().options;
		Map<String, String> before = snapshot(options);
		Map<String, SettingsBridge.Result> results = SettingsBridge.applyVanilla(options, values);
		Map<String, String> after = snapshot(options);
		if (before != null && after != null) {
			ChangeRecorder.current().record(entryId, JournalEntry.APPLY, diff(before, after));
		}
		return results;
	}

	private static Map<String, String> snapshot(Options options) {
		try {
			return SettingsBridge.readVanilla(options);
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read the vanilla options for the RigTune journal", e);
			return null;
		}
	}

	static List<JournalChange> diff(Map<String, String> before, Map<String, String> after) {
		Set<String> keys = new LinkedHashSet<>(before.keySet());
		keys.addAll(after.keySet());
		List<JournalChange> out = new ArrayList<>();
		for (String key : keys) {
			if (!Objects.equals(before.get(key), after.get(key))) {
				out.add(JournalChange.setting(SettingsBridge.VANILLA_PREFIX + key, before.get(key), after.get(key), JournalChange.APPLIED, null));
			}
		}
		return out;
	}
}
