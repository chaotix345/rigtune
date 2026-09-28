package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

// docs/v0.5/SPEC.md 5 (C20): a tracked stutter fix follows its journal entry. staged -> measuring (once the change is in
// effect: at once for vanilla keys; for a staged key, the first session that starts with key = target while its change is
// APPLIED) -> compared; or undone (REVERTED), not applied (DISCARDED/ABANDONED, or nothing recorded for the key), replaced
// (the key changed again; also a staged fix whose first session after the helper applied it starts elsewhere: waiting for an
// on-target session would hold the one-fix slot until it expires), expired (5 skipped sessions, 14 days, or the entry gone
// from the journal). Only monitor sessions that started after the apply count, each of at least MIN_SESSION_SECONDS under
// the fix's conditions; the after side accumulates to clamp(before gameplay, MIN_AFTER_SECONDS, MAX_AFTER_SECONDS), then
// FixComparison decides once. Pure.
public final class FixTracker {
	public static final double MIN_SESSION_SECONDS = 120;
	public static final double MIN_AFTER_SECONDS = 300;
	public static final double MAX_AFTER_SECONDS = 1200;
	public static final int MAX_SKIPPED = 5;
	public static final Duration MAX_AGE = Duration.ofDays(14);
	// Wave B stamps appliedAt before restarting the session and captures the next start after the settings write; a start
	// that still reads the old value this soon after the apply is ignored, never taken as "replaced".
	public static final Duration SETTLE = Duration.ofSeconds(5);
	public static final String SHORT = "short";
	public static final String EXCLUDED = "excluded";
	public static final String IDLE = "idle";
	// review-11 STUTTER-7: the fixed key wasn't in the session's start or end snapshot (its file unreadable then).
	public static final String UNREAD = "unread";
	// A baseline session with less than FixGate.MIN_GAMEPLAY_SECONDS of compared play.
	public static final String SHORT_BEFORE = "short_before";
	// review-12 R12STUTTER-5: a setting changed during the session (and back, or between its start and end).
	public static final String CHANGED = "changed";
	// Not a session's skip: an expired record's mark that its journal entry is gone (nothing left to undo).
	public static final String GONE = "gone";
	// Not a session's skip either (review-13 R13-1): a chosen fix that expired or was replaced before it was applied (no
	// journal entry was ever made: nothing to undo or hold).
	public static final String NEVER_APPLIED = "never_applied";

	// review-12 R12STUTTER-6: BASELINE: the player chose to try the fix, and RigTune measures one session as it is first
	// (nothing changed yet); READY: that session was measured, the change can be applied. The session that led to the
	// offer is never a side of the comparison: it was chosen for being bad, so the next one would look better anyway.
	public enum State {
		STAGED, MEASURING, COMPARED, UNDONE, NOT_APPLIED, REPLACED, EXPIRED, BASELINE, READY;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static @Nullable State of(@Nullable String id) {
			for (State state : values()) {
				if (state.id().equals(id)) {
					return state;
				}
			}
			return null;
		}

		// Staged or measuring, or measuring the play before it (baseline, ready): only one fix at a time.
		public boolean tracking() {
			return this == STAGED || this == MEASURING || this == BASELINE || this == READY;
		}

		// Chosen but not applied yet: no journal entry, nothing to undo or hold.
		public boolean beforeApply() {
			return this == BASELINE || this == READY;
		}
	}

	// Why the last session didn't count: SHORT, or a FixConditions.Reason id with its args.
	public record Skip(String reason, List<String> args) {
		public Skip {
			args = args == null ? List.of() : List.copyOf(args);
		}
	}

	// One tracked fix (stutter-fixes.json, sf §2.5). before: the session the offer came from, frozen at the click;
	// conditions: the fix's conditions at the apply (the fixed key at its old value); after: the counted sessions so far.
	public record Record(String entryId, String adviceId, String key, String from, String to, Instant appliedAt, int rulesRevision, boolean now,
			State state, SessionOutcome before, FixConditions conditions, @Nullable SessionOutcome after, int skipped, @Nullable Skip lastSkip,
			FixComparison.@Nullable Verdict verdict, boolean dismissed) {
		public Record {
			skipped = Math.max(0, skipped);
		}

		// Staged or measuring, and not dismissed: it blocks another fix and is still being advanced.
		public boolean active() {
			return state.tracking() && !dismissed;
		}

		// It can still change (advance): not dismissed, and tracking or compared (an undo).
		public boolean open() {
			return !dismissed && (state.tracking() || state == State.COMPARED);
		}

		// It can still change with the journal: open and applied (a baseline has no journal entry yet).
		public boolean followsJournal() {
			return open() && !state.beforeApply();
		}

		// Its change can still be undone from the block: not undone or not applied, and not expired with its entry gone.
		public boolean undoable() {
			return state != State.UNDONE && state != State.NOT_APPLIED && !neverApplied()
					&& !(state == State.EXPIRED && lastSkip != null && GONE.equals(lastSkip.reason()));
		}

		// Chosen but not applied (yet, or ever: expired or replaced before the Apply).
		public boolean neverApplied() {
			return state.beforeApply() || lastSkip != null && NEVER_APPLIED.equals(lastSkip.reason());
		}

		// A chosen fix ends before its Apply (expired, replaced): marked, as nothing was applied.
		Record endedUnapplied(State next) {
			return new Record(entryId, adviceId, key, from, to, appliedAt, rulesRevision, now, next, before, conditions, after, skipped,
					new Skip(NEVER_APPLIED, List.of()), verdict, dismissed);
		}

		// The baseline session measured: the before side and the conditions the comparison keeps.
		Record ready(SessionOutcome measured, FixConditions at) {
			return new Record(entryId, adviceId, key, from, to, appliedAt, rulesRevision, now, State.READY, measured, at, null, 0, null, null, dismissed);
		}

		// The change applied at `at` (READY -> STAGED or MEASURING), its measured before side kept.
		public Record applied(Instant at, State next) {
			return new Record(entryId, adviceId, key, from, to, at, rulesRevision, now, next, before, conditions, null, 0, null, null, false);
		}

		public Record withState(State next) {
			return new Record(entryId, adviceId, key, from, to, appliedAt, rulesRevision, now, next, before, conditions, after, skipped, lastSkip, verdict,
					dismissed);
		}

		public Record dismiss() {
			return new Record(entryId, adviceId, key, from, to, appliedAt, rulesRevision, now, state, before, conditions, after, skipped, lastSkip, verdict,
					true);
		}

		Record skip(Skip skip) {
			return new Record(entryId, adviceId, key, from, to, appliedAt, rulesRevision, now, state, before, conditions, after, skipped + 1, skip, verdict,
					dismissed);
		}

		Record entryGone() {
			return new Record(entryId, adviceId, key, from, to, appliedAt, rulesRevision, now, State.EXPIRED, before, conditions, after, skipped,
					new Skip(GONE, List.of()), verdict, dismissed);
		}

		Record count(SessionOutcome next) {
			return new Record(entryId, adviceId, key, from, to, appliedAt, rulesRevision, now, state, before, conditions, next, skipped, null, verdict,
					dismissed);
		}

		Record compared(FixComparison.Verdict result) {
			return new Record(entryId, adviceId, key, from, to, appliedAt, rulesRevision, now, State.COMPARED, before, conditions, after, skipped, lastSkip,
					result, dismissed);
		}
	}

	// A finished session: when it started, its source (StutterReport.MONITOR or BENCHMARK), its outcome, the conditions at
	// its start and end, whether it's excluded (around a benchmark run, or Distant Horizons generated terrain in it: WS-B's
	// M4 rule, no comparison across such a session), whether it was mostly idle (FixGate.idle, RW-17) and whether a setting
	// changed during it (review-12 R12STUTTER-5: StutterAnalyzer.Result.settingChanges, a change and back included).
	public record SessionEnd(Instant startedAt, String source, SessionOutcome outcome, FixConditions atStart, FixConditions atEnd, boolean excluded,
			boolean idle, boolean changed) {
		public SessionEnd(Instant startedAt, String source, SessionOutcome outcome, FixConditions atStart, FixConditions atEnd, boolean excluded,
				boolean idle) {
			this(startedAt, source, outcome, atStart, atEnd, excluded, idle, false);
		}

		public SessionEnd(Instant startedAt, String source, SessionOutcome outcome, FixConditions atStart, FixConditions atEnd, boolean excluded) {
			this(startedAt, source, outcome, atStart, atEnd, excluded, false);
		}
	}

	private FixTracker() {
	}

	// How much gameplay the after side needs.
	public static double afterTarget(SessionOutcome before) {
		return Math.clamp(before.gameplaySeconds(), MIN_AFTER_SECONDS, MAX_AFTER_SECONDS);
	}

	// The record after the journal as it is now and, if one just ended, a session. journal: the history.json read's state
	// and entries; one that couldn't be read (not OK and not MISSING) decides nothing. A dismissed or finished record is
	// returned as it is (a compared one can still become undone).
	public static Record advance(Record r, Journal.State journal, List<JournalEntry> entries, @Nullable SessionEnd session, Instant now) {
		if (r.open() && r.state().beforeApply()) {
			return baseline(r, session, now);
		}
		if (!r.followsJournal() || journal != Journal.State.OK && journal != Journal.State.MISSING) {
			return r;
		}
		JournalEntry entry = entries.stream().filter(e -> r.entryId().equals(e.id())).findFirst().orElse(null);
		if (entry == null) {
			return r.state() == State.COMPARED ? r : r.entryGone();
		}
		JournalChange change = change(entry, r.key());
		String status = change == null ? null : change.status();
		if (JournalChange.REVERTED.equals(status)) {
			return r.withState(State.UNDONE);
		}
		if (r.state() == State.COMPARED) {
			return r;
		}
		if (change == null || JournalChange.DISCARDED.equals(status) || JournalChange.ABANDONED.equals(status)) {
			return r.withState(State.NOT_APPLIED);
		}
		if (Duration.between(r.appliedAt(), now).compareTo(MAX_AGE) > 0) {
			return r.withState(State.EXPIRED);
		}
		if (session == null || session.startedAt().isBefore(r.appliedAt()) || !StutterReport.MONITOR.equals(session.source())
				|| !JournalChange.APPLIED.equals(status)) {
			return r;
		}
		String atStart = session.atStart().settings().get(r.key());
		String atEnd = session.atEnd().settings().get(r.key());
		if (atStart == null || atEnd == null) {
			// Unknown (a config file mid-write), never "replaced". Missing at both ends is usually its mod removed: the
			// session's own reason (MODS) wins then (review-12 R12STUTTER-7).
			Skip why = atStart == null && atEnd == null ? skip(r, session) : null;
			Record skipped = r.skip(why != null ? why : new Skip(UNREAD, List.of(r.key())));
			return skipped.skipped() >= MAX_SKIPPED ? skipped.withState(State.EXPIRED) : skipped;
		}
		if (!SettingValues.same(atStart, r.to()) && SettingValues.same(atStart, r.from())
				&& Duration.between(r.appliedAt(), session.startedAt()).compareTo(SETTLE) <= 0) {
			return r;
		}
		if (!SettingValues.same(atStart, r.to()) || !SettingValues.same(session.atEnd().settings().get(r.key()), r.to())) {
			return r.withState(State.REPLACED);
		}
		Record m = r.state() == State.STAGED ? r.withState(State.MEASURING) : r;
		Skip skip = skip(m, session);
		if (skip != null) {
			Record skipped = m.skip(skip);
			return skipped.skipped() >= MAX_SKIPPED ? skipped.withState(State.EXPIRED) : skipped;
		}
		SessionOutcome after = m.after() == null ? session.outcome() : m.after().plus(session.outcome());
		Record counted = m.count(after);
		return after.gameplaySeconds() >= afterTarget(m.before()) ? counted.compared(FixComparison.compare(m.before(), after)) : counted;
	}

	// review-12 R12STUTTER-6: a chosen fix measures one session as it is first. The first monitor session that started after
	// the choice, with the key at its old value at both ends, not excluded or idle and with at least
	// FixGate.MIN_GAMEPLAY_SECONDS of compared play, is the before side (READY). The key changed meanwhile: replaced.
	// review-13: a chosen fix's appliedAt is the triggering session's start, and only a session that started strictly after it
	// counts, so the triggering session's own end (saved after the restart) never does, even from the same second.
	private static Record baseline(Record r, @Nullable SessionEnd session, Instant now) {
		if (Duration.between(r.appliedAt(), now).compareTo(MAX_AGE) > 0) {
			return r.endedUnapplied(State.EXPIRED);
		}
		if (r.state() == State.READY || session == null || !session.startedAt().isAfter(r.appliedAt()) || !StutterReport.MONITOR.equals(session.source())) {
			return r;
		}
		String atStart = session.atStart().settings().get(r.key());
		String atEnd = session.atEnd().settings().get(r.key());
		Skip skip = null;
		if (atStart == null || atEnd == null) {
			skip = new Skip(UNREAD, List.of(r.key()));
		} else if (!SettingValues.same(atStart, r.from()) || !SettingValues.same(atEnd, r.from())) {
			return r.endedUnapplied(State.REPLACED);
		} else if (session.excluded()) {
			skip = new Skip(EXCLUDED, List.of());
		} else if (session.idle()) {
			skip = new Skip(IDLE, List.of());
		} else if (session.changed() || session.atStart().differences(session.atEnd(), "").stream().anyMatch(d -> d.reason() != FixConditions.Reason.SETTING
				|| !d.args().get(1).isEmpty() && !d.args().get(2).isEmpty())) {
			skip = new Skip(CHANGED, List.of());
		} else if (session.outcome().gameplaySeconds() < FixGate.MIN_GAMEPLAY_SECONDS) {
			skip = new Skip(SHORT_BEFORE, List.of());
		}
		if (skip != null) {
			Record skipped = r.skip(skip);
			return skipped.skipped() >= MAX_SKIPPED ? skipped.endedUnapplied(State.EXPIRED) : skipped;
		}
		return r.ready(session.outcome(), session.atStart());
	}

	// The entry's last setting change of the key.
	private static @Nullable JournalChange change(JournalEntry entry, String key) {
		JournalChange found = null;
		for (JournalChange c : entry.changes()) {
			if (c.isSetting() && key.equals(c.key())) {
				found = c;
			}
		}
		return found;
	}

	// Why a session doesn't count, or null: excluded, too short, or the first condition that differs at its start, else at
	// its end.
	private static @Nullable Skip skip(Record r, SessionEnd session) {
		if (session.excluded()) {
			return new Skip(EXCLUDED, List.of());
		}
		if (session.idle()) {
			return new Skip(IDLE, List.of());
		}
		if (session.outcome().gameplaySeconds() < MIN_SESSION_SECONDS) {
			return new Skip(SHORT, List.of());
		}
		List<FixConditions.Difference> d = r.conditions().differences(session.atStart(), r.key());
		if (d.isEmpty()) {
			d = r.conditions().differences(session.atEnd(), r.key());
		}
		if (!d.isEmpty()) {
			return new Skip(d.getFirst().reason().id(), d.getFirst().args());
		}
		return session.changed() ? new Skip(CHANGED, List.of()) : null;
	}
}
