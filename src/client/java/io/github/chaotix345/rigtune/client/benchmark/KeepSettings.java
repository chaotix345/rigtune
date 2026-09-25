package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// Keeping a benchmark result: apply the values and journal every vanilla option that changed as one entry of kind
// "benchmark" (docs/v0.2/SPEC.md item 3), so Undo can revert it.
public final class KeepSettings {
	private KeepSettings() {
	}

	public static List<JournalChange> apply(Minecraft minecraft, Map<String, String> values) {
		Map<String, String> before = SettingsBridge.readVanilla(minecraft.options);
		SettingsBridge.applyVanilla(minecraft.options, values).values().stream()
				.filter(r -> !r.ok())
				.forEach(r -> RigTune.LOGGER.warn("Benchmark result: could not set {}: {}", r.key(), r.message()));
		Map<String, String> after = SettingsBridge.readVanilla(minecraft.options);
		List<JournalChange> changes = new ArrayList<>();
		after.forEach((key, value) -> {
			if (!Objects.equals(before.get(key), value)) {
				changes.add(JournalChange.setting(SettingsBridge.VANILLA_PREFIX + key, before.get(key), value, JournalChange.APPLIED, null));
			}
		});
		if (!changes.isEmpty()) {
			try {
				ChangeRecorder.current().record(ChangeRecorder.newEntryId(), JournalEntry.BENCHMARK, changes);
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Could not journal the benchmark settings", e);
			}
		}
		return changes;
	}
}
