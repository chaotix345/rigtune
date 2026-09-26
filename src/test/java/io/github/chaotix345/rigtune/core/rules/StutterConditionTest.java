package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.stutter.StutterFacts;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.chaotix345.rigtune.core.rules.Truth.FALSE;
import static io.github.chaotix345.rigtune.core.rules.Truth.TRUE;
import static io.github.chaotix345.rigtune.core.rules.Truth.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.4/SPEC.md 5 (AC5.5's condition side): the stutter keys against StutterFacts; K-M1 value types.
class StutterConditionTest {
	// GC claimed 44 % of the lost time, chunk loading 12 %; 7 of 20 spikes during saves; ...
	static final StutterFacts FACTS = new StutterFacts(Map.of("gc", 44.0, "chunkLoad", 12.0, "unknown", 31.0), Map.of("worldSave", 35.0, "dh", 40.0), 2, 0, 3,
			81.0, 2048L, 29.9, 2.5, "g1");

	static Truth eval(String json, StutterFacts facts) {
		return ConditionEvaluator.evaluate(RulesLoader.condition(json), new EvalFixture().context().withStutter(facts));
	}

	static Truth eval(String json) {
		return eval(json, FACTS);
	}

	@Test
	void shares() {
		assertEquals(TRUE, eval("{\"stutterShareAtLeast\": {\"gc\": 30}}"));
		assertEquals(TRUE, eval("{\"stutterShareAtLeast\": {\"gc\": 44}}"), "at least");
		assertEquals(FALSE, eval("{\"stutterShareAtLeast\": {\"gc\": 45}}"));
		assertEquals(FALSE, eval("{\"stutterShareAtLeast\": {\"chunkBuild\": 1}}"), "a known cause that claimed nothing is 0");
		assertEquals(TRUE, eval("{\"stutterShareAtLeast\": {\"gc\": 30, \"chunkLoad\": \"10\"}}"), "numbers or strings");
		assertEquals(FALSE, eval("{\"stutterShareAtLeast\": {\"gc\": 30, \"chunkLoad\": 20}}"), "every entry must hold");
		assertEquals(TRUE, eval("{\"stutterShareAtLeast\": {\"gc\": 30.0}}"), "a whole number written with .0");
		assertEquals(TRUE, eval("{\"stutterTaggedShareAtLeast\": {\"dh\": 40}}"));
		assertEquals(FALSE, eval("{\"stutterTaggedShareAtLeast\": {\"afterTeleport\": 1}}"));
		assertEquals(TRUE, eval("{\"stutterShareAtLeast\": {}}"), "no entries: nothing to check");
	}

	@Test
	void badShareValuesAndUnknownNamesAreUnknown() {
		assertEquals(UNKNOWN, eval("{\"stutterShareAtLeast\": {\"gc\": \"x\"}}"));
		assertEquals(UNKNOWN, eval("{\"stutterShareAtLeast\": {\"gc\": 12.5}}"), "whole percent only");
		assertEquals(UNKNOWN, eval("{\"stutterShareAtLeast\": {\"gc\": -3}}"));
		assertEquals(UNKNOWN, eval("{\"stutterShareAtLeast\": {\"gc\": 99999999999}}"));
		assertEquals(UNKNOWN, eval("{\"stutterShareAtLeast\": {\"shaderCompile\": 10}}"), "a cause a newer version might know");
		assertEquals(UNKNOWN, eval("{\"stutterTaggedShareAtLeast\": {\"gc\": 10}}"), "gc is a cause, not a tag");
		assertEquals(FALSE, eval("{\"stutterShareAtLeast\": {\"shaderCompile\": 10, \"gc\": 90}}"), "Kleene AND with a FALSE entry");
	}

	@Test
	void counts() {
		assertEquals(TRUE, eval("{\"gcFullPausesAtLeast\": 2}"));
		assertEquals(FALSE, eval("{\"gcFullPausesAtLeast\": 3}"));
		assertEquals(FALSE, eval("{\"gcStallsAtLeast\": 1}"));
		assertEquals(TRUE, eval("{\"gcStallsAtLeast\": 0}"));
		assertEquals(TRUE, eval("{\"gcExplicitPausesAtLeast\": 2}"));
		assertEquals(FALSE, eval("{\"gcExplicitPausesAtLeast\": 4}"));
	}

	@Test
	void measuredValues() {
		assertEquals(TRUE, eval("{\"liveSetPercentAtLeast\": 75}"));
		assertEquals(FALSE, eval("{\"liveSetPercentAtLeast\": 82}"));
		assertEquals(TRUE, eval("{\"heapRaiseRoomMbAtLeast\": 2048}"));
		assertEquals(FALSE, eval("{\"heapRaiseRoomMbAtLeast\": 2049}"));
		assertEquals(FALSE, eval("{\"cpuContentionShareAtLeast\": 30}"), "29.9 %");
		assertEquals(TRUE, eval("{\"cpuContentionShareAtLeast\": 29}"));
		assertEquals(TRUE, eval("{\"spikesPerMinuteAtLeast\": 25}"), "x10: 2.5 a minute");
		assertEquals(FALSE, eval("{\"spikesPerMinuteAtLeast\": 26}"));
	}

	@Test
	void unknownFactsAreUnknown() {
		StutterFacts unknown = new StutterFacts(Map.of(), Map.of(), 0, 0, 0, null, null, null, 0, null);
		assertEquals(UNKNOWN, eval("{\"liveSetPercentAtLeast\": 75}", unknown));
		assertEquals(UNKNOWN, eval("{\"heapRaiseRoomMbAtLeast\": 1}", unknown));
		assertEquals(UNKNOWN, eval("{\"cpuContentionShareAtLeast\": 1}", unknown));
		assertEquals(UNKNOWN, eval("{\"gcCollector\": [\"g1\"]}", unknown));
		assertEquals(UNKNOWN, eval("{\"not\": {\"liveSetPercentAtLeast\": 75}}", unknown), "not over UNKNOWN stays UNKNOWN");
	}

	// Review finding 2: a count the capture couldn't measure (no GC listener) and a cause it couldn't measure (no phase
	// timers, an uncalibrated GC clock) are UNKNOWN, so `not` can't turn "never measured" into TRUE.
	@Test
	void unmeasuredIsUnknownEvenUnderNot() {
		StutterFacts blind = new StutterFacts(Map.of("unknown", 100.0), Map.of(), 0, 0, 0, null, 4096L, null, 1.0, "g1", false,
				java.util.Set.of("gc", "chunkLoad", "chunkBuild", "tick", "render", "dh", "cpuContention"));
		for (String json : new String[]{"{\"gcFullPausesAtLeast\": 1}", "{\"not\": {\"gcFullPausesAtLeast\": 1}}", "{\"not\": {\"gcStallsAtLeast\": 1}}",
				"{\"gcExplicitPausesAtLeast\": 0}", "{\"not\": {\"stutterShareAtLeast\": {\"chunkLoad\": 30}}}", "{\"not\": {\"stutterShareAtLeast\": {\"gc\": 1}}}",
				"{\"not\": {\"stutterTaggedShareAtLeast\": {\"dh\": 40}}}", "{\"stutterShareAtLeast\": {\"render\": 0}}"}) {
			assertEquals(UNKNOWN, eval(json, blind), json);
		}
		assertEquals(TRUE, eval("{\"stutterShareAtLeast\": {\"unknown\": 90}}", blind), "the unexplained share is always known");
		assertEquals(TRUE, eval("{\"not\": {\"stutterTaggedShareAtLeast\": {\"worldSave\": 1}}}", blind), "save windows need no sampler");
		assertEquals(FALSE, eval("{\"not\": {\"gcFullPausesAtLeast\": 1}}", new StutterFacts(Map.of(), Map.of(), 2, 0, 0, null, null, null, 0, "g1")),
				"measured counts decide");
	}

	@Test
	void collector() {
		assertEquals(TRUE, eval("{\"gcCollector\": [\"g1\"]}"));
		assertEquals(TRUE, eval("{\"gcCollector\": [\"zgc\", \"G1\"]}"), "case-insensitive, any entry");
		assertEquals(FALSE, eval("{\"gcCollector\": [\"zgc\"]}"));
		assertEquals(UNKNOWN, eval("{\"gcCollector\": [\"zgc\", \"epsilon\"]}"), "a name outside the vocabulary");
		assertEquals(TRUE, eval("{\"gcCollector\": [\"epsilon\", \"g1\"]}"), "a known match wins");
	}

	// Without facts (the main list) every stutter key is UNKNOWN, whatever it's combined with.
	@Test
	void withoutFactsEveryKeyIsUnknown() {
		EvalFixture f = new EvalFixture();
		for (String json : new String[]{"{\"stutterShareAtLeast\": {\"gc\": 0}}", "{\"gcFullPausesAtLeast\": 0}", "{\"spikesPerMinuteAtLeast\": 0}",
				"{\"not\": {\"gcStallsAtLeast\": 1}}", "{\"anyOf\": [{\"gcStallsAtLeast\": 1}, {\"tierAtLeast\": 99}]}", "{\"gcCollector\": [\"g1\"]}"}) {
			assertEquals(UNKNOWN, f.truth(json), json);
		}
		assertEquals(FALSE, f.truth("{\"gcFullPausesAtLeast\": 0, \"os\": [\"macos\"]}"), "a FALSE key still decides");
	}

	@Test
	void combinedWithOtherKeys() {
		assertEquals(TRUE, eval("{\"stutterShareAtLeast\": {\"gc\": 30}, \"anyOf\": [{\"gcFullPausesAtLeast\": 1}, {\"gcStallsAtLeast\": 1}], "
				+ "\"heapRaiseRoomMbAtLeast\": 2048, \"modPresent\": [\"sodium\"]}"));
		assertEquals(FALSE, eval("{\"stutterShareAtLeast\": {\"gc\": 30}, \"modPresent\": [\"iris\"]}"));
	}
}
