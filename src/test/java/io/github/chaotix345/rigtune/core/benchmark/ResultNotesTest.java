package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

// docs/v0.5/SPEC.md 2B: the result screen's lines about how the run was measured, as truth tables over ResultNotes.
class ResultNotesTest {
	private static PlannerResult.Measurement row(int rd, boolean passed, boolean complete) {
		return new PlannerResult.Measurement(rd, FakeRig.low(300), passed, complete);
	}

	private static @Nullable String english(@Nullable Text text) {
		return text == null ? null : text.english();
	}

	// RW-5 (AC2B.3): a distance whose terrain hadn't loaded (after its second try, when it had one) is named as not
	// measured; nothing calls it a fail.
	@Test
	void rw5UnmeasuredDistancesAreNamed() {
		assertNull(ResultNotes.unmeasured(List.of(row(17, true, true), row(24, false, true))));
		String one = english(ResultNotes.unmeasured(List.of(row(17, true, true), row(31, true, true), row(32, false, false))));
		assertEquals("? Render distance 32 couldn't be measured: its terrain hadn't loaded in time.", one);
		String two = english(ResultNotes.unmeasured(List.of(row(20, false, false), row(12, true, true), row(16, false, false))));
		assertEquals("? Render distances 16, 20 couldn't be measured: their terrain hadn't loaded in time.",
				two);
		assertFalse(one.contains("fail") || one.contains("✘"), one);
	}

	// RW-15 (AC2B.9): the stutter line's companion says how many steps the capture left out.
	@Test
	void rw15TheLineNamesTheStepsLeftOut() {
		assertNull(ResultNotes.stutterStepsLeftOut(0));
		assertEquals("The stutter check left out 1 step whose terrain hadn't loaded.", english(ResultNotes.stutterStepsLeftOut(1)));
		assertEquals("The stutter check left out 3 steps whose terrain hadn't loaded.", english(ResultNotes.stutterStepsLeftOut(3)));
	}

	// RW-9 (AC2B.8): "Measured with Distant Horizons rendering off" exactly when DH is installed and didn't render.
	@Test
	void rw9TruthTable() {
		String off = "Measured with Distant Horizons rendering off";
		assertEquals(off, english(ResultNotes.dhOff(true, false)));
		assertNull(ResultNotes.dhOff(true, true));
		assertNull(ResultNotes.dhOff(false, false));
		assertNull(ResultNotes.dhOff(false, true));
	}

	// RW-7 (AC2B.6): the noisy line names Distant Horizons building terrain or new terrain being generated when those tags
	// are on at least half of the capture's spikes, else it is the generic line; no line when the run wasn't noisy.
	@Test
	void rw7TruthTable() {
		String generic = "Results were noisy (7% spread): close background apps and retry.";
		String dh = "Results were noisy (7% spread) while Distant Horizons was building terrain; a later run may be steadier.";
		String terrain = "Results were noisy (7% spread) while new terrain was still being generated; a later run may be steadier.";
		assertNull(ResultNotes.noisy(0.03, 58, 40, 0, false));
		assertNull(ResultNotes.noisy(null, 58, 40, 0, true));
		assertEquals(dh, english(ResultNotes.noisy(0.07, 58, 40, 0, false)), "the real run: 40 of 58 spikes tagged dh");
		assertEquals(dh, english(ResultNotes.noisy(0.07, 58, 29, 0, false)), "exactly half");
		assertEquals(generic, english(ResultNotes.noisy(0.07, 58, 28, 0, false)));
		assertEquals(terrain, english(ResultNotes.noisy(0.07, 10, 2, 5, false)));
		assertEquals(dh, english(ResultNotes.noisy(0.07, 10, 6, 9, false)), "both: Distant Horizons first");
		assertEquals(generic, english(ResultNotes.noisy(0.07, 0, 0, 0, false)), "no spikes, no capture");
		// Review (part 2 L7): the run's own dhGenerating names Distant Horizons whatever the tags (never "background apps").
		assertEquals(dh, english(ResultNotes.noisy(0.07, 58, 3, 0, true)));
		assertEquals(dh, english(ResultNotes.noisy(0.07, 0, 0, 0, true)));
	}

	// RW-6 (AC2B.4): the line appears exactly when the run's dhGenerating is set.
	@Test
	void rw6LineExactlyWhenSet() {
		BenchmarkRecord.Context plain = TrendFixtures.CONTEXT;
		assertEquals("Distant Horizons was generating terrain during this run, so these numbers may be low.",
				english(ResultNotes.dhGenerating(plain.withDhGenerating(true))));
		assertNull(ResultNotes.dhGenerating(plain.withDhGenerating(false)));
		assertNull(ResultNotes.dhGenerating(plain));
		assertNull(ResultNotes.dhGenerating(null));
	}

	// docs/v0.5/SPEC.md 2A (L3, AC2A.1): each table row is a Tab stop that narrates its knob, average FPS, 1 % low, P99 and
	// pass / fail / not measured, and says which one is suggested.
	@Test
	void l3TheRowNarratesItsKnobNumbersAndVerdict() {
		assertEquals("Render distance 12: average 600 FPS, 1% low 300 FPS, P99 3.3 ms, meets the target. Suggested",
				english(ResultNotes.row(row(12, true, true), true)));
		assertEquals("Render distance 16: average 600 FPS, 1% low 300 FPS, P99 3.3 ms, misses the target", english(ResultNotes.row(row(16, false, true), false)));
		assertEquals("Render distance 32: average 600 FPS, 1% low 300 FPS, P99 3.3 ms, not measured (its terrain hadn't loaded in time)",
				english(ResultNotes.row(row(32, false, false), false)));
	}

	// Review (part 1 M4): a Measure pair whose "before" is left out of the trend keeps its numbers with a caveat.
	@Test
	void m4AComparisonWithAnExcludedBeforeSaysSo() {
		BenchmarkRecord plain = TrendFixtures.run("before").build();
		assertNull(ResultNotes.gainCaveat(plain));
		assertNull(ResultNotes.gainCaveat(null));
		assertEquals("The “before” run was the first in a new benchmark world, so this comparison may be off.",
				english(ResultNotes.gainCaveat(TrendFixtures.run("before").fresh().build())));
		assertEquals("Distant Horizons was generating terrain during the “before” run, so this comparison may be off.",
				english(ResultNotes.gainCaveat(TrendFixtures.run("before").dhGenerating().build())));
	}

	// The table's last column: a pass, a fail, or not measured (no ✘ for a distance that couldn't be measured).
	@Test
	void rw5TheTableMarksAnUnmeasuredDistanceWithoutAFail() {
		assertEquals("✔", ResultNotes.mark(row(12, true, true)));
		assertEquals("✘", ResultNotes.mark(row(12, false, true)));
		assertEquals("?", ResultNotes.mark(row(12, false, false)));
	}
}
