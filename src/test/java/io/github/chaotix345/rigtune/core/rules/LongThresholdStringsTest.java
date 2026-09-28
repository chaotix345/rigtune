package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.GpuClass;
import io.github.chaotix345.rigtune.core.model.GpuVendor;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.model.TierResult;
import io.github.chaotix345.rigtune.core.stutter.FixEvidence;
import io.github.chaotix345.rigtune.core.stutter.StutterFacts;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

// Review-11 SEC-5: the stutter thresholds are strings in the client (share maps, causeSpikesAtLeast), and remote rules
// are untrusted, so a threshold longer than MAX_NUMBER_CHARS is UNKNOWN before any number parsing (fail closed), and a
// 2-million-digit one costs nothing.
class LongThresholdStringsTest {
	static final String FIVE_IN_32 = "0".repeat(31) + "5";
	static final String FIVE_IN_33 = "0".repeat(32) + "5";

	static Truth share(String threshold) {
		StutterFacts facts = new StutterFacts(Map.of("gc", 40.0), Map.of(), 0, 0, 0, 40.0, 8192L, null, 3.0, "g1");
		EvalContext ctx = new EvalContext(Fixtures.userRig().build(), new GpuClass(GpuVendor.AMD, false, 5, null), new TierResult(5, 5, 5, 5, 5, "gpu"),
				Goal.BALANCED, Set.of(), Map.of(), new SettingsSnapshot(Map.of())).withStutter(facts);
		return ConditionEvaluator.evaluate(RulesLoader.condition("{\"stutterShareAtLeast\": {\"gc\": \"" + threshold + "\"}}"), ctx);
	}

	static Truth causeSpikes(String threshold) {
		return FixEvidence.causeSpikesAtLeast(Map.of("gc", threshold), Map.of("gc", 9), Set.of());
	}

	@Test
	void aThresholdUpTo32CharactersStillReads() {
		assertEquals(Truth.TRUE, share(FIVE_IN_32));
		assertEquals(Truth.TRUE, causeSpikes(FIVE_IN_32));
		assertEquals(Truth.TRUE, share(" 5 "));
	}

	@Test
	void aLongerThresholdIsUnknown() {
		assertEquals(Truth.UNKNOWN, share(FIVE_IN_33));
		assertEquals(Truth.UNKNOWN, causeSpikes(FIVE_IN_33));
	}

	@Test
	void aTwoMillionDigitThresholdCostsNothing() {
		String huge = "9".repeat(2_000_000);
		assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
			assertEquals(Truth.UNKNOWN, share(huge));
			assertEquals(Truth.UNKNOWN, causeSpikes(huge));
		});
	}
}
