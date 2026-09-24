package io.github.chaotix345.rigtune.core.model;

/** maxFreqMhz is -1 when unknown. */
public record CpuInfo(String name, int physicalCores, int logicalCores, long maxFreqMhz) {
}
