package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The settings as the next restart leaves them (docs/research/v0.4/audit-apply-pipeline.md M1, the same idea as SPEC 2n): a
// Sodium/DH/Iris key an earlier Apply or switch in this start has staged is worth what its LAST still-pending patch op sets,
// not what its file says now; every other key is worth the file's value. Profiles decide "differs" and show "current"
// against this, so A -> B -> A before a restart stages A again instead of leaving B pending. A staged key the file doesn't
// have is not added (RigTune never creates a key).
public final class EffectiveSettings {
	private EffectiveSettings() {
	}

	// files: SettingsBridge.read. pending: pending.json's ops in order (the relocated view). fileByPrefix: each config
	// namespace's prefix ("sodium.") -> its file (ConfigTargets).
	public static SettingsSnapshot of(SettingsSnapshot files, List<Op> pending, Map<String, Path> fileByPrefix) {
		Map<String, String> values = new LinkedHashMap<>(files.values());
		for (Op op : pending) {
			if (op == null || op.patches() == null || op.path() == null || !patch(op.type())) {
				continue;
			}
			String prefix = prefixOf(op.path(), fileByPrefix);
			if (prefix == null) {
				continue;
			}
			op.patches().forEach((keyInFile, value) -> {
				String key = prefix + keyInFile;
				if (value != null && values.containsKey(key)) {
					values.put(key, value);
				}
			});
		}
		return new SettingsSnapshot(values);
	}

	private static boolean patch(PendingActions.Type type) {
		return type == PendingActions.Type.PATCH_JSON || type == PendingActions.Type.PATCH_TOML || type == PendingActions.Type.PATCH_PROPERTIES;
	}

	private static String prefixOf(String opPath, Map<String, Path> fileByPrefix) {
		Path path;
		try {
			path = Path.of(opPath).toAbsolutePath().normalize();
		} catch (RuntimeException e) {
			return null;
		}
		for (Map.Entry<String, Path> entry : fileByPrefix.entrySet()) {
			if (entry.getValue().toAbsolutePath().normalize().equals(path)) {
				return entry.getKey();
			}
		}
		return null;
	}
}
