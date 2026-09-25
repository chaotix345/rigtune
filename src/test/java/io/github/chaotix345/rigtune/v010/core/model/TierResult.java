package io.github.chaotix345.rigtune.v010.core.model;

/** limitingFactor is "gpu", "cpu" or "mem". */
public record TierResult(int rawTier, int effectiveTier, int gpuTier, int cpuTier, int memTier, String limitingFactor) {
}
