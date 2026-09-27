package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.profile.BatteryPrompt.Decision;
import io.github.chaotix345.rigtune.core.profile.BatteryPrompt.Offer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	// docs/v0.5/SPEC.md PF-1 (AC2P.1): Battery taken with no active profile remembers "My settings", so plugging back in
	// offers it; with a profile active, that profile.
	@Test
	void pf1WithNoActiveProfileTheBackOfferTargetsTheBaseline() {
		assertEquals("p-mine", BatteryPrompt.previousFor(null, "p-mine"));
		assertEquals("p-evening", BatteryPrompt.previousFor("p-evening", "p-mine"));
		assertNull(BatteryPrompt.previousFor(null, null), "no baseline (profiles.json not writable): nothing to offer back");
		ProfileStore.Battery taken = new ProfileStore.Battery(true, BatteryPrompt.previousFor(null, "p-mine"), NOW.minusSeconds(60).toString(), false);
		assertEquals(new Decision(Offer.PREVIOUS, "p-mine"), BatteryPrompt.onEdge(false, taken, "template:battery", false, NOW));
	}

	// docs/v0.5/SPEC.md PF-3 (AC2P.3): an offer whose target no longer resolves (its profile deleted) or is already active is
	// retired.
	@Test
	void pf3AnOfferWhoseTargetIsGoneIsRetired() {
		Set<String> existing = Set.of("p-mine", "template:battery");
		Decision back = new Decision(Offer.PREVIOUS, "p-mine");
		assertTrue(BatteryPrompt.stillOffered(back, "template:battery", existing::contains));
		assertFalse(BatteryPrompt.stillOffered(back, "template:battery", Set.of("template:battery")::contains), "its target was deleted");
		assertFalse(BatteryPrompt.stillOffered(back, "p-mine", existing::contains), "already active");
		assertFalse(BatteryPrompt.stillOffered(new Decision(Offer.PREVIOUS, null), "template:battery", existing::contains));
		assertTrue(BatteryPrompt.stillOffered(new Decision(Offer.BATTERY, "template:battery"), null, existing::contains));
	}

	// docs/v0.5/SPEC.md PF-2 (AC2P.2): only the unplug offer has "Don't offer again"; the plug-in offer keeps only its ×.
	@Test
	void pf2OnlyTheBatteryOfferCanBeSnoozed() {
		assertTrue(BatteryPrompt.offersSnooze(Offer.BATTERY));
		assertFalse(BatteryPrompt.offersSnooze(Offer.PREVIOUS));
		assertFalse(BatteryPrompt.offersSnooze(Offer.NONE));
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
