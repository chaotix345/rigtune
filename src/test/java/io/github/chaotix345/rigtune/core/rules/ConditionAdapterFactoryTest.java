package io.github.chaotix345.rigtune.core.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/plan-review.md K-M1: every condition key 0.4 adds is an adapter-vetted type, so a wrong-typed value poisons
// only the condition that has it; the rest of the rules document still loads.
class ConditionAdapterFactoryTest {
	// new key -> wrong-typed values for it
	private static final Map<String, List<String>> WRONG = Map.ofEntries(
			Map.entry("driverVersion", List.of("\"531.18\"", "{\"vendor\": {\"x\": 1}}", "[\"nvidia\"]", "7")),
			Map.entry("stutterShareAtLeast", List.of("30", "\"gc\"", "{\"gc\": [30]}", "{\"gc\": {\"v\": 30}}")),
			Map.entry("stutterTaggedShareAtLeast", List.of("40", "[\"dh\"]", "{\"dh\": {}}")),
			Map.entry("gcFullPausesAtLeast", List.of("\"1\"", "1.5", "true", "[1]", "{}")),
			Map.entry("gcStallsAtLeast", List.of("\"x\"", "0.5", "{}")),
			Map.entry("gcExplicitPausesAtLeast", List.of("\"2\"", "2.5", "[]")),
			Map.entry("liveSetPercentAtLeast", List.of("\"75\"", "75.5", "false")),
			Map.entry("heapRaiseRoomMbAtLeast", List.of("\"2048\"", "2048.5", "[2048]", "1e30")),
			Map.entry("cpuContentionShareAtLeast", List.of("\"30\"", "30.1", "{}")),
			Map.entry("spikesPerMinuteAtLeast", List.of("\"3\"", "0.3", "4294967296")),
			Map.entry("gcCollector", List.of("\"g1\"", "5", "[{\"g1\": true}]", "[[\"g1\"]]", "{\"g1\": true}")));

	private static String document(String key, String value) {
		return """
				{"schemaVersion": 2, "revision": 1,
				 "advice": [
				   {"id": "fine", "when": {"tierAtLeast": 1}, "title": "t", "text": "x"},
				   {"id": "bad", "when": {"%s": %s, "tierAtLeast": 1}, "title": "t", "text": "x"},
				   {"id": "nested", "when": {"anyOf": [{"%s": %s}, {"tierAtLeast": 1}]}, "title": "t", "text": "x"}],
				 "stutterAdvice": [{"id": "s", "requires": ["stutter-doctor"], "when": {"%s": %s}, "title": "t", "text": "x"}]}
				""".formatted(key, value, key, value, key, value);
	}

	@Test
	void everyNewKeyIsListed() {
		List<String> added = List.of("driverVersion", "stutterShareAtLeast", "stutterTaggedShareAtLeast", "gcFullPausesAtLeast", "gcStallsAtLeast",
				"gcExplicitPausesAtLeast", "liveSetPercentAtLeast", "heapRaiseRoomMbAtLeast", "cpuContentionShareAtLeast", "spikesPerMinuteAtLeast",
				"gcCollector");
		assertEquals(Map.copyOf(WRONG).keySet(), java.util.Set.copyOf(added));
		for (String key : added) {
			Class<?> type = ConditionAdapterFactory.KNOWN_KEYS.get(key);
			assertTrue(type == Integer.class || type == Long.class || type == Map.class || ConditionAdapterFactory.STRING_LISTS.contains(key),
					key + " is an adapter-vetted type, not " + type);
		}
	}

	@Test
	void aWrongTypedValuePoisonsOnlyItsCondition() {
		EvalFixture f = new EvalFixture();
		WRONG.forEach((key, values) -> {
			for (String value : values) {
				String where = key + " = " + value;
				RulesDocument doc = RulesLoader.parse(document(key, value));
				assertEquals(3, doc.advice.size(), where);
				RulesDocument.AdviceRule fine = doc.advice.get(0);
				RulesDocument.AdviceRule bad = doc.advice.get(1);
				RulesDocument.AdviceRule nested = doc.advice.get(2);
				assertTrue(fine.when.unknownFields.isEmpty(), where);
				assertEquals(Truth.TRUE, ConditionEvaluator.evaluate(fine.when, f.context()), where);
				assertTrue(bad.when.unknownFields.contains(key), where + ": " + bad.when.unknownFields);
				assertEquals(Truth.UNKNOWN, ConditionEvaluator.evaluate(bad.when, f.context()), where);
				assertTrue(nested.when.anyOf.getFirst().unknownFields.contains(key), where);
				assertEquals(Truth.UNKNOWN, ConditionEvaluator.evaluate(nested.when, f.context()), where + ": a poisoned branch poisons the tree");
				assertTrue(doc.stutterAdvice.getFirst().when.unknownFields.contains(key), where);
			}
		});
	}

	@Test
	void existingStringListsFailClosedInsteadOfRejectingTheDocument() {
		RulesDocument doc = RulesLoader.parse(document("gpuVendor", "\"nvidia\""));
		assertTrue(doc.advice.get(1).when.unknownFields.contains("gpuVendor"));
		RulesDocument ok = RulesLoader.parse(document("gpuVendor", "[\"amd\", null]"));
		assertTrue(ok.advice.get(1).when.unknownFields.isEmpty(), "arrays of primitives and nulls read as before");
		assertTrue(!ConditionAdapterFactory.STRING_LISTS.contains("anyOf"));
	}
}
