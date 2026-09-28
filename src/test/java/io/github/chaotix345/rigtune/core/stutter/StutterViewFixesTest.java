package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md 5: StutterView carries the offers and the tracked fix; 0.4's constructor has neither.
class StutterViewFixesTest {
	@Test
	void theOldConstructorHasNoFixesAndNoBlock() {
		StutterView v = new StutterView(true, true, false, false, true, null, List.of());
		assertEquals(Map.of(), v.fixes());
		assertNull(v.tracked());
		assertEquals(Map.of(), StutterView.EMPTY.fixes());
	}

	@Test
	void offersKeepTheirOrder() {
		FixOffer a = new FixOffer.NotYet("stutter-sodium-defer", FixOffer.Reason.BUSY, List.of());
		FixOffer b = new FixOffer.Offer("stutter-chunk-loading", "vanilla.renderDistance", "12", "10", true);
		java.util.LinkedHashMap<String, FixOffer> fixes = new java.util.LinkedHashMap<>();
		fixes.put(a.adviceId(), a);
		fixes.put(b.adviceId(), b);
		StutterView v = new StutterView(true, true, false, false, true, null, List.of(), fixes, FixTrackerTest.measuring());
		assertEquals(List.of("stutter-sodium-defer", "stutter-chunk-loading"), List.copyOf(v.fixes().keySet()));
		assertEquals("entry-1", v.tracked().entryId());
	}
}
