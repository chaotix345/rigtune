package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.CpuInfo;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.CpuTierRule;

import java.util.List;
import java.util.regex.Pattern;

public final class CpuClassifier {
	public static final int UNKNOWN_CORES_TIER = 3;

	private final List<CpuTierRule> rules;

	public CpuClassifier(List<CpuTierRule> rules) {
		this.rules = rules == null ? List.of() : rules;
	}

	public static CpuClassifier from(RulesDocument rules) {
		return new CpuClassifier(rules.cpuTiers);
	}

	public int classify(CpuInfo cpu) {
		if (cpu == null) {
			return UNKNOWN_CORES_TIER;
		}
		if (cpu.name() != null) {
			for (CpuTierRule rule : rules) {
				Pattern pattern = rule.compiled();
				if (pattern != null && pattern.matcher(cpu.name()).find()) {
					return clamp(rule.tier);
				}
			}
		}
		return formula(cpu.logicalCores(), cpu.maxFreqMhz());
	}

	public static int formula(int logicalCores, long maxFreqMhz) {
		if (logicalCores <= 0) {
			return UNKNOWN_CORES_TIER;
		}
		int tier;
		if (logicalCores <= 2) {
			tier = 1;
		} else if (logicalCores <= 4) {
			tier = 2;
		} else if (logicalCores <= 8) {
			tier = 3;
		} else if (logicalCores <= 12) {
			tier = 4;
		} else {
			tier = 5;
		}
		if (maxFreqMhz > 0 && maxFreqMhz < 2500) {
			tier--;
		}
		return clamp(tier);
	}

	private static int clamp(int tier) {
		return Math.max(1, Math.min(5, tier));
	}
}
