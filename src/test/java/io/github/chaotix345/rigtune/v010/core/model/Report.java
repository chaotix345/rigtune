package io.github.chaotix345.rigtune.v010.core.model;

import java.time.Instant;
import java.util.List;

public record Report(
		HardwareProfile hardware,
		GpuClass gpuClass,
		TierResult tier,
		Goal goal,
		List<Recommendation> recommendations,
		int rulesRevision,
		String rulesSource,
		boolean online,
		Instant createdAt) {
}
