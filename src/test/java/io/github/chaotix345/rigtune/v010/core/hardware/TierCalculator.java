package io.github.chaotix345.rigtune.v010.core.hardware;

import io.github.chaotix345.rigtune.v010.core.model.Goal;
import io.github.chaotix345.rigtune.v010.core.model.TierResult;
import io.github.chaotix345.rigtune.v010.core.rules.RulesDocument.HeapTierRule;

import java.util.Comparator;
import java.util.List;

public final class TierCalculator {
	private TierCalculator() {
	}

	public static int heapTier(List<HeapTierRule> rules, long heapMb) {
		if (rules == null) {
			return 1;
		}
		return rules.stream()
				.sorted(Comparator.comparingLong((HeapTierRule r) -> r.atLeastMb).reversed())
				.filter(r -> heapMb >= r.atLeastMb)
				.findFirst()
				.map(r -> Math.max(1, Math.min(5, r.tier)))
				.orElse(1);
	}

	public static TierResult calculate(int gpuTier, int cpuTier, int memTier, Goal goal) {
		int raw = gpuTier;
		String limiting = "gpu";
		if (cpuTier < raw) {
			raw = cpuTier;
			limiting = "cpu";
		}
		if (memTier < raw) {
			raw = memTier;
			limiting = "mem";
		}
		int effective = raw <= 0 ? 0 : Math.max(1, Math.min(5, raw + goal.tierOffset()));
		return new TierResult(raw, effective, gpuTier, cpuTier, memTier, limiting);
	}
}
