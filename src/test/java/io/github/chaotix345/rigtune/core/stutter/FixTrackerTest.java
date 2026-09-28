package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.8 (FixTrackerTest): the record follows the journal (staged -> measuring -> compared; undone, not applied, replaced,
// expired), sessions count only under the fix's conditions, and the after side accumulates to its target.
class FixTrackerTest {
	private static final String ENTRY = "entry-1";
	private static final String RD = "vanilla.renderDistance";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final Instant APPLIED_AT = Instant.parse("2026-09-02T09:14:00Z");
	private static final SessionOutcome BEFORE = FixComparisonTest.side(20, 400, 1600, 2, 3, 3, 4, 4, 4, 0);

	private static FixConditions conditions(String key, String value) {
		Map<String, String> s = new LinkedHashMap<>(FixConditionsTest.settings());
		s.put(key, value);
		FixConditions b = FixConditionsTest.base();
		return new FixConditions(b.mc(), b.modSetHash(), b.heapMaxMb(), b.collector(), b.width(), b.height(), b.fullscreen(), b.world(), b.phaseTiming(),
				b.gcMeasured(), s);
	}

	static FixTracker.Record staged() {
		return new FixTracker.Record(ENTRY, "stutter-sodium-defer", DEFER, "ZERO_FRAMES", "ALWAYS", APPLIED_AT, 17, false, FixTracker.State.STAGED, BEFORE,
				conditions(DEFER, "ZERO_FRAMES"), null, 0, null, null, false);
	}

	static FixTracker.Record measuring() {
		return new FixTracker.Record(ENTRY, "stutter-chunk-loading", RD, "12", "10", APPLIED_AT, 17, true, FixTracker.State.MEASURING, BEFORE,
				conditions(RD, "12"), null, 0, null, null, false);
	}

	private static List<JournalEntry> journal(String key, String status) {
		return List.of(new JournalEntry("other", "2026-10-01T00:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null,
				List.of(JournalChange.setting(key, "1", "2", JournalChange.APPLIED, "op-0"))),
				new JournalEntry(ENTRY, APPLIED_AT.toString(), JournalEntry.APPLY, "0.5.0", "26.2", null,
						List.of(JournalChange.setting(key, "old", "new", status, "op-1"))));
	}

	// A monitor session starting `minutesAfter` the apply, with the fixed key at `value` at its start and end.
	private static FixTracker.SessionEnd session(long minutesAfter, double gameplay, int hitches, String key, String value) {
		SessionOutcome outcome = new SessionOutcome(1, gameplay, hitches, 50.0 * hitches, 4, hitches / 4.0, 0);
		return new FixTracker.SessionEnd(APPLIED_AT.plus(Duration.ofMinutes(minutesAfter)), StutterReport.MONITOR, outcome, conditions(key, value),
				conditions(key, value), false);
	}

	private static Instant at(long minutesAfter) {
		return APPLIED_AT.plus(Duration.ofMinutes(minutesAfter + 30));
	}

	private static FixTracker.Record advance(FixTracker.Record r, List<JournalEntry> journal, FixTracker.SessionEnd session, Instant now) {
		return FixTracker.advance(r, Journal.State.OK, journal, session, now);
	}

	// RW-17 for C20: an after session the game throttled (idle) for longer than it was played doesn't count.
	@Test
	void aMostlyIdleSessionIsSkipped() {
		FixTracker.SessionEnd s = session(60, 400, 10, RD, "10");
		FixTracker.SessionEnd idle = new FixTracker.SessionEnd(s.startedAt(), s.source(), s.outcome(), s.atStart(), s.atEnd(), false, true);
		FixTracker.Record r = advance(measuring(), journal(RD, JournalChange.APPLIED), idle, at(60));
		assertEquals(FixTracker.State.MEASURING, r.state());
		assertNull(r.after());
		assertEquals(new FixTracker.Skip(FixTracker.IDLE, List.of()), r.lastSkip());
		assertEquals(1, r.skipped());
		assertEquals(s, new FixTracker.SessionEnd(s.startedAt(), s.source(), s.outcome(), s.atStart(), s.atEnd(), false), "not idle by default");
	}

	// C20 review L12: a fix that expired because its journal entry is gone has nothing left to undo; one that expired by
	// age keeps its Undo; undone and not-applied fixes have none.
	@Test
	void aFixWhoseEntryIsGoneHasNothingToUndo() {
		FixTracker.Record gone = FixTracker.advance(measuring(), Journal.State.OK, List.of(), null, at(5));
		assertEquals(FixTracker.State.EXPIRED, gone.state());
		assertFalse(gone.undoable());
		FixTracker.Record old = advance(measuring(), journal(RD, JournalChange.APPLIED), null, APPLIED_AT.plus(FixTracker.MAX_AGE).plusSeconds(1));
		assertEquals(FixTracker.State.EXPIRED, old.state());
		assertTrue(old.undoable());
		assertTrue(measuring().undoable());
		assertTrue(staged().undoable());
		assertFalse(measuring().withState(FixTracker.State.UNDONE).undoable());
		assertFalse(measuring().withState(FixTracker.State.NOT_APPLIED).undoable());
	}

	@Test
	void aStagedFixWaitsForTheRestart() {
		FixTracker.Record r = staged();
		// Still in the same game run: the change is STAGED and the session started with the old value.
		assertSame(r, advance(r, journal(DEFER, JournalChange.STAGED), session(5, 400, 10, DEFER, "ZERO_FRAMES"), at(5)));
		assertSame(r, advance(r, journal(DEFER, JournalChange.STAGED), null, at(5)));
	}

	// After the restart the helper applied it; the first session that starts with the target starts the after side and counts.
	@Test
	void aStagedFixStartsMeasuringAtTheFirstSessionWithTheTarget() {
		FixTracker.Record r = advance(staged(), journal(DEFER, JournalChange.APPLIED), session(60, 200, 4, DEFER, "ALWAYS"), at(60));
		assertEquals(FixTracker.State.MEASURING, r.state());
		assertNotNull(r.after());
		assertEquals(1, r.after().sessions());
		assertEquals(200, r.after().gameplaySeconds());
		assertEquals(0, r.skipped());
	}

	@Test
	void aStagedFixAppliedButChangedAgainIsReplaced() {
		FixTracker.Record r = advance(staged(), journal(DEFER, JournalChange.APPLIED), session(60, 200, 4, DEFER, "ONE_FRAME"), at(60));
		assertEquals(FixTracker.State.REPLACED, r.state());
	}

	// The after side accumulates to clamp(before gameplay, 300 s, 1200 s), then the comparison runs once.
	@Test
	void sessionsAccumulateToTheTargetThenCompare() {
		List<JournalEntry> journal = journal(RD, JournalChange.APPLIED);
		FixTracker.Record r = advance(measuring(), journal, session(1, 200, 2, RD, "10"), at(1));
		assertEquals(FixTracker.State.MEASURING, r.state());
		assertEquals(200, r.after().gameplaySeconds());
		r = advance(r, journal, session(30, 250, 3, RD, "10"), at(30));
		assertEquals(FixTracker.State.COMPARED, r.state());
		assertEquals(2, r.after().sessions());
		assertEquals(450, r.after().gameplaySeconds());
		assertEquals(5, r.after().hitches());
		assertEquals(FixComparison.compare(BEFORE, r.after()), r.verdict());
		// Compared is where it stays.
		assertSame(r, advance(r, journal, session(90, 900, 40, RD, "10"), at(90)));
	}

	@Test
	void theAfterTarget() {
		assertEquals(400, FixTracker.afterTarget(BEFORE));
		assertEquals(300, FixTracker.afterTarget(new SessionOutcome(1, 100, 9, 0, 2, 4.5, 0)));
		assertEquals(1200, FixTracker.afterTarget(new SessionOutcome(1, 5000, 9, 0, 2, 4.5, 0)));
	}

	@Test
	void aShortSessionDoesntCount() {
		FixTracker.Record r = advance(measuring(), journal(RD, JournalChange.APPLIED), session(1, 119, 2, RD, "10"), at(1));
		assertEquals(FixTracker.State.MEASURING, r.state());
		assertNull(r.after());
		assertEquals(1, r.skipped());
		assertEquals(new FixTracker.Skip("short", List.of()), r.lastSkip());
	}

	@Test
	void aSessionUnderOtherConditionsDoesntCountAndSaysWhy() {
		FixTracker.SessionEnd s = session(1, 400, 2, RD, "10");
		FixConditions b = s.atStart();
		FixConditions resized = new FixConditions(b.mc(), b.modSetHash(), b.heapMaxMb(), b.collector(), 1280, 720, false, b.world(), b.phaseTiming(),
				b.gcMeasured(), b.settings());
		FixTracker.Record r = advance(measuring(), journal(RD, JournalChange.APPLIED),
				new FixTracker.SessionEnd(s.startedAt(), s.source(), s.outcome(), resized, s.atEnd(), false), at(1));
		assertEquals(new FixTracker.Skip("display", List.of()), r.lastSkip());
		assertNull(r.after());
		// The end of the session is checked too (a setting changed while playing).
		Map<String, String> changed = new LinkedHashMap<>(b.settings());
		changed.put("vanilla.simulationDistance", "12");
		FixConditions atEnd = new FixConditions(b.mc(), b.modSetHash(), b.heapMaxMb(), b.collector(), b.width(), b.height(), b.fullscreen(), b.world(),
				b.phaseTiming(), b.gcMeasured(), changed);
		r = advance(measuring(), journal(RD, JournalChange.APPLIED), new FixTracker.SessionEnd(s.startedAt(), s.source(), s.outcome(), b, atEnd, false), at(1));
		assertEquals(new FixTracker.Skip("setting", List.of("vanilla.simulationDistance", "8", "12")), r.lastSkip());
		assertEquals(1, r.skipped());
	}

	// WS-B's M4 rule for C20: a session around a benchmark run or while Distant Horizons generated terrain never counts,
	// so no verdict is computed across one.
	@Test
	void anExcludedSessionDoesntCount() {
		FixTracker.SessionEnd s = session(1, 500, 2, RD, "10");
		FixTracker.Record r = advance(measuring(), journal(RD, JournalChange.APPLIED),
				new FixTracker.SessionEnd(s.startedAt(), s.source(), s.outcome(), s.atStart(), s.atEnd(), true), at(1));
		assertEquals(new FixTracker.Skip("excluded", List.of()), r.lastSkip());
		assertNull(r.after());
		assertEquals(FixTracker.State.MEASURING, r.state());
	}

	@Test
	void aCountedSessionClearsTheLastSkip() {
		List<JournalEntry> journal = journal(RD, JournalChange.APPLIED);
		FixTracker.Record r = advance(measuring(), journal, session(1, 60, 1, RD, "10"), at(1));
		r = advance(r, journal, session(10, 150, 1, RD, "10"), at(10));
		assertNull(r.lastSkip());
		assertEquals(1, r.skipped());
	}

	@Test
	void fiveSkippedSessionsExpire() {
		List<JournalEntry> journal = journal(RD, JournalChange.APPLIED);
		FixTracker.Record r = measuring();
		for (int i = 1; i <= 4; i++) {
			r = advance(r, journal, session(i, 30, 1, RD, "10"), at(i));
			assertEquals(FixTracker.State.MEASURING, r.state());
		}
		r = advance(r, journal, session(5, 30, 1, RD, "10"), at(5));
		assertEquals(FixTracker.State.EXPIRED, r.state());
		assertEquals(5, r.skipped());
	}

	@Test
	void fourteenDaysExpire() {
		List<JournalEntry> journal = journal(RD, JournalChange.APPLIED);
		assertEquals(FixTracker.State.MEASURING, FixTracker.advance(measuring(), Journal.State.OK, journal, null, APPLIED_AT.plus(Duration.ofDays(14))).state());
		assertEquals(FixTracker.State.EXPIRED,
				FixTracker.advance(measuring(), Journal.State.OK, journal, null, APPLIED_AT.plus(Duration.ofDays(14)).plusSeconds(1)).state());
		assertEquals(FixTracker.State.EXPIRED,
				FixTracker.advance(staged(), Journal.State.OK, journal(DEFER, JournalChange.STAGED), null, APPLIED_AT.plus(Duration.ofDays(15))).state());
	}

	@Test
	void undoingItMakesItUndone() {
		assertEquals(FixTracker.State.UNDONE, advance(staged(), journal(DEFER, JournalChange.REVERTED), null, at(1)).state());
		assertEquals(FixTracker.State.UNDONE, advance(measuring(), journal(RD, JournalChange.REVERTED), session(1, 400, 2, RD, "12"), at(1)).state());
		// A compared fix that's undone later keeps its verdict.
		FixTracker.Record compared = advance(measuring(), journal(RD, JournalChange.APPLIED), session(1, 500, 2, RD, "10"), at(1));
		assertEquals(FixTracker.State.COMPARED, compared.state());
		FixTracker.Record undone = advance(compared, journal(RD, JournalChange.REVERTED), null, at(60));
		assertEquals(FixTracker.State.UNDONE, undone.state());
		assertEquals(compared.verdict(), undone.verdict());
	}

	@Test
	void notAppliedWhenDiscardedAbandonedOrNothingWasRecorded() {
		assertEquals(FixTracker.State.NOT_APPLIED, advance(staged(), journal(DEFER, JournalChange.DISCARDED), null, at(1)).state());
		assertEquals(FixTracker.State.NOT_APPLIED, advance(staged(), journal(DEFER, JournalChange.ABANDONED), null, at(1)).state());
		assertEquals(FixTracker.State.NOT_APPLIED, advance(staged(), journal("vanilla.simulationDistance", JournalChange.APPLIED), null, at(1)).state());
	}

	// Folded away by the journal's cap (or history.json deleted): tracking stops; a computed verdict stays.
	@Test
	void anEntryGoneFromTheJournalStopsTracking() {
		List<JournalEntry> without = List.of(journal(RD, JournalChange.APPLIED).getFirst());
		assertEquals(FixTracker.State.EXPIRED, advance(measuring(), without, null, at(1)).state());
		assertEquals(FixTracker.State.EXPIRED, FixTracker.advance(measuring(), Journal.State.MISSING, List.of(), null, at(1)).state());
		FixTracker.Record compared = advance(measuring(), journal(RD, JournalChange.APPLIED), session(1, 500, 2, RD, "10"), at(1));
		assertSame(compared, advance(compared, without, null, at(60)));
	}

	@Test
	void anUnreadableJournalDecidesNothing() {
		for (Journal.State state : List.of(Journal.State.CORRUPT, Journal.State.NEWER, Journal.State.UNREADABLE)) {
			FixTracker.Record r = measuring();
			assertSame(r, FixTracker.advance(r, state, List.of(), session(1, 500, 2, RD, "10"), at(1)), state.name());
		}
	}

	// The immediate fix restarts the session: the one that ends then started before the apply, with the old value. It
	// neither counts nor replaces the fix. Nor does a benchmark capture.
	@Test
	void sessionsStartedBeforeTheApplyAndBenchmarkCapturesAreIgnored() {
		FixTracker.Record r = measuring();
		FixTracker.SessionEnd before = session(-10, 600, 20, RD, "12");
		assertSame(r, advance(r, journal(RD, JournalChange.APPLIED), before, at(0)));
		FixTracker.SessionEnd s = session(1, 500, 2, RD, "10");
		FixTracker.SessionEnd benchmark = new FixTracker.SessionEnd(s.startedAt(), StutterReport.BENCHMARK, s.outcome(), s.atStart(), s.atEnd(), false);
		assertSame(r, advance(r, journal(RD, JournalChange.APPLIED), benchmark, at(1)));
	}

	// A profile switch, the main list or the player changed the fixed key again.
	@Test
	void theKeyChangedAgainReplacesIt() {
		assertEquals(FixTracker.State.REPLACED, advance(measuring(), journal(RD, JournalChange.APPLIED), session(1, 500, 2, RD, "16"), at(1)).state());
		FixTracker.SessionEnd s = session(1, 500, 2, RD, "10");
		FixTracker.SessionEnd changedWhilePlaying = new FixTracker.SessionEnd(s.startedAt(), s.source(), s.outcome(), s.atStart(), conditions(RD, "8"), false);
		assertEquals(FixTracker.State.REPLACED, advance(measuring(), journal(RD, JournalChange.APPLIED), changedWhilePlaying, at(1)).state());
	}

	// Wave B stamps appliedAt before the session restart and captures the next session's start after the write; should a
	// start still read the old value within SETTLE of the apply, it is ignored rather than taken as "replaced".
	@Test
	void aSessionStartingAtTheOldValueRightAfterTheApplyIsIgnored() {
		FixTracker.Record r = measuring();
		List<JournalEntry> journal = journal(RD, JournalChange.APPLIED);
		FixTracker.SessionEnd s = session(0, 500, 2, RD, "12");
		FixTracker.SessionEnd early = new FixTracker.SessionEnd(APPLIED_AT.plusSeconds(3), s.source(), s.outcome(), s.atStart(), conditions(RD, "10"), false);
		assertSame(r, advance(r, journal, early, at(1)));
		FixTracker.SessionEnd late = new FixTracker.SessionEnd(APPLIED_AT.plusSeconds(6), s.source(), s.outcome(), s.atStart(), conditions(RD, "10"), false);
		assertEquals(FixTracker.State.REPLACED, advance(r, journal, late, at(1)).state());
		// Anything other than the old value right after the apply is still "replaced".
		FixTracker.SessionEnd other = new FixTracker.SessionEnd(APPLIED_AT.plusSeconds(3), s.source(), s.outcome(), conditions(RD, "16"),
				conditions(RD, "16"), false);
		assertEquals(FixTracker.State.REPLACED, advance(r, journal, other, at(1)).state());
	}

	// An appliedAt far in the future or the past (a hand-edited file) never throws.
	@Test
	void extremeDatesDontThrow() {
		FixTracker.Record m = measuring();
		for (Instant when : List.of(Instant.parse("+1000000000-12-31T00:00:00Z"), Instant.MAX, Instant.MIN)) {
			FixTracker.Record r = new FixTracker.Record(m.entryId(), m.adviceId(), m.key(), m.from(), m.to(), when, m.rulesRevision(), m.now(), m.state(),
					m.before(), m.conditions(), m.after(), m.skipped(), m.lastSkip(), m.verdict(), m.dismissed());
			FixTracker.advance(r, Journal.State.OK, journal(RD, JournalChange.APPLIED), session(1, 500, 2, RD, "10"), at(1));
		}
		assertEquals(FixTracker.State.EXPIRED, FixTracker.advance(new FixTracker.Record(m.entryId(), m.adviceId(), m.key(), m.from(), m.to(), Instant.MIN,
				m.rulesRevision(), m.now(), m.state(), m.before(), m.conditions(), m.after(), m.skipped(), m.lastSkip(), m.verdict(), m.dismissed()),
				Journal.State.OK, journal(RD, JournalChange.APPLIED), null, at(1)).state());
	}

	@Test
	void aNegativeSkipCountReadsAsZero() {
		FixTracker.Record m = measuring();
		assertEquals(0, new FixTracker.Record(m.entryId(), m.adviceId(), m.key(), m.from(), m.to(), m.appliedAt(), m.rulesRevision(), m.now(), m.state(),
				m.before(), m.conditions(), m.after(), -3, m.lastSkip(), m.verdict(), m.dismissed()).skipped());
	}

	@Test
	void dismissedAndFinishedRecordsAreLeftAlone() {
		FixTracker.Record dismissed = measuring().dismiss();
		assertSame(dismissed, advance(dismissed, journal(RD, JournalChange.REVERTED), session(1, 500, 2, RD, "10"), at(1)));
		for (FixTracker.State state : List.of(FixTracker.State.UNDONE, FixTracker.State.NOT_APPLIED, FixTracker.State.REPLACED, FixTracker.State.EXPIRED)) {
			FixTracker.Record r = measuring().withState(state);
			assertSame(r, advance(r, journal(RD, JournalChange.APPLIED), session(1, 500, 2, RD, "10"), at(1)), state.name());
		}
	}

	@Test
	void activeMeansStagedOrMeasuringAndNotDismissed() {
		assertEquals(true, staged().active());
		assertEquals(true, measuring().active());
		assertEquals(false, measuring().dismiss().active());
		assertEquals(false, measuring().withState(FixTracker.State.COMPARED).active());
		for (FixTracker.State state : FixTracker.State.values()) {
			assertEquals(state, FixTracker.State.of(state.id()));
		}
		assertEquals("not_applied", FixTracker.State.NOT_APPLIED.id());
		assertNull(FixTracker.State.of("paused"));
	}
}
