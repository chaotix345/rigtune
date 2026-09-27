package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Action;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.github.chaotix345.rigtune.core.tryit.TryItFixtures.ENTRY;
import static io.github.chaotix345.rigtune.core.tryit.TryItFixtures.KEY;
import static io.github.chaotix345.rigtune.core.tryit.TryItFixtures.SESSION;
import static io.github.chaotix345.rigtune.core.tryit.TryItFixtures.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md AC6.8 (unit part), docs/research/v0.5/feature-try-it.md §2.5: the stage is derived from tryit.json's
// try, its pair in benchmarks.json, its entry in history.json (with last-apply.json's failures), this session and the
// live flags, never stored, so a crash or quit at any point resumes or closes cleanly. One test per row of the table,
// plus the footer each stage offers and the decision a closing stage records.
class TryItFlowTest {
	private static final String OTHER_SESSION = "session-b";
	private static final TryItFlow.Live IDLE = new TryItFlow.Live(SESSION, false, false);
	private static final TryItFlow.Live IDLE_LATER = new TryItFlow.Live(OTHER_SESSION, false, false);
	private static final TryItFlow.Live MEASURING = new TryItFlow.Live(SESSION, true, false);
	private static final TryItFlow.Live APPLYING = new TryItFlow.Live(SESSION, false, true);

	private static final TryIt RESTART = TryItFixtures.restartTry();
	private static final TryIt NOW_HERE = TryItFixtures.tryOf("vanilla.renderDistance", TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT);
	private static final TryIt NOW_WORLD = TryItFixtures.tryOf("vanilla.renderDistance", TryIt.Kind.NOW, BenchmarkRequest.Scene.BENCHMARK_WORLD);

	private static BenchmarkRecord before(TryIt t) {
		return run("before").at("2026-09-20T10:01:00Z").before(t.pairId()).build();
	}

	private static BenchmarkRecord after(TryIt t, String id, double low) {
		return run(id).at("2026-09-21T10:01:00Z").after(t.pairId()).low(low).cursor(ENTRY).build();
	}

	// The try's apply entry with its one change, for a NOW try's key or the restart try's.
	private static JournalEntry applied(TryIt t, String status) {
		return TryItFixtures.entry(ENTRY, "2026-09-20T10:05:00Z", JournalEntry.APPLY, null, change(t, status));
	}

	private static JournalChange change(TryIt t, String status) {
		return new JournalChange("c-try", JournalChange.SETTING, t.key(), t.from(), t.to(), null, null, null, null, status, "op-try", null, null);
	}

	private static TryItFlow.History ok(JournalEntry... entries) {
		return new TryItFlow.History(Journal.State.OK, List.of(entries), Map.of());
	}

	private static TryItFlow.History ok(Map<String, ApplyFailures.Failure> failures, JournalEntry... entries) {
		return new TryItFlow.History(Journal.State.OK, List.of(entries), failures);
	}

	private static ApplyFailures.Failure failure(ApplyResult.Status status, int attempt) {
		return new ApplyFailures.Failure("op-try", status, PendingActions.Type.PATCH_JSON, null, "sodium-options.json", "the file is locked", attempt);
	}

	private static TryItView derive(TryIt t, List<BenchmarkRecord> runs, TryItFlow.History history, TryItFlow.Live live) {
		return TryItFlow.derive(t, runs, history, live);
	}

	@Test
	void noTryIsNone() {
		assertSame(TryItView.EMPTY, TryItFlow.derive(null, List.of(), ok(), IDLE));
		assertEquals(TryItView.EMPTY, new TryItView(null));
		assertEquals(List.of(), TryItView.EMPTY.actions());
		assertNull(TryItView.EMPTY.closing());
	}

	@Test
	void measuringBeforeWhileTheFirstRunRuns() {
		TryItView v = derive(RESTART, List.of(), ok(), MEASURING);
		assertEquals(Stage.MEASURING_BEFORE, v.stage());
		assertSame(RESTART, v.tryIt());
		assertEquals(List.of(), v.actions(), "the GUI is hidden while it measures");
	}

	@Test
	void stoppedBeforeWhenTheFirstRunEndedWithoutARecord() {
		TryItView v = derive(RESTART, List.of(), ok(), IDLE);
		assertEquals(Stage.STOPPED_BEFORE, v.stage());
		assertEquals(List.of(Action.DONE), v.actions());
		assertEquals(TryIt.Decision.CANCELLED, v.closing());
		assertEquals(Stage.STOPPED_BEFORE, derive(RESTART, List.of(), ok(), IDLE_LATER).stage(), "a crash during the before");
		assertEquals(Stage.STOPPED_BEFORE, derive(RESTART, List.of(), new TryItFlow.History(Journal.State.MISSING, List.of(), Map.of()), IDLE).stage(),
				"no history.json yet");
	}

	@Test
	void applyingBetweenTheRuns() {
		assertEquals(Stage.APPLYING, derive(NOW_HERE, List.of(before(NOW_HERE)), ok(), APPLYING).stage());
	}

	@Test
	void stoppedBeforeWhenTheGameEndedBetweenTheBeforeAndTheApply() {
		TryItView v = derive(NOW_HERE, List.of(before(NOW_HERE)), ok(), IDLE);
		assertEquals(Stage.STOPPED_BEFORE, v.stage());
		assertNotNull(v.before());
		assertEquals(Stage.STOPPED_BEFORE, derive(NOW_HERE, List.of(before(NOW_HERE)), ok(), IDLE_LATER).stage());
	}

	@Test
	void awaitingTheRestartWhileTheChangeIsStaged() {
		TryItView v = derive(RESTART, List.of(before(RESTART)), ok(applied(RESTART, JournalChange.STAGED)), IDLE);
		assertEquals(Stage.AWAITING_RESTART, v.stage());
		assertEquals(JournalChange.STAGED, v.changeStatus());
		assertEquals(List.of(Action.CANCEL_TRY, Action.DONE), v.actions());
		assertNull(v.closing());
		assertEquals(Stage.AWAITING_RESTART, derive(RESTART, List.of(before(RESTART)),
				ok(Map.of("op-try", failure(ApplyResult.Status.ABANDONED, 3)), applied(RESTART, JournalChange.STAGED)), IDLE).stage(),
				"only a FAILED op is retried");
	}

	@Test
	void retryingAfterAHelperFailure() {
		TryItView v = derive(RESTART, List.of(before(RESTART)), ok(Map.of("op-try", failure(ApplyResult.Status.FAILED, 2)),
				applied(RESTART, JournalChange.STAGED)), IDLE_LATER);
		assertEquals(Stage.RETRYING, v.stage());
		assertNotNull(v.failure());
		assertEquals(2, v.failure().attempt());
		assertEquals("the file is locked", v.failure().reason());
		assertEquals(List.of(Action.CANCEL_TRY, Action.DONE), v.actions());
	}

	@Test
	void notAppliedWhenTheHelperGaveUp() {
		TryItView v = derive(RESTART, List.of(before(RESTART)), ok(Map.of("op-try", failure(ApplyResult.Status.ABANDONED, 3)),
				applied(RESTART, JournalChange.ABANDONED)), IDLE_LATER);
		assertEquals(Stage.NOT_APPLIED, v.stage());
		assertEquals(JournalChange.ABANDONED, v.changeStatus());
		assertNotNull(v.failure());
		assertEquals(List.of(Action.DONE), v.actions());
		assertEquals(TryIt.Decision.FAILED, v.closing());
		assertNull(derive(RESTART, List.of(before(RESTART)), ok(applied(RESTART, JournalChange.ABANDONED)), IDLE_LATER).failure(),
				"lost without a helper run: no reason");
	}

	@Test
	void notAppliedWhenThePendingChangesWereDiscarded() {
		TryItView v = derive(RESTART, List.of(before(RESTART)), ok(applied(RESTART, JournalChange.DISCARDED)), IDLE);
		assertEquals(Stage.NOT_APPLIED, v.stage());
		assertEquals(JournalChange.DISCARDED, v.changeStatus());
		assertNull(v.failure());
	}

	@Test
	void cancelledByAnUndoOfTheEntry() {
		JournalEntry undo = TryItFixtures.entry("e-undo", "2026-09-20T10:10:00Z", JournalEntry.UNDO, ENTRY);
		TryItView v = derive(RESTART, List.of(before(RESTART)), ok(applied(RESTART, JournalChange.DISCARDED), undo), IDLE);
		assertEquals(Stage.CANCELLED, v.stage());
		assertEquals(TryIt.Decision.CANCELLED, v.closing());
		assertEquals(List.of(Action.DONE), v.actions());
		JournalEntry undoAll = TryItFixtures.entry("e-undo", "2026-09-20T10:10:00Z", JournalEntry.UNDO, "all");
		assertEquals(Stage.CANCELLED, derive(RESTART, List.of(before(RESTART)), ok(applied(RESTART, JournalChange.DISCARDED), undoAll), IDLE).stage(),
				"Undo all cancels it too");
		JournalEntry earlierUndoAll = TryItFixtures.entry("e-old-undo", "2026-09-19T10:10:00Z", JournalEntry.UNDO, "all");
		assertEquals(Stage.NOT_APPLIED, derive(RESTART, List.of(before(RESTART)), ok(earlierUndoAll, applied(RESTART, JournalChange.DISCARDED)),
				IDLE).stage(), "an undo before the try's entry isn't its cancel");
	}

	@Test
	void measuringAfterWhileTheSecondRunRuns() {
		TryItView v = derive(NOW_HERE, List.of(before(NOW_HERE)), ok(applied(NOW_HERE, JournalChange.APPLIED)), MEASURING);
		assertEquals(Stage.MEASURING_AFTER, v.stage());
		assertEquals(List.of(), v.actions());
		assertEquals(Stage.MEASURING_AFTER, derive(RESTART, List.of(before(RESTART)), ok(applied(RESTART, JournalChange.APPLIED)),
				new TryItFlow.Live(OTHER_SESSION, true, false)).stage(), "a restart try measures after in the next session");
	}

	@Test
	void readyInTheSameSessionWhenTheSecondRunWasStopped() {
		for (TryIt t : List.of(NOW_HERE, NOW_WORLD)) {
			TryItView v = derive(t, List.of(before(t)), ok(applied(t, JournalChange.APPLIED)), IDLE);
			assertEquals(Stage.READY, v.stage(), t.scene().name());
			assertTrue(v.sameSession());
			assertEquals(List.of(Action.MEASURE_AGAIN, Action.KEEP, Action.REVERT), v.actions());
			assertNull(v.closing());
		}
	}

	@Test
	void interruptedWhenARestartCameBetweenTheRunsHere() {
		TryItView v = derive(NOW_HERE, List.of(before(NOW_HERE)), ok(applied(NOW_HERE, JournalChange.APPLIED)), IDLE_LATER);
		assertEquals(Stage.INTERRUPTED, v.stage());
		assertFalse(v.sameSession());
		assertEquals(List.of(Action.KEEP, Action.REVERT), v.actions());
		assertNull(v.verdict());
	}

	@Test
	void readyToMeasureAfterARestartInTheBenchmarkWorld() {
		for (TryIt t : List.of(RESTART, NOW_WORLD)) {
			TryItView v = derive(t, List.of(before(t)), ok(applied(t, JournalChange.APPLIED)), IDLE_LATER);
			assertEquals(Stage.READY, v.stage(), t.kind().name());
			assertFalse(v.sameSession());
			assertEquals(List.of(Action.MEASURE_NOW, Action.CANCEL_TRY, Action.LATER), v.actions());
		}
	}

	@Test
	void theResultWithItsVerdict() {
		List<BenchmarkRecord> runs = List.of(before(RESTART), after(RESTART, "after", 600));
		TryItView v = derive(RESTART, runs, ok(applied(RESTART, JournalChange.APPLIED)), IDLE_LATER);
		assertEquals(Stage.RESULT, v.stage());
		assertEquals(List.of(Action.KEEP, Action.REVERT, Action.DECIDE_LATER), v.actions());
		assertNotNull(v.verdict());
		assertEquals(TryItVerdict.Kind.BETTER, v.verdict().kind());
		assertEquals(20.0, v.verdict().lowPercent(), 1e-9);
		assertEquals("after", v.after().id());
		assertEquals(Stage.RESULT, derive(NOW_HERE, List.of(before(NOW_HERE), after(NOW_HERE, "after", 500)),
				ok(applied(NOW_HERE, JournalChange.APPLIED)), IDLE).stage());
	}

	@Test
	void theNewestAfterRunWins() {
		List<BenchmarkRecord> runs = List.of(before(RESTART), after(RESTART, "after-1", 600), run("other").after("tryit-other").low(100).build(),
				after(RESTART, "after-2", 400));
		TryItView v = derive(RESTART, runs, ok(applied(RESTART, JournalChange.APPLIED)), IDLE_LATER);
		assertEquals("after-2", v.after().id());
		assertEquals(TryItVerdict.Kind.WORSE, v.verdict().kind());
	}

	@Test
	void anotherPairsRunsAreNotThisTrys() {
		List<BenchmarkRecord> runs = List.of(run("b").before("tryit-other").build(), run("a").after("tryit-other").build());
		assertEquals(Stage.STOPPED_BEFORE, derive(RESTART, runs, ok(), IDLE).stage());
		assertNull(derive(RESTART, runs, ok(), IDLE).before());
	}

	@Test
	void revertPendingWhileTheUndoIsStaged() {
		JournalChange revert = JournalChange.setting(KEY, "ONE_FRAME", "ALWAYS", JournalChange.STAGED, "op-undo").reverting("c-try");
		JournalEntry undo = TryItFixtures.entry("e-undo", "2026-09-22T10:10:00Z", JournalEntry.UNDO, ENTRY, revert);
		List<BenchmarkRecord> runs = List.of(before(RESTART), after(RESTART, "after", 500));
		TryItView v = derive(RESTART, runs, ok(applied(RESTART, JournalChange.APPLIED), undo), IDLE_LATER);
		assertEquals(Stage.REVERT_PENDING, v.stage());
		assertEquals(TryIt.Decision.REVERTED, v.closing());
		assertEquals(List.of(Action.DONE), v.actions());
		JournalEntry dropped = TryItFixtures.entry("e-undo", "2026-09-22T10:10:00Z", JournalEntry.UNDO, ENTRY,
				revert.withStatus(JournalChange.DISCARDED));
		assertEquals(Stage.RESULT, derive(RESTART, runs, ok(applied(RESTART, JournalChange.APPLIED), dropped), IDLE_LATER).stage(),
				"the staged undo was discarded: back to the result");
	}

	@Test
	void revertedOnceTheUndoApplied() {
		TryItView v = derive(NOW_HERE, List.of(before(NOW_HERE), after(NOW_HERE, "after", 500)), ok(applied(NOW_HERE, JournalChange.REVERTED)), IDLE);
		assertEquals(Stage.REVERTED, v.stage());
		assertEquals(TryIt.Decision.REVERTED, v.closing());
		assertEquals(Stage.REVERTED, derive(NOW_HERE, List.of(), ok(applied(NOW_HERE, JournalChange.REVERTED)), IDLE).stage(), "whatever the runs");
	}

	@Test
	void noBeforeWhenItAgedOutOfTheHistory() {
		TryItView v = derive(RESTART, List.of(after(RESTART, "after", 500)), ok(applied(RESTART, JournalChange.APPLIED)), IDLE_LATER);
		assertEquals(Stage.NO_BEFORE, v.stage());
		assertEquals(List.of(Action.KEEP, Action.REVERT), v.actions());
		assertNull(v.verdict());
		assertNull(v.closing());
	}

	@Test
	void noEntryWhenItWasFoldedIntoABaseline() {
		JournalEntry baseline = TryItFixtures.entry("baseline-1", "2026-09-19T10:00:00Z", JournalEntry.APPLY, null,
				TryItFixtures.setting(KEY, JournalChange.APPLIED)).withFoldedEntryIds(List.of("e-old", ENTRY));
		TryItView v = derive(RESTART, List.of(before(RESTART)), ok(baseline), IDLE_LATER);
		assertEquals(Stage.NO_ENTRY, v.stage());
		assertEquals(List.of(Action.DONE), v.actions());
		assertEquals(TryIt.Decision.KEPT, v.closing());
		assertEquals(Stage.NO_ENTRY, derive(RESTART, List.of(), ok(baseline), IDLE_LATER).stage(), "whatever the runs");
		assertEquals(Stage.NO_ENTRY, derive(RESTART, List.of(before(RESTART), after(RESTART, "after", 500)), ok(), IDLE_LATER).stage(),
				"an after run proves the change was applied");
		TryIt measured = RESTART.withAfter(RESTART.settingsBefore(), OTHER_SESSION);
		assertEquals(Stage.NO_ENTRY, derive(measured, List.of(before(RESTART)), ok(), IDLE_LATER).stage(), "so does an after snapshot");
	}

	@Test
	void anUnreadableHistoryClosesNothing() {
		for (Journal.State state : List.of(Journal.State.UNREADABLE, Journal.State.CORRUPT, Journal.State.NEWER)) {
			TryItView v = derive(RESTART, List.of(before(RESTART)), new TryItFlow.History(state, List.of(applied(RESTART, JournalChange.APPLIED)),
					Map.of()), IDLE_LATER);
			assertEquals(Stage.HISTORY_UNREADABLE, v.stage(), state.name());
			assertNull(v.closing());
			assertEquals(List.of(Action.DONE), v.actions());
		}
	}

	@Test
	void anEntryWithoutTheKeysChangeWasntApplied() {
		JournalEntry other = TryItFixtures.entry(ENTRY, "2026-09-20T10:05:00Z", JournalEntry.APPLY, null,
				TryItFixtures.setting("vanilla.particles", JournalChange.APPLIED));
		assertEquals(Stage.NOT_APPLIED, derive(RESTART, List.of(before(RESTART)), ok(other), IDLE).stage());
	}

	@Test
	void theChainRunsOnlyInsideItsRuns() {
		Set<Stage> running = EnumSet.of(Stage.MEASURING_BEFORE, Stage.APPLYING, Stage.MEASURING_AFTER);
		for (Stage s : Stage.values()) {
			assertEquals(running.contains(s), s.chainRunning(), s.name());
		}
	}

	@Test
	void everyStageHasItsFooterAndClosing() {
		List<String> rows = new ArrayList<>();
		for (Stage s : Stage.values()) {
			TryItView same = view(s, true);
			TryItView other = view(s, false);
			rows.add(s + " " + same.actions() + (other.actions().equals(same.actions()) ? "" : " / " + other.actions()) + " " + same.closing());
		}
		assertEquals(List.of(
				"NONE [] null",
				"MEASURING_BEFORE [] null",
				"STOPPED_BEFORE [DONE] CANCELLED",
				"APPLYING [] null",
				"AWAITING_RESTART [CANCEL_TRY, DONE] null",
				"RETRYING [CANCEL_TRY, DONE] null",
				"NOT_APPLIED [DONE] FAILED",
				"CANCELLED [DONE] CANCELLED",
				"MEASURING_AFTER [] null",
				"READY [MEASURE_AGAIN, KEEP, REVERT] / [MEASURE_NOW, CANCEL_TRY, LATER] null",
				"INTERRUPTED [KEEP, REVERT] null",
				"RESULT [KEEP, REVERT, DECIDE_LATER] null",
				"REVERT_PENDING [DONE] REVERTED",
				"REVERTED [DONE] REVERTED",
				"NO_BEFORE [KEEP, REVERT] null",
				"NO_ENTRY [DONE] KEPT",
				"HISTORY_UNREADABLE [DONE] null"), rows);
	}

	private static TryItView view(Stage stage, boolean sameSession) {
		return new TryItView(stage, RESTART, null, null, null, null, null, sameSession);
	}
}
