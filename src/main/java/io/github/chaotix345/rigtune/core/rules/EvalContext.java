package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.TierResult;

import java.util.Set;

public record EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds) {
}
