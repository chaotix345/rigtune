package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Difference;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.tryit.TryItText.Line;
import io.github.chaotix345.rigtune.core.tryit.TryItText.Tone;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Cause;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Caveat;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Kind;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict.Verdict;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.tryit.TryItFixtures.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 6 (X3, X5, AC6.5's line): Try it's words in English with their arguments. The verdict line always
// shows the 1 % low change, the average change and the floor; no verdict names each cause; a setting is named by
// History's label.
class TryItTextTest {
	private static final HistoryModel.Labels LABELS = new HistoryModel.Labels() {
		@Override
		public String label(String key) {
			return switch (key) {
				case "vanilla.renderDistance" -> "Render Distance";
				case "vanilla.particles" -> "Particles";
				default -> "Chunk Build Defer";
			};
		}

		@Override
		public String value(String key, String value) {
			return "ONE_FRAME".equals(value) ? "One frame" : "ALWAYS".equals(value) ? "Always" : value;
		}
	};

	private static final TryIt RESTART = TryItFixtures.restartTry();

	private static List<String> english(List<Line> lines) {
		return lines.stream().map(l -> l.text().english()).toList();
	}

	private static TryItView view(Stage stage, boolean sameSession) {
		return new TryItView(stage, RESTART, null, null, null, null, null, sameSession);
	}

	@Test
	void everyRefusalHasItsWords() {
		assertEquals("Tick exactly one suggestion to try it.", TryItText.refusal(Triable.Refusal.ONE, null).english());
		assertEquals("Only settings can be tried; mod changes can't.", TryItText.refusal(Triable.Refusal.KIND, null).english());
		assertEquals("Finish or cancel your other Try it first (Render Distance).", TryItText.refusal(Triable.Refusal.OPEN, "Render Distance").english());
		for (Triable.Refusal r : Triable.Refusal.values()) {
			Text text = TryItText.refusal(r, "x");
			assertEquals(r == Triable.Refusal.BUSY || r == Triable.Refusal.SCENE, text == null, r.name());
		}
		assertEquals("Leave your world first: the benchmark world opens from the title screen.",
				TryItText.sceneRefusal("rigtune.benchmark.refused.leave_world").english());
		assertEquals("The benchmark needs an open world.", TryItText.sceneRefusal("rigtune.status.benchmark_unavailable").english());
	}

	// Review L8/L10: what this session saw go wrong comes first, as a warning.
	@Test
	void aNoteComesFirst() {
		Text why = TryItText.sceneRefusal("rigtune.benchmark.refused.leave_world");
		List<Line> lines = TryItText.lines(view(Stage.READY, true).withNote(TryItText.lost(why)), LABELS);
		assertEquals("The measurement couldn't start: Leave your world first: the benchmark world opens from the title screen.",
				lines.getFirst().text().english());
		assertEquals(Tone.WARNING, lines.getFirst().tone());
		assertEquals("The measurement ended without a result.", TryItText.lost(null).english());
		assertEquals(List.of("Stopped before anything changed."), english(TryItText.lines(view(Stage.STOPPED_BEFORE, true), LABELS)).stream()
				.filter(l -> l.startsWith("Stopped")).toList());
	}

	@Test
	void theChangeIsNamedWithHistorysLabels() {
		assertEquals("Chunk Build Defer: Always → One frame", TryItText.change(RESTART, LABELS).english());
	}

	@Test
	void theIntro() {
		assertEquals(List.of(
				"RigTune measures your game now, applies this change, and measures again in the same place. Then you choose: keep it or revert it.",
				"Measured here, where you stand. The screen and controls are taken over while it measures; Esc stops it.",
				"Higher render distances load and save more of this world.",
				"Takes about 2 minutes."), english(TryItText.intro(TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT, "vanilla.renderDistance")));
		List<String> world = english(TryItText.intro(TryIt.Kind.NOW, BenchmarkRequest.Scene.BENCHMARK_WORLD, "vanilla.particles"));
		assertEquals("Measured in the benchmark world: same spot, time and weather every time.", world.get(1));
		assertEquals("The benchmark world has no mobs and clear weather, so this setting may show little change there.", world.getLast());
		assertTrue(english(TryItText.intro(TryIt.Kind.RESTART, BenchmarkRequest.Scene.BENCHMARK_WORLD, TryItFixtures.KEY)).getLast()
				.startsWith("This setting takes effect at the next start"));
	}

	@Test
	void theStepsSoFar() {
		BenchmarkRecord before = run("b").low(84.4).avg(142).build();
		BenchmarkRecord after = run("a").low(90).avg(150.6).build();
		TryItView v = new TryItView(Stage.READY, RESTART, before, after, null, JournalChange.APPLIED, null, false);
		assertEquals(List.of("Measured before: 1% lows 84 FPS, average 142 FPS", "Applied: Chunk Build Defer: Always → One frame",
				"Measured after: 1% lows 90 FPS, average 151 FPS", "The change is in effect. Measure again to see what it did.",
				"Measure again from the title screen: the benchmark world opens from there."), english(TryItText.lines(v, LABELS)));
		TryItView staged = new TryItView(Stage.AWAITING_RESTART, RESTART, before, null, null, JournalChange.STAGED, null, true);
		assertEquals(List.of("Measured before: 1% lows 84 FPS, average 142 FPS", "Waiting for a restart: Chunk Build Defer: Always → One frame",
				"Quit and restart Minecraft. RigTune reminds you to measure again at the next start."), english(TryItText.lines(staged, LABELS)));
	}

	@Test
	void everyStageSaysSomething() {
		Set<Stage> silent = EnumSet.of(Stage.NONE, Stage.RESULT);
		for (Stage s : Stage.values()) {
			List<Line> lines = TryItText.lines(view(s, false), LABELS);
			assertEquals(silent.contains(s), lines.isEmpty(), s.name());
		}
		ApplyFailures.Failure failure = new ApplyFailures.Failure("op", ApplyResult.Status.FAILED, PendingActions.Type.PATCH_JSON, null,
				"sodium-options.json", "the file is locked", 2);
		TryItView retrying = new TryItView(Stage.RETRYING, RESTART, null, null, null, JournalChange.STAGED, failure, false);
		assertEquals("The change wasn't applied at the last restart (the file is locked). RigTune tries again at the next restart (try 3 of 3).",
				english(TryItText.lines(retrying, LABELS)).getLast());
		TryItView dropped = new TryItView(Stage.NOT_APPLIED, RESTART, null, null, null, JournalChange.ABANDONED, null, false);
		assertEquals("The change wasn't applied (RigTune's helper dropped it), so there's nothing to measure or revert.",
				english(TryItText.lines(dropped, LABELS)).getLast());
		assertEquals("Stopped before the second measurement. The change is applied.", english(TryItText.lines(view(Stage.READY, true), LABELS)).getLast());
		assertEquals("Reverted: Chunk Build Defer gets its old value at the next restart.",
				english(TryItText.lines(view(Stage.REVERT_PENDING, false), LABELS)).getLast());
		assertEquals(List.of(), TryItText.lines(TryItView.EMPTY, LABELS));
	}

	@Test
	void theVerdictLineShowsTheNumbersAndTheFloor() {
		Verdict better = new Verdict(Kind.BETTER, 12.04, 8.0, 5.0, List.of(), List.of(Caveat.SCENE));
		List<Line> lines = TryItText.verdict(better, LABELS);
		assertEquals(List.of("Better: 1% lows +12.0% (average +8.0%), more than the ±5.0% these runs vary by.",
				"A measured comparison in one scene, not proof: busier places may differ."), english(lines));
		assertEquals(List.of(Tone.GOOD, Tone.NOTE), lines.stream().map(Line::tone).toList());
		assertEquals("Worse: 1% lows -7.5% (average -3.0%), more than the ±6.2% these runs vary by.",
				english(TryItText.verdict(new Verdict(Kind.WORSE, -7.5, -3.0, 6.2, List.of(), List.of()), LABELS)).getFirst());
		assertEquals("No clear change: 1% lows +1.2% (average +0.0%), within the ±5.0% these runs vary by.",
				english(TryItText.verdict(new Verdict(Kind.NO_CLEAR_CHANGE, 1.2, -0.01, 5.0, List.of(), List.of()), LABELS)).getFirst());
		assertEquals("No verdict: one of the measurements has no result.",
				english(TryItText.verdict(new Verdict(Kind.NO_NUMBERS, null, null, 5.0, List.of(), List.of()), LABELS)).getFirst());
	}

	@Test
	void noVerdictNamesEachCauseAndKeepsTheNumbersForReference() {
		List<Cause> causes = List.of(new Cause.Condition(Difference.RESOLUTION), new Cause.Excluded(Cause.Excluded.Why.FRESH_WORLD),
				new Cause.Excluded(Cause.Excluded.Why.DH_GENERATING), new Cause.Moved(), new Cause.Mods(), new Cause.Entry("e-2", JournalEntry.BENCHMARK),
				new Cause.Setting("vanilla.particles"));
		List<Line> lines = TryItText.verdict(new Verdict(Kind.NOT_COMPARABLE, 20.0, 10.0, 5.0, causes, List.of(Caveat.NOISY, Caveat.DH, Caveat.SESSIONS)),
				LABELS);
		assertEquals(List.of("No verdict: something else changed between the two measurements (resolution, the first run in a new benchmark world, "
						+ "Distant Horizons was generating terrain, you moved, the loaded mods, a later change in History (Benchmark result), "
						+ "Particles changed). Try it again for a verdict.",
				"For reference only: 1% lows +20.0%, average +10.0%.",
				"These runs varied a lot (more than 5%), so the result is less certain.",
				"Distant Horizons builds distant terrain in the background, which can make runs vary.",
				"The two measurements were in different game sessions: a driver or background program change would show up here too."), english(lines));
		assertEquals(Tone.WARNING, lines.getFirst().tone());
	}

	@Test
	void theNoticeAndTheToast() {
		assertEquals("Try it: Chunk Build Defer is waiting for a restart.", TryItText.notice(view(Stage.AWAITING_RESTART, true), LABELS).english());
		assertEquals("Try it: your result for Chunk Build Defer is ready.", TryItText.notice(view(Stage.RESULT, false), LABELS).english());
		assertEquals("Try it: your try of Chunk Build Defer has ended. Open it to see how.", TryItText.notice(view(Stage.NOT_APPLIED, false), LABELS).english());
		for (Stage s : List.of(Stage.NONE, Stage.MEASURING_BEFORE, Stage.APPLYING, Stage.MEASURING_AFTER)) {
			assertNull(TryItText.notice(view(s, true), LABELS), s.name());
		}
		for (Stage s : Stage.values()) {
			if (s != Stage.NONE && !s.chainRunning()) {
				assertNotNull(TryItText.notice(view(s, false), LABELS), s.name());
			}
		}
		assertEquals("RigTune: Try it", TryItText.toastTitle().english());
		assertEquals("Open RigTune to measure the change again.", TryItText.toastBody(Stage.READY).english());
		assertNotNull(TryItText.toastBody(Stage.RETRYING));
		assertNotNull(TryItText.toastBody(Stage.NOT_APPLIED));
		assertNull(TryItText.toastBody(Stage.RESULT));
		assertEquals("Kept: Chunk Build Defer: Always → One frame.", TryItText.kept(RESTART, LABELS).english());
		assertFalse(TryItText.overlay().english().isBlank());
	}
}
