package io.github.chaotix345.rigtune.core.hardware;

import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.rules.RulesDocument.HeapTierRule;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TierCalculatorTest {
	@Test
	void tierIsMinimumWithLimitingFactor() {
		assertEquals(new TierResult(5, 5, 5, 5, 5, "gpu"), TierCalculator.calculate(5, 5, 5, Goal.BALANCED));
		assertEquals(new TierResult(3, 3, 4, 3, 5, "cpu"), TierCalculator.calculate(4, 3, 5, Goal.BALANCED));
		assertEquals(new TierResult(2, 2, 5, 5, 2, "mem"), TierCalculator.calculate(5, 5, 2, Goal.BALANCED));
	}

	@Test
	void tiesPreferGpuThenCpu() {
		assertEquals("gpu", TierCalculator.calculate(3, 3, 3, Goal.BALANCED).limitingFactor());
		assertEquals("cpu", TierCalculator.calculate(4, 3, 3, Goal.BALANCED).limitingFactor());
	}

	@Test
	void goalShiftsEffectiveTierWithinOneToFive() {
		assertEquals(2, TierCalculator.calculate(3, 5, 5, Goal.PERFORMANCE).effectiveTier());
		assertEquals(4, TierCalculator.calculate(3, 5, 5, Goal.QUALITY).effectiveTier());
		assertEquals(5, TierCalculator.calculate(5, 5, 5, Goal.QUALITY).effectiveTier());
		assertEquals(1, TierCalculator.calculate(1, 5, 5, Goal.PERFORMANCE).effectiveTier());
	}

	@Test
	void softwareRenderingStaysAtZero() {
		TierResult result = TierCalculator.calculate(0, 5, 5, Goal.QUALITY);
		assertEquals(0, result.rawTier());
		assertEquals(0, result.effectiveTier());
		assertEquals("gpu", result.limitingFactor());
	}

	@Test
	void heapTierPicksHighestThresholdReached() {
		List<HeapTierRule> rules = RulesLoader.loadBundled().heapTiers;
		assertEquals(5, TierCalculator.heapTier(rules, 6144));
		assertEquals(4, TierCalculator.heapTier(rules, 4096));
		assertEquals(3, TierCalculator.heapTier(rules, 3072));
		assertEquals(2, TierCalculator.heapTier(rules, 2048));
		assertEquals(1, TierCalculator.heapTier(rules, 1024));
		assertEquals(1, TierCalculator.heapTier(List.of(), 8192));
	}

	@Test
	void heapTierSortsRules() {
		HeapTierRule low = new HeapTierRule();
		low.atLeastMb = 1000;
		low.tier = 2;
		HeapTierRule high = new HeapTierRule();
		high.atLeastMb = 4000;
		high.tier = 4;
		assertEquals(4, TierCalculator.heapTier(List.of(low, high), 5000));
		assertEquals(2, TierCalculator.heapTier(List.of(low, high), 2000));
	}
}
