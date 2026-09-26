package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.stutter.StutterFacts;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;

// modVersions: installed mod id -> version string, for the modVersion condition. A loaded mod without an entry has an
// unknown version. settings: the current settings, for the settingIs condition; a key it doesn't have is unknown.
// stutter (v0.4, docs/v0.4/SPEC.md 5): the Stutter Doctor's session facts for the stutter keys; null (the main list)
// makes every stutter key UNKNOWN.
public record EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds,
		Map<String, String> modVersions, SettingsSnapshot settings, @Nullable StutterFacts stutter) {
	public EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds) {
		this(hardware, gpu, tier, goal, loadedModIds, Map.of());
	}

	public EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds,
			Map<String, String> modVersions) {
		this(hardware, gpu, tier, goal, loadedModIds, modVersions, new SettingsSnapshot(Map.of()));
	}

	public EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds,
			Map<String, String> modVersions, SettingsSnapshot settings) {
		this(hardware, gpu, tier, goal, loadedModIds, modVersions, settings, null);
	}

	public EvalContext withStutter(@Nullable StutterFacts facts) {
		return new EvalContext(hardware, gpu, tier, goal, loadedModIds, modVersions, settings, facts);
	}
}
