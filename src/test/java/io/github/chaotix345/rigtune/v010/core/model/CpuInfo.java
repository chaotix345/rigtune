package io.github.chaotix345.rigtune.v010.core.model;

/** maxFreqMhz is -1 when unknown. */
public record CpuInfo(String name, int physicalCores, int logicalCores, long maxFreqMhz) {
}
