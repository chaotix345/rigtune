package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.TierResult;

import java.util.Map;
import java.util.Set;

// modVersions: installed mod id -> version string, for the modVersion condition. A loaded mod without an entry has an
// unknown version.
public record EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds,
		Map<String, String> modVersions) {
	public EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds) {
		this(hardware, gpu, tier, goal, loadedModIds, Map.of());
	}
}
