package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.profile.BatteryPrompt.Decision;
import io.github.chaotix345.rigtune.core.profile.BatteryPrompt.Offer;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.4/SPEC.md AC4.9 (the prompt; PowerWatcherTest covers the watcher).
class BatteryPromptTest {
	private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
	private static final ProfileStore.Battery FRESH = new ProfileStore.Battery(true, null, null, false);

	@Test
	void anAcToBatteryEdgeOffersBattery() {
		assertEquals(new Decision(Offer.BATTERY, "template:battery"), BatteryPrompt.onEdge(true, FRESH, null, false, NOW));
		assertEquals(new Decision(Offer.BATTERY, "template:battery"), BatteryPrompt.onEdge(true, FRESH, "p-1", false, NOW));
	}

	@Test
	void theCooldownSnoozeBenchmarkAndActiveBatteryStopTheOffer() {
		ProfileStore.Battery recent = new ProfileStore.Battery(true, null, NOW.minusSeconds(9 * 60).toString(), false);
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(true, recent, null, false, NOW).offer());
		ProfileStore.Battery old = new ProfileStore.Battery(true, null, NOW.minusSeconds(11 * 60).toString(), false);
		assertEquals(Offer.BATTERY, BatteryPrompt.onEdge(true, old, null, false, NOW).offer());
		ProfileStore.Battery future = new ProfileStore.Battery(true, null, NOW.plusSeconds(3600).toString(), false);
		assertEquals(Offer.BATTERY, BatteryPrompt.onEdge(true, future, null, false, NOW).offer(), "a clock set back doesn't block offers forever");
		ProfileStore.Battery garbage = new ProfileStore.Battery(true, null, "yesterday", false);
		assertEquals(Offer.BATTERY, BatteryPrompt.onEdge(true, garbage, null, false, NOW).offer());
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(true, new ProfileStore.Battery(true, null, null, true), null, false, NOW).offer());
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(true, new ProfileStore.Battery(false, null, null, false), null, false, NOW).offer());
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(true, FRESH, null, true, NOW).offer());
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(true, FRESH, "template:battery", false, NOW).offer());
	}

	@Test
	void aBatteryToAcEdgeOffersThePreviousProfile() {
		ProfileStore.Battery switched = new ProfileStore.Battery(true, "p-evening", NOW.minusSeconds(60).toString(), false);
		assertEquals(new Decision(Offer.PREVIOUS, "p-evening"), BatteryPrompt.onEdge(false, switched, "template:battery", false, NOW));
		assertEquals(new Decision(Offer.PREVIOUS, "template:quality"),
				BatteryPrompt.onEdge(false, new ProfileStore.Battery(true, "template:quality", null, false), "template:battery", false, NOW));
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(false, switched, "p-other", false, NOW).offer(), "Battery no longer active");
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(false, FRESH, "template:battery", false, NOW).offer(), "nothing to go back to");
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(false, switched, "template:battery", true, NOW).offer());
		assertEquals(Offer.NONE, BatteryPrompt.onEdge(false, new ProfileStore.Battery(true, "p-evening", null, true), "template:battery", false, NOW).offer());
	}

	@Test
	void aChangeCountsAfterTwoPollsInARow() {
		BatteryPrompt.Debouncer debouncer = new BatteryPrompt.Debouncer(false);
		assertNull(debouncer.poll(false));
		assertNull(debouncer.poll(true));
		assertNull(debouncer.poll(false), "a wiggled plug");
		assertNull(debouncer.poll(true));
		assertEquals(Boolean.TRUE, debouncer.poll(true));
		assertNull(debouncer.poll(true));
		assertNull(debouncer.poll(false));
		assertEquals(Boolean.FALSE, debouncer.poll(false));
		BatteryPrompt.Debouncer unknown = new BatteryPrompt.Debouncer(null);
		assertNull(unknown.poll(true), "the first poll is the baseline, not an edge");
		assertEquals(Boolean.TRUE, unknown.confirmed());
	}
}
