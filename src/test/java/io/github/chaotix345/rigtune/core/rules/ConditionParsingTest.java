package io.github.chaotix345.rigtune.core.rules;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionParsingTest {
	@Test
	void unknownTopLevelKeyIsRecorded() {
		Condition c = RulesLoader.condition("{\"tierAtLeast\":3,\"meshShaders\":true}");
		assertEquals(Set.of("meshShaders"), c.unknownFields);
		assertEquals(3, c.tierAtLeast);
	}

	@Test
	void unknownKeyInsideNotIsRecordedOnTheNestedNode() {
		Condition c = RulesLoader.condition("{\"not\":{\"futureKey\":1}}");
		assertTrue(c.unknownFields.isEmpty());
		assertEquals(Set.of("futureKey"), c.not.unknownFields);
	}

	@Test
	void unknownKeyInsideAnyOfIsRecorded() {
		Condition c = RulesLoader.condition("{\"anyOf\":[{\"always\":true},{\"x\":1}]}");
		assertTrue(c.anyOf.get(0).unknownFields.isEmpty());
		assertEquals(Set.of("x"), c.anyOf.get(1).unknownFields);
	}

	@Test
	void explicitNullValueCountsAsUnknown() {
		Condition c = RulesLoader.condition("{\"gpuModelMatches\":null,\"tierAtMost\":2}");
		assertEquals(Set.of("gpuModelMatches"), c.unknownFields);
	}

	@Test
	void nonIntegralOrOutOfRangeNumbersPoison() {
		assertEquals(Set.of("tierAtLeast"), RulesLoader.condition("{\"tierAtLeast\":3.5}").unknownFields);
		assertEquals(Set.of("tierAtLeast"), RulesLoader.condition("{\"tierAtLeast\":4294967297}").unknownFields);
		assertEquals(Set.of("ramMbAtLeast"), RulesLoader.condition("{\"ramMbAtLeast\":1e30}").unknownFields);
		assertEquals(Set.of("heapMbAtMost"), RulesLoader.condition("{\"heapMbAtMost\":9223372036854775808}").unknownFields);
		assertEquals(Set.of("tierAtMost"), RulesLoader.condition("{\"tierAtMost\":\"3\"}").unknownFields);
		assertEquals(Set.of("always"), RulesLoader.condition("{\"always\":\"yes\"}").unknownFields);
	}

	@Test
	void numbersAtTheBoundsAreFine() {
		Condition c = RulesLoader.condition("{\"tierAtLeast\":2147483647,\"tierAtMost\":3.0,\"ramMbAtLeast\":9223372036854775807,\"always\":true}");
		assertTrue(c.unknownFields.isEmpty());
		assertEquals(Integer.MAX_VALUE, c.tierAtLeast);
		assertEquals(3, c.tierAtMost);
		assertEquals(Long.MAX_VALUE, c.ramMbAtLeast);
	}

	@Test
	void aNullConditionFieldIsPoisonedNotAlways() {
		RulesDocument doc = RulesLoader.parse("""
				{"schemaVersion":2,"revision":1,
				 "mods":[{"slug":"x","modIds":["x"],"recommendWhen":null}],
				 "settings":[{"key":"vanilla.renderDistance","value":8,"when":null}],
				 "advice":[{"id":"a","when":{"anyOf":[null]}}]}""");
		assertTrue(ConditionEvaluator.poisoned(doc.mods.getFirst().recommendWhen));
		assertTrue(ConditionEvaluator.poisoned(doc.settings.getFirst().when));
		assertTrue(ConditionEvaluator.poisoned(doc.advice.getFirst().when));
	}

	@Test
	void v2FieldsParse() {
		Condition c = RulesLoader.condition("""
				{"gpuModelMatches":"(?i)rtx","displayPixelsAtLeast":3686400,"displayPixelsAtMost":8294400,
				 "modVersion":{"sodium":">=0.6.0 <0.8.0"},"mcVersionRange":">=26.3"}""");
		assertTrue(c.unknownFields.isEmpty());
		assertEquals("(?i)rtx", c.gpuModelMatches);
		assertEquals(3686400L, c.displayPixelsAtLeast);
		assertEquals(8294400L, c.displayPixelsAtMost);
		assertEquals(Map.of("sodium", ">=0.6.0 <0.8.0"), c.modVersion);
		assertEquals(">=26.3", c.mcVersionRange);
	}

	@Test
	void ruleConditionsInADocumentAreTracked() {
		RulesDocument doc = RulesLoader.parse("""
				{"schemaVersion":2,"revision":1,
				 "mods":[{"slug":"x","modIds":["x"],"avoidWhen":{"anyOf":[{"not":{"soon":true}}]}}],
				 "settings":[{"key":"vanilla.renderDistance","value":8,"when":{"later":1}}],
				 "advice":[{"id":"a","when":{"tierAtMost":2}}]}""");
		assertEquals(Set.of("soon"), doc.mods.getFirst().avoidWhen.anyOf.getFirst().not.unknownFields);
		assertEquals(Set.of("later"), doc.settings.getFirst().when.unknownFields);
		assertTrue(doc.advice.getFirst().when.unknownFields.isEmpty());
	}

	@Test
	void settingIsReadsStringsNumbersAndBooleans() {
		Condition c = RulesLoader.condition("{\"settingIs\":{\"dh.a.enabled\":true,\"vanilla.renderDistance\":12,\"dh.b.mode\":\"DISABLED\"}}");
		assertTrue(c.unknownFields.isEmpty());
		assertEquals(Map.of("dh.a.enabled", "true", "vanilla.renderDistance", "12", "dh.b.mode", "DISABLED"), c.settingIs);
	}

	@Test
	void settingIsThatIsNotAMapOfPlainValuesPoisons() {
		for (String bad : List.of("\"x\"", "[]", "3", "{\"a\":null}", "{\"a\":{}}", "{\"a\":[1]}")) {
			assertEquals(Set.of("settingIs"), RulesLoader.condition("{\"settingIs\":" + bad + ",\"always\":true}").unknownFields, bad);
		}
	}
}
