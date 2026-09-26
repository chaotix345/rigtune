package io.github.chaotix345.rigtune.core.footprint;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets.Budget;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets.Mode;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets.Violation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 10: the committed budgets file parses, every limit stays within the SPEC's ceilings, and the gate
// warns or fails by mode.
class FootprintBudgetsTest {
	private static final double MIB = 1024 * 1024;
	// The SPEC's ceilings (render-thread init as amended for WS-F from the calibration: SPEC 10 said 25 ms wall / 15 ms
	// CPU; measured up to 184 ms wall on a descheduled CI runner and 96 ms CPU); a limit may be tightened by calibration,
	// never raised past these.
	private static final Map<String, Double> SPEC_CEILINGS = Map.ofEntries(
			Map.entry("renderThreadInitWallMs", 400.0),
			Map.entry("renderThreadInitCpuMs", 150.0),
			Map.entry("workerCpuMs5s", 300.0),
			Map.entry("frameHookNsPerCallOff", 20.0),
			Map.entry("frameHookAllocBytesOff", 0.0),
			Map.entry("frameHookNsPerCallOn", 200.0),
			Map.entry("frameHookAllocBytesOn", 0.0),
			Map.entry("tickHookNsPerCall", 2000.0),
			Map.entry("tickHookAllocBytes", 0.0),
			Map.entry("rigtuneClassBytesIdle", 8 * MIB),
			Map.entry("leakSuspects", 0.0),
			Map.entry("monitorOnRetainedBytes", 2.5 * MIB),
			Map.entry("monitorOffRetainedBytes", 0.25 * MIB),
			Map.entry("samplerCpuMsPer60s", 30.0));

	@Test
	void theCommittedFileKeepsEveryLimitWithinTheSpecCeilings() throws IOException {
		FootprintBudgets budgets = FootprintBudgets.load(RepoFiles.resolve(FootprintBudgets.REPO_PATH));
		for (Map.Entry<String, Double> spec : SPEC_CEILINGS.entrySet()) {
			Budget budget = budgets.budgets().get(spec.getKey());
			assertNotNull(budget, "budget " + spec.getKey());
			assertEquals(spec.getValue(), budget.ceiling(), "ceiling of " + spec.getKey());
			assertTrue(budget.limit() <= spec.getValue(), spec.getKey() + " limit " + budget.limit());
		}
		assertTrue(budgets.budgets().keySet().containsAll(List.of("clientStartedWallMs", "heapGrowthAfterCyclesBytes")));
	}

	@Test
	void locateFindsTheRepositoryFile() {
		assertTrue(FootprintBudgets.locate().toString().replace('\\', '/').endsWith(FootprintBudgets.REPO_PATH));
	}

	@Test
	void checkReportsOnlyMeasuredValuesPastTheirLimit() {
		FootprintBudgets budgets = FootprintBudgets.parse("""
				{"mode": "warn", "budgets": {
				  "a": {"limit": 10, "ceiling": 20, "what": "A"},
				  "b": {"limit": 0, "ceiling": 0},
				  "c": {"limit": 5, "ceiling": null}}}""");
		Map<String, Number> measured = new HashMap<>();
		measured.put("a", 10);
		measured.put("b", 1);
		measured.put("c", null);
		measured.put("unbudgeted", 1e9);
		List<Violation> violations = budgets.check(measured);
		assertEquals(1, violations.size());
		assertEquals("b", violations.getFirst().key());
		assertEquals("footprint budget b: 1 > 0 (b)", violations.getFirst().message());
		assertEquals(List.of(), budgets.check(Map.of("a", 9.5)));
		assertEquals(1, budgets.check(Map.of("a", 10.01)).size());
	}

	@Test
	void warnModeLogsAndFailModeThrows() {
		String body = "\"budgets\": {\"a\": {\"limit\": 1, \"what\": \"A\"}}}";
		FootprintBudgets warn = FootprintBudgets.parse("{\"mode\": \"warn\", " + body);
		List<String> warnings = new ArrayList<>();
		warn.enforce(warn.check(Map.of("a", 2)), warnings::add);
		assertEquals(List.of("WARN-ONLY footprint budget a: 2 > 1 (A)"), warnings);

		FootprintBudgets fail = FootprintBudgets.parse("{\"mode\": \"fail\", " + body);
		assertEquals(Mode.FAIL, fail.mode());
		fail.enforce(fail.check(Map.of("a", 1)), w -> {
			throw new AssertionError("no warning expected: " + w);
		});
		AssertionError error = assertThrows(AssertionError.class, () -> fail.enforce(fail.check(Map.of("a", 2)), w -> {
		}));
		assertEquals("footprint budget a: 2 > 1 (A)", error.getMessage());
	}

	@Test
	void aLimitAboveItsCeilingOrAnUnknownModeIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> FootprintBudgets.parse(
				"{\"mode\": \"warn\", \"budgets\": {\"a\": {\"limit\": 30, \"ceiling\": 25}}}"));
		assertThrows(IllegalArgumentException.class, () -> FootprintBudgets.parse(
				"{\"mode\": \"loud\", \"budgets\": {}}"));
		assertThrows(IllegalArgumentException.class, () -> FootprintBudgets.parse(
				"{\"mode\": \"fail\", \"budgets\": {\"a\": {\"limit\": -1}}}"));
	}
}
