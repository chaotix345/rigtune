package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.stutter.StutterFacts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md "Shared contracts" C2, the Java side: the new rules-v2 sections and condition keys parse, and every new
// key is UNKNOWN until its workstream fills the evaluator (so nothing can fire yet).
class RulesContractsTest {
	private static final String SECTIONS = """
			{"schemaVersion": 2, "revision": 99,
			 "profileTemplates": {"templates": [
			   {"id": "max_fps", "goal": "performance", "settings": [{"key": "vanilla.maxFps", "value": 260}, {"key": "vanilla.enableVsync", "value": false}]},
			   {"id": "balanced", "goal": "balanced"},
			   {"id": "battery", "goal": "performance", "facts": {"onBattery": true, "hasBattery": true}, "requires": ["x"],
			    "settings": [{"key": "iris.enableShaders", "value": false, "when": {"modPresent": ["iris"]}}, {"key": "vanilla.renderDistance", "max": 8}]},
			   null]},
			 "stutterAdvice": [
			   {"id": "stutter-gc-explicit", "requires": ["stutter-doctor"], "kind": "info", "impact": "medium",
			    "when": {"gcExplicitPausesAtLeast": 2, "stutterShareAtLeast": {"gc": 10}}, "title": "t", "text": "x"},
			   {"kind": "info"}]}
			""";

	@Test
	void theNewSectionsParseAsTypedObjects() {
		RulesDocument doc = RulesLoader.parse(SECTIONS);
		List<RulesDocument.ProfileTemplate> templates = doc.profileTemplates.templates;
		assertEquals(List.of("max_fps", "balanced", "battery"), templates.stream().map(t -> t.id).toList(), "null entries dropped");
		RulesDocument.ProfileTemplate battery = templates.get(2);
		assertEquals("performance", battery.goal);
		assertEquals(Map.of("onBattery", true, "hasBattery", true), battery.facts);
		assertEquals(List.of("x"), battery.requires);
		assertEquals(8.0, battery.settings.get(1).max);
		assertTrue(battery.settings.get(1).isClampEntry());
		assertTrue(battery.settings.getFirst().isValueEntry());
		assertNull(templates.get(1).settings);

		assertEquals(1, doc.stutterAdvice.size(), "entries without an id dropped");
		RulesDocument.AdviceRule advice = doc.stutterAdvice.getFirst();
		assertEquals(List.of("stutter-doctor"), advice.requires);
		assertTrue(advice.when.unknownFields.isEmpty(), "the stutter keys are known: " + advice.when.unknownFields);
		assertEquals(2, advice.when.gcExplicitPausesAtLeast);
		assertEquals(Map.of("gc", "10"), advice.when.stutterShareAtLeast);
	}

	@Test
	void bothSectionsAreNullWhenAbsent() {
		RulesDocument bundled = RulesLoader.loadBundled();
		assertNull(bundled.profileTemplates);
		assertNull(bundled.stutterAdvice);
		RulesDocument v1 = RulesLoader.parse("{\"schemaVersion\": 1, \"revision\": 1}");
		assertNull(v1.profileTemplates);
		assertNull(v1.stutterAdvice);
	}

	// Review finding 7 (K-M1's reasoning): an unreadable new section is dropped on its own; the document still loads.
	@Test
	void anUnreadableNewSectionIsNullAndTheDocumentStillLoads() {
		for (String sections : List.of(
				"\"profileTemplates\": [1, 2], \"stutterAdvice\": {\"id\": \"x\"}",
				"\"profileTemplates\": {\"templates\": [{\"id\": \"battery\", \"facts\": {\"onBattery\": {\"x\": 1}}}]}, \"stutterAdvice\": [[]]",
				"\"profileTemplates\": {\"templates\": \"all\"}, \"stutterAdvice\": \"none\"",
				"\"profileTemplates\": {\"templates\": [{\"id\": \"q\", \"settings\": [{\"key\": \"vanilla.maxFps\", \"max\": \"x\"}]}]}, \"stutterAdvice\": [{\"id\": \"s\", \"requires\": {}}]")) {
			RulesDocument doc = RulesLoader.parse("{\"schemaVersion\": 2, \"revision\": 3, \"advice\": [{\"id\": \"a\", \"title\": \"t\", \"text\": \"x\"}], "
					+ sections + "}");
			assertNull(doc.profileTemplates, sections);
			assertNull(doc.stutterAdvice, sections);
			assertEquals(1, doc.advice.size(), sections);
			assertEquals(3, doc.revision);
		}
		RulesDocument badCondition = RulesLoader.parse("{\"schemaVersion\": 2, \"revision\": 1, \"stutterAdvice\": [{\"id\": \"s\", "
				+ "\"when\": {\"gcStallsAtLeast\": \"x\"}, \"title\": \"t\", \"text\": \"x\"}]}");
		assertEquals(1, badCondition.stutterAdvice.size(), "a bad condition value poisons only its condition");
		assertTrue(badCondition.stutterAdvice.getFirst().when.unknownFields.contains("gcStallsAtLeast"));
	}

	@Test
	void driverVersionParsesAndIsUnknownForNow() {
		Condition c = RulesLoader.condition("{\"driverVersion\": {\"vendor\": \"nvidia\", \"atLeast\": \"526.47\", \"atMost\": \"536.22\"}}");
		assertTrue(c.unknownFields.isEmpty());
		assertEquals(Map.of("vendor", "nvidia", "atLeast", "526.47", "atMost", "536.22"), c.driverVersion);
		EvalFixture f = new EvalFixture();
		assertEquals(Truth.UNKNOWN, f.truth("{\"driverVersion\": {\"vendor\": \"amd\", \"atLeast\": \"1\"}}"));
		assertEquals(Truth.UNKNOWN, f.truth("{\"not\": {\"driverVersion\": {\"vendor\": \"amd\"}}}"));
		assertEquals(Truth.FALSE, f.truth("{\"driverVersion\": {\"vendor\": \"amd\"}, \"os\": [\"macos\"]}"), "Kleene AND with a FALSE key");
		assertEquals(Truth.UNKNOWN, f.truth("{\"driverVersion\": \"531.18\"}"), "not a map: poisoned");
	}

	// WS-S filled the evaluator (StutterConditionTest has the cases): UNKNOWN without facts, decided with them.
	@Test
	void everyStutterKeyParsesAndIsUnknownWithoutFacts() {
		List<String> keys = List.of("{\"stutterShareAtLeast\": {\"gc\": 30}}", "{\"stutterTaggedShareAtLeast\": {\"dh\": 40}}",
				"{\"gcFullPausesAtLeast\": 1}", "{\"gcStallsAtLeast\": 1}", "{\"gcExplicitPausesAtLeast\": 2}", "{\"liveSetPercentAtLeast\": 75}",
				"{\"heapRaiseRoomMbAtLeast\": 2048}", "{\"cpuContentionShareAtLeast\": 30}", "{\"spikesPerMinuteAtLeast\": 3}",
				"{\"gcCollector\": [\"g1\"]}");
		StutterFacts facts = new StutterFacts(Map.of("gc", 50.0), Map.of("dh", 60.0), 2, 1, 3, 80.0, 4096L, 40.0, 5.0, "g1");
		EvalFixture f = new EvalFixture();
		for (String json : keys) {
			Condition c = RulesLoader.condition(json);
			assertTrue(c.unknownFields.isEmpty(), json);
			assertTrue(ConditionEvaluator.hasStutterKey(c), json);
			assertEquals(Truth.UNKNOWN, ConditionEvaluator.evaluate(c, f.context()), json + " without facts");
			assertEquals(Truth.TRUE, ConditionEvaluator.evaluate(c, f.context().withStutter(facts)), json + " with facts");
		}
		assertFalse(ConditionEvaluator.hasStutterKey(RulesLoader.condition("{\"tierAtLeast\": 3}")));
		assertTrue(RulesLoader.condition("{\"spikesPerMinuteAtLeast\": 0.5}").unknownFields.contains("spikesPerMinuteAtLeast"),
				"a fraction in an integer key fails closed");
	}

	@Test
	void existingConditionsAreUnchanged() {
		EvalFixture f = new EvalFixture();
		assertEquals(Truth.TRUE, f.truth("{\"modPresent\": [\"sodium\"]}"));
		assertEquals(Truth.FALSE, f.truth("{\"modPresent\": [\"iris\"]}"));
	}

	@Test
	void evalContextCarriesOptionalStutterFacts() {
		EvalContext plain = new EvalFixture().context();
		assertNull(plain.stutter());
		StutterFacts facts = new StutterFacts(null, null, 0, 0, 0, null, null, null, 0, null);
		assertEquals(Map.of(), facts.claimedShares());
		EvalContext with = plain.withStutter(facts);
		assertSame(facts, with.stutter());
		assertEquals(plain.hardware(), with.hardware());
		assertEquals(plain.settings(), with.settings());
	}

	@Test
	void theMainListStillKnowsNoFeatures() {
		assertEquals(Set.of(), Recommender.SUPPORTED_FEATURES, "WS-R changes it together with the jvm-* content");
	}
}
