package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets;
import io.github.chaotix345.rigtune.gametest.FootprintGameTest.TickTiming;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// FootprintGameTest's tickHookOnVsReference re-measure, as FrameHookBudgetTest.anOutlierIsMeasuredOnceMoreAndARegressionStillFails:
// against the real budgets file (limit 2.05), one measure over the limit then a passing one passes, a first measure under
// the limit is the only one, and a ratio over the limit both times fails.
class FootprintTickRemeasureTest {
	private static final String KEY = "tickHookOnVsReference";

	@Test
	void anOutlierIsMeasuredOnceMoreAndARegressionStillFails() throws IOException {
		FootprintBudgets budgets = FootprintBudgets.load();
		FootprintBudgets.Budget budget = budgets.budgets().get(KEY);
		assertEquals(2.05, budget.limit(), 1e-9);
		List<String> log = new ArrayList<>();

		List<TickTiming> outlier = FootprintGameTest.remeasuredOverLimit(timing(2.30), budget, () -> timing(1.60), log::add);
		assertEquals(List.of(2.30, 1.60), ratios(outlier));
		budgets.enforce(budgets.check(Map.of(KEY, outlier.getLast().vsReference())), w -> fail(w));
		assertEquals(3, log.size(), log.toString());
		assertTrue(log.get(0).contains(KEY + " 2.300 (twice the work 4.600)"), log.get(0));
		assertTrue(log.get(1).contains(KEY + " 2.300 is over its limit 2.05: measuring once more"), log.get(1));
		assertTrue(log.get(2).contains(KEY + " 1.600 (twice the work 3.200)"), log.get(2));

		List<TickTiming> normal = FootprintGameTest.remeasuredOverLimit(timing(1.60), budget, () -> fail("no second measure"), log::add);
		assertEquals(List.of(1.60), ratios(normal));

		List<TickTiming> regression = FootprintGameTest.remeasuredOverLimit(timing(2.30), budget, () -> timing(2.20), log::add);
		assertEquals(List.of(2.30, 2.20), ratios(regression));
		AssertionError over = assertThrows(AssertionError.class,
				() -> budgets.enforce(budgets.check(Map.of(KEY, regression.getLast().vsReference())), w -> fail(w)));
		assertTrue(over.getMessage().contains(KEY + ": 2.20 > 2.05"), over.getMessage());
	}

	private static TickTiming timing(double ratio) {
		return new TickTiming(30, 0, ratio, 2 * ratio, Map.of());
	}

	private static List<Double> ratios(List<TickTiming> timings) {
		return timings.stream().map(TickTiming::vsReference).toList();
	}
}
