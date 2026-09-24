package io.github.chaotix345.rigtune.core.model;

/** limitingFactor is "gpu", "cpu" or "mem". */
public record TierResult(int rawTier, int effectiveTier, int gpuTier, int cpuTier, int memTier, String limitingFactor) {
}
