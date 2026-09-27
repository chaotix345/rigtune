package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.Recommender;
import io.github.chaotix345.rigtune.core.stutter.FixSpec;
import io.github.chaotix345.rigtune.core.stutter.StutterAdvisor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 5 (C20's rules side, AC5.1, AC5.15): the bundled release revision's stutterFixes seeds, as the client
// reads them. The updater validates the section (tools/tests/test_rules_v05.py); this checks what reaches RulesLoader.
class StutterFixSeedsTest {
	static final RulesDocument RULES = RulesLoader.loadBundled();

	@Test
	void theThreeSeedsInOrderWithTheDhFixLast() {
		assertEquals(List.of("stutter-sodium-defer", "stutter-chunk-loading", "stutter-dh-threads"), RULES.stutterFixes.stream().map(f -> f.adviceId).toList());
		assertEquals(List.of("sodium.performance.chunk_build_defer_mode", "vanilla.renderDistance", "dh.common.multiThreading.numberOfThreads"),
				RULES.stutterFixes.stream().map(f -> f.set.key).toList());
	}

	@Test
	void eachSeedNamesAnAdviceAnAllowedKeyAndOnlyTheFixFeature() {
		Set<String> advice = RULES.stutterAdvice.stream().map(a -> a.id).collect(Collectors.toSet());
		for (RulesDocument.StutterFix fix : RULES.stutterFixes) {
			assertTrue(advice.contains(fix.adviceId), fix.adviceId);
			assertTrue(FixSpec.KEYS.contains(fix.set.key), fix.set.key);
			assertEquals(List.of(FixSpec.FEATURE), fix.requires, fix.adviceId);
			assertTrue(fix.evidence.unknownFields == null || fix.evidence.unknownFields.isEmpty(), fix.adviceId + ": " + fix.evidence.unknownFields);
			assertTrue(ConditionEvaluator.hasStutterKey(fix.evidence), fix.adviceId);
			assertTrue(fix.set.value != null ^ fix.set.step != null, fix.adviceId);
		}
		assertEquals("ALWAYS", RULES.stutterFixes.getFirst().set.value.getAsString());
		assertEquals(-2, RULES.stutterFixes.get(1).set.step.getAsInt());
		assertEquals(6, RULES.stutterFixes.get(1).set.min.getAsInt());
		assertEquals(-2, RULES.stutterFixes.get(2).set.step.getAsInt());
		assertEquals(1, RULES.stutterFixes.get(2).set.min.getAsInt());
	}

	// Neither the main list nor the Stutter Doctor's advice knows the fix feature, and an evidence condition never fires
	// without a session's facts.
	@Test
	void onlyTheFixOffersKnowTheFeature() {
		assertFalse(Recommender.SUPPORTED_FEATURES.contains(FixSpec.FEATURE));
		assertFalse(StutterAdvisor.SUPPORTED_FEATURES.contains(FixSpec.FEATURE));
		EvalContext mainList = Recommender.context(RULES, Fixtures.userRig().build(), Fixtures.mods("sodium", "distanthorizons"),
				new SettingsSnapshot(Map.of()), Goal.BALANCED);
		for (RulesDocument.StutterFix fix : RULES.stutterFixes) {
			assertEquals(Truth.UNKNOWN, ConditionEvaluator.evaluate(fix.evidence, mainList), fix.adviceId);
		}
	}
}
