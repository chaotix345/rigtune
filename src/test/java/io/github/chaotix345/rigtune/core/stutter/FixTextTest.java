package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.history.HistoryModel;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
		assertEquals("A one-click fix needs a longer session: at least 5:00 of play and 8 hitches (this one: 4:12, 5).",
				notYet(FixOffer.Reason.LENGTH, "5:00", "8", "4:12", "5"));
		assertEquals("The measurements don't point at this clearly enough for a one-click fix; the advice above still applies.",
				notYet(FixOffer.Reason.EVIDENCE));
		assertEquals("One-click fixes are offered for your own play sessions, not for benchmark runs.", notYet(FixOffer.Reason.BENCHMARK));
		assertEquals("This server sends at most 8 chunks, so a shorter render distance would change nothing here.", notYet(FixOffer.Reason.SERVER, "8"));
		assertEquals("Another fix is still being measured. Wait for its comparison or dismiss it first.", notYet(FixOffer.Reason.BUSY));
		assertTrue(notYet(FixOffer.Reason.STORE).contains("stutter-fixes.json"));
		assertTrue(notYet(FixOffer.Reason.EXCLUDED).contains("can't be compared"));
		for (FixOffer.Reason reason : FixOffer.Reason.values()) {
			assertTrue(!notYet(reason).isBlank(), reason.name());
		}
	}

	@Test
	void theOfferRows() {
		String change = FixText.change(LABELS, "vanilla.renderDistance", "12", "10").english();
		assertEquals("Render Distance: 12 → 10", change);
		assertEquals("Try it in one click: Render Distance: 12 → 10", FixText.offer(FixText.change(LABELS, "vanilla.renderDistance", "12", "10")).english());
		assertEquals("Try this fix: Render Distance: 12 → 10. Opens a preview first.",
				FixText.tryNarration(FixText.change(LABELS, "vanilla.renderDistance", "12", "10")).english());
		assertTrue(FixText.takesEffect(true).english().startsWith("Takes effect now."));
		assertTrue(FixText.takesEffect(false).english().startsWith("Takes effect after you restart Minecraft."));
		assertEquals("Fix applied. Keep the Stutter Doctor on and play at least 6:40; RigTune then compares that play with this session.",
				FixText.applied(true, 400).english());
		assertEquals("Fix staged: it takes effect after you restart Minecraft. Then play at least 5:00 with the Stutter Doctor on.",
				FixText.applied(false, 300).english());
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
		assertEquals("4.8 hitches a minute, 1,310 ms lost a minute", FixText.rate(4.84, 1310.4).english());
	}

	@Test
	void theBlocksLines() {
		FixTracker.Record m = FixTrackerTest.measuring();
		assertEquals("Render Distance: 12 → 10, applied 2026-09-02", FixText.applied(LABELS, m, ZoneOffset.UTC).english());
		assertEquals("Measuring: 0:00 of 6:40 played with the Stutter Doctor on.", FixText.state(LABELS, m, true).english());
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
		assertEquals("Your last session didn't count: it was shorter than 2 minutes.", skipped(m, new FixTracker.Skip("short", List.of())));
		assertEquals("Your last session didn't count: Render Distance changed (10 → 12).",
				skipped(m, new FixTracker.Skip("setting", List.of("vanilla.renderDistance", "10", "12"))));
		assertEquals("Your last session didn't count: the window size or fullscreen changed.", skipped(m, new FixTracker.Skip("display", List.of())));
		assertTrue(skipped(m, new FixTracker.Skip("excluded", List.of())).contains("benchmark"));
		for (FixConditions.Reason reason : FixConditions.Reason.values()) {
			assertTrue(!skipped(m, new FixTracker.Skip(reason.id(), List.of("k", "a", "b"))).contains("null"), reason.name());
		}
		assertTrue(skipped(m, new FixTracker.Skip("weather", List.of())).contains("other conditions"));
	}

	private static String skipped(FixTracker.Record m, FixTracker.Skip skip) {
		FixTracker.Record r = new FixTracker.Record(m.entryId(), m.adviceId(), m.key(), m.from(), m.to(), m.appliedAt(), m.rulesRevision(), m.now(), m.state(),
				m.before(), m.conditions(), m.after(), 1, skip, m.verdict(), m.dismissed());
		return FixText.skipped(LABELS, r).english();
	}
}
