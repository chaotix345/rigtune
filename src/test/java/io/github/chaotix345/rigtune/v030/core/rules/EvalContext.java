package io.github.chaotix345.rigtune.v030.core.rules;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierResult;

import java.util.Map;
import java.util.Set;

// modVersions: installed mod id -> version string, for the modVersion condition. A loaded mod without an entry has an
// unknown version. settings: the current settings, for the settingIs condition; a key it doesn't have is unknown.
public record EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds,
		Map<String, String> modVersions, SettingsSnapshot settings) {
	public EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds) {
		this(hardware, gpu, tier, goal, loadedModIds, Map.of());
	}

	public EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds,
			Map<String, String> modVersions) {
		this(hardware, gpu, tier, goal, loadedModIds, modVersions, new SettingsSnapshot(Map.of()));
	}
}
