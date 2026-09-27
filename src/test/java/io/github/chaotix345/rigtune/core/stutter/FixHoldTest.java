package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.10 (FixHoldTest): a main-list SetSetting that would reverse an active fix is unticked with the hold reason; other
// keys and same-direction changes aren't; nothing once the fix is undone (no hold then).
class FixHoldTest {
	private static final String RD = "vanilla.renderDistance";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final FixHold.Hold RD_HOLD = new FixHold.Hold(RD, "12", "10", "2026-10-02");
	private static final FixHold.Hold DEFER_HOLD = new FixHold.Hold(DEFER, "ZERO_FRAMES", "ALWAYS", "2026-10-03");

	private static Recommendation set(String key, String current, String target) {
		return Recommendation.of("set:" + key, Category.SETTING, Impact.MEDIUM, Text.literal(key + " " + current + " -> " + target),
				Text.literal("The rules' reason."), new Action.SetSetting(key, current, target), true);
	}

	private static Report report(Recommendation... recs) {
		return new Report(null, null, null, null, List.of(recs), 16, "bundled", false, Instant.parse("2026-10-04T00:00:00Z"));
	}

	@Test
	void aChangeBackIsUntickedWithTheReason() {
		Recommendation back = set(RD, "10", "12");
		Report held = FixHold.apply(report(back), List.of(RD_HOLD));
		Recommendation r = held.recommendations().getFirst();
		assertFalse(r.selectedByDefault());
		assertEquals(back.id(), r.id());
		assertEquals(back.titleText(), r.titleText());
		assertEquals(back.action(), r.action());
		assertEquals("The rules' reason. You set this on 2026-10-02 with the Stutter Doctor's fix; changing it here undoes that fix.",
				r.reasonText().english());
		assertEquals(r.reasonText().english(), r.reason());
		// Past where it was is away from the fix too.
		assertFalse(FixHold.apply(report(set(RD, "10", "16")), List.of(RD_HOLD)).recommendations().getFirst().selectedByDefault());
	}

	@Test
	void anyOtherValueOfAnEnumIsHeld() {
		assertFalse(FixHold.apply(report(set(DEFER, "ALWAYS", "ONE_FRAME")), List.of(DEFER_HOLD)).recommendations().getFirst().selectedByDefault());
		assertFalse(FixHold.apply(report(set(DEFER, "ALWAYS", "ZERO_FRAMES")), List.of(DEFER_HOLD)).recommendations().getFirst().selectedByDefault());
	}

	// Further the same way, toward the fix's own value, and other keys are the main list's business.
	@Test
	void sameDirectionAndOtherKeysAreLeftAlone() {
		Report same = report(set(RD, "10", "8"), set("vanilla.simulationDistance", "10", "12"), set(DEFER, "ZERO_FRAMES", "ALWAYS"));
		assertSame(same, FixHold.apply(same, List.of(RD_HOLD, DEFER_HOLD)));
		Report mixed = report(set(RD, "10", "8"), set(DEFER, "ALWAYS", "ONE_FRAME"));
		Report held = FixHold.apply(mixed, List.of(RD_HOLD, DEFER_HOLD));
		assertSame(mixed.recommendations().getFirst(), held.recommendations().getFirst());
		assertFalse(held.recommendations().get(1).selectedByDefault());
	}

	// Once the fix is undone (or not applied, or replaced) the service passes no hold for it.
	@Test
	void noHoldsNoChange() {
		Report r = report(set(RD, "10", "12"));
		assertSame(r, FixHold.apply(r, List.of()));
		assertSame(r, FixHold.apply(r, List.of(DEFER_HOLD)));
		Report none = report(new Recommendation("add:lithium", Category.ADD_MOD, Impact.HIGH, "Add Lithium", "r", new Action.AddMod("lithium", "id",
				"Lithium"), true));
		assertSame(none, FixHold.apply(none, List.of(RD_HOLD)));
	}

	// Numbers compare by sign only (no subtraction), so a hand-edited "1e99999999" costs nothing.
	@Test
	void hugeExponentsAreCheap() {
		Report r = report(set(RD, "10", "1e99999999"));
		Report held = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
				() -> FixHold.apply(r, List.of(RD_HOLD, new FixHold.Hold(RD, "1e99999999", "10", "2026-10-02"))));
		assertFalse(held.recommendations().getFirst().selectedByDefault());
	}

	// The holds come from the tracked fixes whose change is in effect: staged, measuring, compared (unless the comparison
	// found more stutter, when the main list may recommend going back) and expired; dismissed ones too (the setting stays).
	// Never an undone, not applied or replaced one. The date is the player's local day.
	@Test
	void holdsFromTheTrackedFixes() {
		FixTracker.Record m = FixTrackerTest.measuring();
		SessionOutcome before = FixComparisonTest.side(10, 300, 500, 2, 2, 2, 2, 2);
		SessionOutcome worse = FixComparisonTest.side(20, 300, 1000, 4, 4, 4, 4, 4);
		SessionOutcome better = FixComparisonTest.side(0, 300, 0, 0, 0, 0, 0, 0);
		java.util.function.BiFunction<FixTracker.State, SessionOutcome, FixTracker.Record> make = (state, after) -> new FixTracker.Record(state.id(),
				m.adviceId(), m.key(), m.from(), m.to(), Instant.parse("2026-10-02T23:30:00Z"), m.rulesRevision(), m.now(), state, before, m.conditions(),
				after, 0, null, after == null ? null : FixComparison.compare(before, after), false);
		List<FixTracker.Record> records = List.of(make.apply(FixTracker.State.STAGED, null), make.apply(FixTracker.State.MEASURING, null),
				make.apply(FixTracker.State.COMPARED, better), make.apply(FixTracker.State.COMPARED, worse), make.apply(FixTracker.State.EXPIRED, null),
				make.apply(FixTracker.State.UNDONE, better), make.apply(FixTracker.State.NOT_APPLIED, null), make.apply(FixTracker.State.REPLACED, null),
				make.apply(FixTracker.State.MEASURING, null).dismiss());
		assertEquals(FixComparison.Kind.MORE, records.get(3).verdict().kind());
		List<FixHold.Hold> holds = FixHold.holds(records, java.time.ZoneOffset.UTC);
		assertEquals(5, holds.size());
		assertEquals(new FixHold.Hold(RD, "12", "10", "2026-10-02"), holds.getFirst());
		assertEquals("2026-10-03", FixHold.holds(records.subList(0, 1), java.time.ZoneOffset.ofHours(2)).getFirst().appliedOn());
		assertEquals(List.of(), FixHold.holds(records.subList(3, 4), java.time.ZoneOffset.UTC));
	}

	@Test
	void theRestOfTheReportIsKept() {
		Report r = report(set(RD, "10", "12"), set("vanilla.simulationDistance", "10", "12"));
		Report held = FixHold.apply(r, List.of(RD_HOLD));
		assertEquals(r.rulesRevision(), held.rulesRevision());
		assertEquals(r.createdAt(), held.createdAt());
		assertEquals(2, held.recommendations().size());
		assertSame(r.recommendations().get(1), held.recommendations().get(1));
		assertTrue(held.recommendations().get(1).selectedByDefault());
	}
}
