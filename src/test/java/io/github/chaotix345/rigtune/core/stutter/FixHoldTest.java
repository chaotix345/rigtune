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
		assertEquals("The rules' reason. The Stutter Doctor's fix set this on 2026-10-02; changing it back may bring the stutter back.",
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
