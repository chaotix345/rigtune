package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.history.HistoryModel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.3 (the player-visible blocks' one-line reasons) and AC5.7 (every verdict line shows both rates): the English as
// en_us.json has it (LangCheckTest ties the two).
class FixTextTest {
	private static final HistoryModel.Labels LABELS = new HistoryModel.Labels() {
		@Override
		public String label(String key) {
			return key.equals("vanilla.renderDistance") ? "Render Distance" : key;
		}
	};

	private static String notYet(FixOffer.Reason reason, String... args) {
		return FixText.notYet(new FixOffer.NotYet("stutter-chunk-loading", reason, List.of(args))).english();
	}

	@Test
	void eachReasonHasItsOneLine() {
		assertEquals("A one-click fix needs more play to compare: at least 5:00 and 8 hitches after a session's first 3 minutes (this one: 4:12, 5).",
				notYet(FixOffer.Reason.LENGTH, "5:00", "8", "4:12", "5"));
		assertEquals("The measurements don't point at this clearly enough for a one-click fix; the advice above still applies.",
				notYet(FixOffer.Reason.EVIDENCE));
		assertEquals("One-click fixes are offered for your own play sessions, not for benchmark runs.", notYet(FixOffer.Reason.BENCHMARK));
		assertEquals("This server sends at most 8 chunks, so a shorter render distance would change nothing here.", notYet(FixOffer.Reason.SERVER, "8"));
		assertEquals("Another fix is still being measured. Wait for its comparison or dismiss it first.", notYet(FixOffer.Reason.BUSY));
		assertTrue(notYet(FixOffer.Reason.STORE).contains("stutter-fixes.json"));
		assertTrue(notYet(FixOffer.Reason.EXCLUDED).contains("can't be compared"));
		assertEquals("This session was idle (throttled) longer than it was played, so it can't be compared. Play a session without long breaks.",
				notYet(FixOffer.Reason.IDLE));
		assertEquals("The Stutter Doctor isn't ready for this yet. Try again in a moment.", FixText.later().english());
		for (FixOffer.Reason reason : FixOffer.Reason.values()) {
			assertTrue(!notYet(reason).isBlank(), reason.name());
		}
	}

	@Test
	void theOfferRows() {
		String change = FixText.change(LABELS, "vanilla.renderDistance", "12", "10").english();
		assertEquals("Render Distance: 12 → 10", change);
		assertEquals("Try it in one click: Render Distance: 12 → 10", FixText.offer(FixText.change(LABELS, "vanilla.renderDistance", "12", "10")).english());
		assertEquals("Try this fix: Render Distance: 12 → 10. RigTune measures one more session as it is first.",
				FixText.tryNarration(FixText.change(LABELS, "vanilla.renderDistance", "12", "10")).english());
		assertEquals("Apply the fix: Render Distance: 12 → 10. Opens a preview first.",
				FixText.applyNarration(FixText.change(LABELS, "vanilla.renderDistance", "12", "10")).english());
		assertTrue(FixText.takesEffect(true).english().contains("It takes effect at once"));
		assertTrue(FixText.takesEffect(false).english().contains("It takes effect after you restart Minecraft"));
		assertEquals("Fix applied. Keep the Stutter Doctor on and play at least 6:40 after a session's first 3 minutes; RigTune then compares that play"
				+ " with your play before.", FixText.applied(true, 400).english());
		assertEquals("Fix staged: it takes effect after you restart Minecraft. Then play at least 5:00 with the Stutter Doctor on (a session's first 3"
				+ " minutes don't count).", FixText.applied(false, 300).english());
	}

	// AC5.7: both rates in every verdict line; "more" names the Undo.
	@Test
	void everyVerdictShowsBothRates() {
		for (FixComparison.Kind kind : FixComparison.Kind.values()) {
			String line = FixText.verdict(new FixComparison.Verdict(kind, 4.84, 1.26, 300, 80, 1, 0.01, 0.99)).english();
			assertTrue(line.contains("1.3 hitches a minute") && line.contains("(was 4.8)"), line);
		}
		assertTrue(FixText.verdict(new FixComparison.Verdict(FixComparison.Kind.LESS, 4, 1, 300, 80, 1, 0.01, 0.99)).english().endsWith(
				"measured comparison, not proof."));
		assertTrue(FixText.verdict(new FixComparison.Verdict(FixComparison.Kind.MORE, 1, 4, 80, 300, 1, 0.99, 0.01)).english().contains("undo the change"));
		assertEquals("4.8 hitches a minute", FixText.rate(4.84).english());
		assertEquals("Time lost to stutter: 1,310 ms a minute before, 80 ms after.",
				FixText.lost(new FixComparison.Verdict(FixComparison.Kind.LESS, 4.84, 1.2, 1310.4, 80.2, 1, 0.01, 0.99)).english());
	}

	// review-11 STUTTER-5 (X3): "no clear change" says why. Fewer hitches by "less"'s own measure but more time lost, or a
	// difference the test finds but too small to call, isn't "within how much play sessions vary"; only a difference
	// neither p-value finds is.
	@Test
	void noClearChangeSaysWhy() {
		FixComparison.Verdict moreLost = FixComparison.compare(FixComparisonTest.side(20, 600, 1000, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
				FixComparisonTest.side(4, 600, 3000, 1, 0, 1, 0, 1, 0, 1, 0, 0, 0));
		assertEquals(FixComparison.Kind.SAME, moreLost.kind());
		String line = FixText.verdict(moreLost).english();
		assertFalse(line.contains("within how much play sessions vary"), line);
		assertEquals("No clear improvement: 0.4 hitches a minute (was 2.0), but more time lost to stutter (300 ms a minute, was 100). Keep the change or"
				+ " undo it.", line);

		FixComparison.Verdict small = FixComparison.compare(new SessionOutcome(1, 3600, 3000, 90_000, 60, 50, 50),
				new SessionOutcome(1, 3600, 2400, 72_000, 60, 40, 40));
		assertEquals(FixComparison.Kind.SAME, small.kind());
		assertTrue(small.pLess() <= FixComparison.ALPHA, "a clear difference: " + small);
		line = FixText.verdict(small).english();
		assertFalse(line.contains("within how much play sessions vary"), line);
		assertEquals("A small change: 40.0 hitches a minute (was 50.0). Measurable, but too small to call better or worse. Keep the change or undo it.",
				line);

		FixComparison.Verdict noise = FixComparison.compare(FixComparisonTest.side(20, 600, 1000, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2),
				FixComparisonTest.side(17, 600, 850, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1));
		assertEquals(FixComparison.Kind.SAME, noise.kind());
		assertTrue(FixText.verdict(noise).english().contains("within how much play sessions vary"));
	}

	@Test
	void theBlocksLines() {
		FixTracker.Record m = FixTrackerTest.measuring();
		assertEquals("Render Distance: 12 → 10, applied 2026-09-02", FixText.applied(LABELS, m, ZoneOffset.UTC).english());
		assertEquals("Measuring: 0:00 of 6:40 played with the Stutter Doctor on (a session's first 3 minutes don't count).",
				FixText.state(LABELS, m, true).english());
		assertEquals("Turn the Stutter Doctor on and play to compare.", FixText.state(LABELS, m, false).english());
		assertEquals("Waiting for a restart: the change takes effect when Minecraft starts again.",
				FixText.state(LABELS, FixTrackerTest.staged(), true).english());
		assertEquals("You undid this change.", FixText.state(LABELS, m.withState(FixTracker.State.UNDONE), true).english());
		assertEquals("Render Distance was changed again since, so this comparison stopped.",
				FixText.state(LABELS, m.withState(FixTracker.State.REPLACED), true).english());
		assertTrue(FixText.state(LABELS, m.withState(FixTracker.State.NOT_APPLIED), true).english().startsWith("The change wasn't applied"));
		assertTrue(FixText.state(LABELS, m.withState(FixTracker.State.EXPIRED), true).english().startsWith("No comparable play in time"));
		assertNull(FixText.skipped(LABELS, m));
	}

	@Test
	void whyASessionDidntCount() {
		FixTracker.Record m = FixTrackerTest.measuring();
		assertEquals("Your last session didn't count: it had less than 2 minutes of play after its first 3 minutes.",
				skipped(m, new FixTracker.Skip("short", List.of())));
		assertEquals("Your last session didn't count: Render Distance changed (10 → 12).",
				skipped(m, new FixTracker.Skip("setting", List.of("vanilla.renderDistance", "10", "12"))));
		assertEquals("Your last session didn't count: the window size or fullscreen changed.", skipped(m, new FixTracker.Skip("display", List.of())));
		assertTrue(skipped(m, new FixTracker.Skip("excluded", List.of())).contains("benchmark"));
		assertEquals("Your last session didn't count: it was idle (throttled) longer than it was played.", skipped(m, new FixTracker.Skip("idle", List.of())));
		assertEquals("Your last session didn't count: RigTune couldn't read Render Distance at its start or end.",
				skipped(m, new FixTracker.Skip("unread", List.of("vanilla.renderDistance"))));
		for (FixConditions.Reason reason : FixConditions.Reason.values()) {
			assertTrue(!skipped(m, new FixTracker.Skip(reason.id(), List.of("k", "a", "b"))).contains("null"), reason.name());
		}
		assertTrue(skipped(m, new FixTracker.Skip("weather", List.of())).contains("other conditions"));
	}

	// review-11 STUTTER-1: a date outside the zone's range (Instant.MIN, Instant.MAX) never throws; it reads "?".
	@Test
	void aDateOutsideTheZonesRangeReadsAsUnknown() {
		for (ZoneId zone : List.of(ZoneOffset.UTC, ZoneId.of("Australia/Sydney"), ZoneId.of("America/Los_Angeles"))) {
			assertEquals("?", FixText.day(Instant.MIN, zone));
			assertEquals("?", FixText.day(Instant.MAX, zone));
			assertEquals("2026-09-28", FixText.day(Instant.parse("2026-09-28T02:00:00Z"), ZoneOffset.UTC));
		}
	}

	// review-12 R12STUTTER-6: the offer names both steps and why; the chosen fix's block says nothing changed yet, then what
	// the baseline measured.
	@Test
	void theBaselineLines() {
		assertTrue(FixText.takesEffect(true).english().startsWith("First RigTune measures one more session as it is"));
		assertTrue(FixText.takesEffect(false).english().contains("after you restart Minecraft"));
		FixTracker.Record b = FixTrackerTest.baseline();
		assertTrue(FixText.state(LABELS, b, true).english().startsWith("Nothing has changed yet"));
		assertEquals("Render Distance: 12 → 10, chosen 2026-09-02", FixText.applied(LABELS, b, ZoneOffset.UTC).english());
		FixTracker.Record ready = new FixTracker.Record(b.entryId(), b.adviceId(), b.key(), b.from(), b.to(), b.appliedAt(), b.rulesRevision(), b.now(),
				FixTracker.State.READY, new SessionOutcome(1, 400, 20, 900, 7, 20 / 7.0, 2), b.conditions(), null, 0, null, null, false);
		assertEquals("Your play as it is: 3.0 hitches a minute over 6:40. Apply the change to compare it with your play after.",
				FixText.state(LABELS, ready, true).english());
		assertEquals("Your last session didn't count: it had less than 5:00 of play after its first 3 minutes.",
				skipped(b, new FixTracker.Skip(FixTracker.SHORT_BEFORE, List.of())));
		assertEquals("Your last session didn't count: a setting changed while it ran.", skipped(b, new FixTracker.Skip(FixTracker.CHANGED, List.of())));
	}

	// review-13 R13-1: a chosen fix that expired before it was applied says so, and keeps "chosen" on its change line.
	@Test
	void aChosenFixThatExpiredSaysItWasNeverApplied() {
		FixTracker.Record b = FixTrackerTest.baseline();
		FixTracker.Record expired = FixTracker.advance(b, io.github.chaotix345.rigtune.core.history.Journal.State.OK, List.of(), null,
				b.appliedAt().plus(FixTracker.MAX_AGE).plusSeconds(1));
		assertEquals("Render Distance: 12 → 10, chosen 2026-09-02", FixText.applied(LABELS, expired, ZoneOffset.UTC).english());
		assertEquals("Never applied: no session as it is was measured in time, so nothing changed.", FixText.state(LABELS, expired, true).english());
	}

	private static String skipped(FixTracker.Record m, FixTracker.Skip skip) {
		FixTracker.Record r = new FixTracker.Record(m.entryId(), m.adviceId(), m.key(), m.from(), m.to(), m.appliedAt(), m.rulesRevision(), m.now(), m.state(),
				m.before(), m.conditions(), m.after(), 1, skip, m.verdict(), m.dismissed());
		return FixText.skipped(LABELS, r).english();
	}
}
