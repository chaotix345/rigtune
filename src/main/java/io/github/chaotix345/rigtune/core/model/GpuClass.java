package io.github.chaotix345.rigtune.core.model;

/** Classification derived from GpuInfo. tier 0 = software rendering, 1..5 weak..strong. */
public record GpuClass(GpuVendor vendor, boolean integrated, int tier, String matchedPattern) {
}
