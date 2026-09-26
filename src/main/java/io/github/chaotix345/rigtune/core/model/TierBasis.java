package io.github.chaotix345.rigtune.core.model;

import org.jspecify.annotations.Nullable;

// docs/v0.4/SPEC.md 2j: what each component's tier rests on, for the tier badge's tooltip ("GPU tier 4 (table match)",
// "CPU tier 5 (fallback estimate from 16 threads)", "Memory tier 5 (6 GB heap)"). Plain data; the Recommender fills it.
// GPU: TABLE_MATCH when a gpuTiers pattern matched (matchedPattern), else FALLBACK_ESTIMATE (vendor fallback).
// CPU: TABLE_MATCH when a cpuTiers pattern matched, else FALLBACK_ESTIMATE from logicalCores and maxFreqMhz (-1 unknown).
// Memory: always the heapTiers table over heapMb.
public record TierBasis(Gpu gpu, Cpu cpu, Memory memory) {
	public enum Basis { TABLE_MATCH, FALLBACK_ESTIMATE }

	public record Gpu(int tier, Basis basis, @Nullable String matchedPattern, @Nullable GpuVendor vendor, boolean integrated) {
	}

	public record Cpu(int tier, Basis basis, @Nullable String matchedPattern, int logicalCores, long maxFreqMhz) {
	}

	public record Memory(int tier, Basis basis, long heapMb) {
	}
}
