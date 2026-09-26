package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.jvm.JvmFacts;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.rules.Truth.FALSE;
import static io.github.chaotix345.rigtune.core.rules.Truth.TRUE;
import static io.github.chaotix345.rigtune.core.rules.Truth.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 6 with amendment J-M1: the jvm- flags evaluate only once the probe ran (jvm-probed); before that, or
// on a JVM without HotSpot's diagnostic bean, every jvm- flag is UNKNOWN, also under not/anyOf, so nothing can fire.
class JvmFlagEvaluationTest {
	private final EvalFixture f = new EvalFixture();

	private void probed(String... facts) {
		Set<String> flags = new HashSet<>(Set.of(facts));
		flags.add(JvmFacts.PROBED);
		f.hw.flags = flags;
	}

	@Test
	void withoutTheProbeEveryJvmFlagIsUnknown() {
		for (String flag : JvmFacts.RULE_FLAGS) {
			String rule = "{\"flags\":[\"" + flag + "\"]}";
			assertEquals(UNKNOWN, f.truth(rule), flag);
			assertEquals(UNKNOWN, f.truth("{\"not\":" + rule + "}"), flag);
			assertEquals(UNKNOWN, f.truth("{\"anyOf\":[" + rule + ",{\"always\":false}]}"), flag);
			assertEquals(UNKNOWN, f.truth("{\"not\":{\"anyOf\":[" + rule + ",{\"tierAtLeast\":6}]}}"), flag);
		}
		// A stray fact without jvm-probed (it can't come from the probe) is still UNKNOWN.
		f.hw.flags = Set.of("jvm-gc-zgc");
		assertEquals(UNKNOWN, f.truth("{\"flags\":[\"jvm-gc-zgc\"]}"));
	}

	@Test
	void afterTheProbePresentIsTrueAndAbsentIsFalse() {
		probed("jvm-gc-zgc", JvmFacts.GC_TYPED);
		assertEquals(TRUE, f.truth("{\"flags\":[\"jvm-gc-zgc\"]}"));
		assertEquals(TRUE, f.truth("{\"flags\":[\"jvm-gc-zgc\",\"jvm-gc-typed\"]}"));
		assertEquals(FALSE, f.truth("{\"flags\":[\"jvm-gc-g1\"]}"));
		assertEquals(TRUE, f.truth("{\"not\":{\"flags\":[\"jvm-server-flags\"]}}"));
		assertEquals(TRUE, f.truth("{\"anyOf\":[{\"flags\":[\"jvm-gc-parallel\"]},{\"flags\":[\"jvm-gc-zgc\"]}]}"));
		assertEquals(FALSE, f.truth("{\"flags\":[\"jvm-gc-zgc\",\"jvm-ignored-flags\"]}"));
		assertEquals(TRUE, f.truth("{\"flags\":[\"jvm-gc-zgc\"],\"heapMbAtLeast\":4096}"));
		assertEquals(FALSE, f.truth("{\"flags\":[\"jvm-gc-zgc\"],\"heapMbAtMost\":3072}"));
	}

	@Test
	void aJvmNameOutsideTheVocabularyIsAlwaysUnknown() {
		probed("jvm-gc-g1");
		assertEquals(UNKNOWN, f.truth("{\"flags\":[\"jvm-heap-reserved\"]}"), "a future fact this client can't compute");
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"flags\":[\"jvm-xms-large\"]}}"), "J-M2: no -Xms fact in 0.4");
		assertEquals(UNKNOWN, f.truth("{\"flags\":[\"jvm-probed\"]}"), "jvm-probed isn't rule vocabulary");
		assertEquals(UNKNOWN, f.truth("{\"not\":{\"flags\":[\"jvm-probed\"]}}"));
		assertEquals(UNKNOWN, f.truth("{\"flags\":[\"jvm-\"]}"));
	}

	@Test
	void jvmFlagsMixWithTheOtherFlags() {
		probed("jvm-gc-g1");
		assertEquals(FALSE, f.truth("{\"flags\":[\"jvm-gc-g1\",\"shaders-enabled\"]}"));
		f.hw.flags = Set.of("shaders-enabled", JvmFacts.PROBED, "jvm-gc-g1");
		assertEquals(TRUE, f.truth("{\"flags\":[\"jvm-gc-g1\",\"shaders-enabled\"]}"));
		// A known-absent flag still decides an AND over an unknown jvm flag, as for the other flags.
		f.hw.flags = Set.of();
		assertEquals(FALSE, f.truth("{\"flags\":[\"shaders-enabled\",\"jvm-gc-g1\"]}"));
	}

	@Test
	void theVocabulary() {
		for (String flag : JvmFacts.RULE_FLAGS) {
			assertTrue(ConditionEvaluator.knownFlag(flag), flag);
			assertTrue(flag.startsWith(JvmFacts.PREFIX), flag);
		}
		assertFalse(ConditionEvaluator.knownFlag(JvmFacts.PROBED));
		assertFalse(ConditionEvaluator.knownFlag("jvm-xms-large"));
		assertFalse(ConditionEvaluator.FLAGS.stream().anyMatch(flag -> flag.startsWith(JvmFacts.PREFIX)),
				"the v1/v2 vocabulary the updater mirrors stays without jvm- flags");
	}
}
