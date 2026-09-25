package io.github.chaotix345.rigtune.v010.core.rules;

import io.github.chaotix345.rigtune.v010.core.model.Goal;
import io.github.chaotix345.rigtune.v010.core.model.GpuClass;
import io.github.chaotix345.rigtune.v010.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.v010.core.model.TierResult;

import java.util.Set;

public record EvalContext(HardwareProfile hardware, GpuClass gpu, TierResult tier, Goal goal, Set<String> loadedModIds) {
}
