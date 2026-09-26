package io.github.chaotix345.rigtune.core.model;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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

	// The component(s) with the lowest estimated tier, as TierResult's factor ids ("gpu", "cpu", "mem") in that order; a tie
	// lists every tied component (a 5/5/5 machine: all three). An estimate, never a measured limit (external review §1).
	public static List<String> lowest(@Nullable TierResult tier) {
		if (tier == null) {
			return List.of();
		}
		int min = Math.min(tier.gpuTier(), Math.min(tier.cpuTier(), tier.memTier()));
		List<String> out = new ArrayList<>();
		if (tier.gpuTier() == min) {
			out.add("gpu");
		}
		if (tier.cpuTier() == min) {
			out.add("cpu");
		}
		if (tier.memTier() == min) {
			out.add("mem");
		}
		return List.copyOf(out);
	}
}
