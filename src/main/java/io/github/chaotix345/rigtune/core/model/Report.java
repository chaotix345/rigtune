package io.github.chaotix345.rigtune.core.model;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

// tierBasis (0.4.0, docs/v0.4/SPEC.md 2j): what each component's tier rests on; null until the Recommender fills it.
public record Report(
		HardwareProfile hardware,
		GpuClass gpuClass,
		TierResult tier,
		Goal goal,
		List<Recommendation> recommendations,
		int rulesRevision,
		String rulesSource,
		boolean online,
		Instant createdAt,
		@Nullable TierBasis tierBasis) {
	public Report(HardwareProfile hardware, GpuClass gpuClass, TierResult tier, Goal goal, List<Recommendation> recommendations,
			int rulesRevision, String rulesSource, boolean online, Instant createdAt) {
		this(hardware, gpuClass, tier, goal, recommendations, rulesRevision, rulesSource, online, createdAt, null);
	}
}
