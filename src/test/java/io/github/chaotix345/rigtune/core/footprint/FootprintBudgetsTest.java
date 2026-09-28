package io.github.chaotix345.rigtune.core.footprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets.Budget;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets.Mode;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets.Violation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
			// The phase timers' case has its own ceiling (8 System.nanoTime reads per frame; coordinator, 2026-09-26).
			Map.entry("frameHookNsPerCallOnPhases", 400.0),
			Map.entry("frameHookAllocBytesOnPhases", 0.0),
			Map.entry("tickHookNsPerCall", 2000.0),
			Map.entry("tickHookAllocBytes", 0.0),
			Map.entry("tickHookNsPerCallWorld", 2000.0),
			Map.entry("tickHookAllocBytesWorld", 0.0),
			Map.entry("tickHookNsPerCallOn", 2000.0),
			Map.entry("tickHookAllocBytesOn", 0.0),
			// X4.4: the v0.5 listeners with their own END_CLIENT_TICK registration stay inside the tick budgets.
			Map.entry("settingsCheckNsPerCall", 2000.0),
			Map.entry("settingsCheckAllocBytes", 0.0),
			Map.entry("tryItTickNsPerCall", 2000.0),
			Map.entry("tryItTickAllocBytes", 0.0),
			Map.entry("serverProfileTickNsPerCall", 2000.0),
			Map.entry("serverProfileTickAllocBytes", 0.0),
			Map.entry("launcherLeftoverTickNsPerCall", 2000.0),
			Map.entry("launcherLeftoverTickAllocBytes", 0.0),
			Map.entry("rigtuneClassBytesIdle", 8 * MIB),
			Map.entry("leakSuspects", 0.0),
			Map.entry("monitorOnRetainedBytes", 2.5 * MIB),
			Map.entry("monitorOffRetainedBytes", 0.25 * MIB),
			Map.entry("monitorOffLeftoverInstances", 0.0),
			// The sampler in steady state: 120 ms per 60 s (0.2 % of one core, opt-in monitor only; SPEC 10 said 30, the CI
			// runners measured 42-50 ms after the WS-F2 optimisation; coordinator, 2026-09-26).
			Map.entry("samplerCpuMsPer60s", 120.0));

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

	// v0.5 (docs/v0.5/design/ws-ci.md): the monitor-on tick and frame work are gated as median ratios against a reference
	// workload. Each limit sits between the largest 1x ratio and the smallest 2x ratio of its calibration (recorded next to
	// it); FootprintGameTest and FrameHookBudgetTest fail any run whose own doubled-work ratio doesn't exceed it.
	@Test
	void theRatioGatesSitBetweenTheirCalibratedOneAndTwoTimes() throws IOException {
		JsonObject budgets = JsonParser.parseString(Files.readString(RepoFiles.resolve(FootprintBudgets.REPO_PATH), StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonObject("budgets");
		for (String key : List.of("tickHookOnVsReference", "frameHookOnVsReference", "frameHookOnPhasesVsReference")) {
			JsonObject b = budgets.getAsJsonObject(key);
			assertNotNull(b, key);
			assertTrue(b.get("ceiling").isJsonNull(), key + ": no SPEC ceiling for a ratio");
			double limit = b.get("limit").getAsDouble();
			assertTrue(b.get("max1x").getAsDouble() < limit && limit < b.get("min2x").getAsDouble(), key + " limit " + limit);
		}
	}

	// v0.5 (coordinator, user's instruction): the 0-allocation keys stay strict. A tick hook that allocates in one of its
	// 48 timed blocks fails, wherever that block falls; the fewest-allocating block (0 here) would have passed it.
	@Test
	void aTickHookAllocatingInOneOf48BlocksFailsItsZeroAllocationBudget() throws IOException {
		FootprintBudgets budgets = FootprintBudgets.load(RepoFiles.resolve(FootprintBudgets.REPO_PATH));
		for (String key : List.of("tickHookAllocBytes", "tickHookAllocBytesWorld", "tickHookAllocBytesOn")) {
			for (int block : new int[] {0, 23, 47}) {
				long[] perBlock = new long[48];
				perBlock[block] = 16;
				List<Violation> violations = budgets.check(Map.of(key, FootprintBudgets.allocatedBytes(perBlock)));
				assertEquals(1, violations.size(), key + ", block " + block);
				assertEquals(16, violations.getFirst().value(), key);
			}
			assertEquals(List.of(), budgets.check(Map.of(key, FootprintBudgets.allocatedBytes(new long[48]))), key + " with no allocation");
		}
	}

	// Coordinator: a hook that allocates once every 30,000 calls. Across FootprintGameTest's 48 blocks of 20,000 calls
	// (960,000 calls after warm-up) a third of the blocks see no allocation, so the fewest-allocating block (0) passed it;
	// the sum over every block fails it, as v0.4's 5 runs of 100,000 calls did.
	@Test
	void aTickHookAllocatingEvery30000CallsFailsItsZeroAllocationBudget() throws IOException {
		FootprintBudgets budgets = FootprintBudgets.load(RepoFiles.resolve(FootprintBudgets.REPO_PATH));
		int blocks = 48;
		int blockCalls = 20_000;
		long[] perBlock = new long[blocks];
		for (long call = 30_000; call <= (long) blocks * blockCalls; call += 30_000) {
			perBlock[(int) ((call - 1) / blockCalls)] += 24;
		}
		assertEquals(0, java.util.Arrays.stream(perBlock).min().orElseThrow(), "some blocks see no allocation");

		for (String key : List.of("tickHookAllocBytes", "tickHookAllocBytesWorld", "tickHookAllocBytesOn")) {
			List<Violation> violations = budgets.check(Map.of(key, FootprintBudgets.allocatedBytes(perBlock)));
			assertEquals(1, violations.size(), key);
			assertEquals(32 * 24, violations.getFirst().value(), key + ": 32 allocations of 24 B in 960,000 calls");
		}
	}

	// The floor of a per-call limit whose observed maximum is under it (SPEC 1d(c)/1h).
	private static final double SUB_10_NS = 10;

	// Every per-call ns budget: v0.5's six, and the per-tick listeners' own keys (X4.4).
	private static final List<String> PER_CALL_KEYS = List.of("frameHookNsPerCallOff", "frameHookNsPerCallOn", "frameHookNsPerCallOnPhases",
			"tickHookNsPerCall", "tickHookNsPerCallWorld", "tickHookNsPerCallOn", "settingsCheckNsPerCall", "tryItTickNsPerCall",
			"serverProfileTickNsPerCall", "launcherLeftoverTickNsPerCall");

	// v0.5 SPEC 1h (AC1h.1): the post-Wave-B checkpoint re-applied min(ceiling, 4 x max observed) to every per-call ns budget,
	// the new listeners' keys included, each from at least 20 CI runs (their count and the run with the maximum recorded, the
	// checkpoint named in the file's about); nothing else changed.
	@Test
	void everyPerCallBudgetComesFromTheCheckpoint() throws IOException {
		JsonObject file = JsonParser.parseString(Files.readString(RepoFiles.resolve(FootprintBudgets.REPO_PATH), StandardCharsets.UTF_8))
				.getAsJsonObject();
		JsonObject budgets = file.getAsJsonObject("budgets");
		assertEquals(PER_CALL_KEYS.stream().sorted().toList(), budgets.keySet().stream().filter(k -> k.contains("NsPerCall")).sorted().toList());
		for (String key : PER_CALL_KEYS) {
			JsonObject b = budgets.getAsJsonObject(key);
			assertEquals("4x", b.get("rule").getAsString(), key);
			assertTrue(b.get("observedRuns").getAsInt() >= 20, key + ": " + b.get("observedRuns") + " runs");
			assertTrue(b.get("observedMaxRun").getAsLong() > 0, key + ": the run with the maximum");
		}
		assertTrue(file.get("about").getAsString().contains("post-Wave-B checkpoint"), "the about names the checkpoint");
	}

	// v0.5 SPEC AC1d.1: the six per-call ns limits are min(ceiling, 4 x the recorded max observed) (user-approved, ws-ci);
	// every other timing limit stays min(ceiling, 2 x its recorded max) (docs/v0.4/verification/footprint/README.md).
	@Test
	void timingLimitsFollowTheirRecordedRule() throws IOException {
		JsonObject budgets = JsonParser.parseString(Files.readString(RepoFiles.resolve(FootprintBudgets.REPO_PATH), StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonObject("budgets");
		Map<String, String> rules = new TreeMap<>();
		budgets.entrySet().forEach(entry -> {
			JsonObject b = entry.getValue().getAsJsonObject();
			if (!b.has("rule")) {
				return;
			}
			String rule = b.get("rule").getAsString();
			double factor = switch (rule) {
				case "4x" -> 4;
				case "2x" -> 2;
				default -> throw new AssertionError(entry.getKey() + ": rule " + rule);
			};
			double observedMax = b.get("observedMax").getAsDouble();
			double expected = Math.ceil(factor * observedMax - 1e-9);
			if (!b.get("ceiling").isJsonNull()) {
				expected = Math.min(expected, b.get("ceiling").getAsDouble());
			}
			// SPEC 1d(c)/1h (coordinator, 2026-09-28): a per-call key observed under 10 ns is a gross-regression backstop only,
			// with a 10 ns floor, so a JIT deopt or a slow timer read can't fail it (flake safety).
			if (rule.equals("4x") && observedMax < SUB_10_NS) {
				expected = Math.max(SUB_10_NS, expected);
			}
			assertEquals(expected, b.get("limit").getAsDouble(), entry.getKey());
			rules.put(entry.getKey(), rule);
		});
		Map<String, String> expected = new TreeMap<>();
		for (String key : PER_CALL_KEYS) {
			expected.put(key, "4x");
		}
		for (String key : List.of("renderThreadInitWallMs", "renderThreadInitCpuMs", "clientStartedWallMs", "workerCpuMs5s", "samplerCpuMsPer60s")) {
			expected.put(key, "2x");
		}
		assertEquals(expected, rules);
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

	// P5-A F5: without compressed oops (ZGC) the same objects measure up to about twice as big in a class histogram, so the
	// shallow-size budget doubles there (never past its ceiling); every other budget, the leak checks included, stays.
	@Test
	void shallowSizeBudgetsDoubleWithoutCompressedOops() throws IOException {
		FootprintBudgets committed = FootprintBudgets.load(RepoFiles.resolve(FootprintBudgets.REPO_PATH));
		assertEquals(committed.budgets(), committed.forCompressedOops(true).budgets());
		FootprintBudgets zgc = committed.forCompressedOops(false);
		double idle = committed.budgets().get("rigtuneClassBytesIdle").limit();
		assertEquals(2 * idle, zgc.budgets().get("rigtuneClassBytesIdle").limit());
		for (String key : committed.budgets().keySet()) {
			if (!FootprintBudgets.SHALLOW_SIZE_KEYS.contains(key)) {
				assertEquals(committed.budgets().get(key), zgc.budgets().get(key), key);
			}
		}
		assertEquals(0, zgc.budgets().get("leakSuspects").limit());
		assertEquals(committed.mode(), zgc.mode());
		Map<String, Number> zgcRun = Map.of("rigtuneClassBytesIdle", 120_224, "leakSuspects", 0);
		assertEquals(1, committed.check(zgcRun).size(), "the local ZGC run's 120,224 bytes against the G1 limit");
		assertEquals(List.of(), zgc.check(zgcRun));

		FootprintBudgets capped = FootprintBudgets.parse("{\"mode\": \"fail\", \"budgets\": {\"rigtuneClassBytesIdle\": {\"limit\": 700, \"ceiling\": 1000}}}")
				.forCompressedOops(false);
		assertEquals(1000, capped.budgets().get("rigtuneClassBytesIdle").limit(), "never past the ceiling");
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
