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
	// Not a session's skip: an expired record's mark that its journal entry is gone (nothing left to undo).
	public static final String GONE = "gone";

	public enum State {
		STAGED, MEASURING, COMPARED, UNDONE, NOT_APPLIED, REPLACED, EXPIRED;

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

		// Staged or measuring: only one fix at a time.
		public boolean tracking() {
			return this == STAGED || this == MEASURING;
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

		// It can still change with the journal (advance): not dismissed, and staged, measuring or compared (an undo).
		public boolean followsJournal() {
			return !dismissed && (state.tracking() || state == State.COMPARED);
		}

		// Its change can still be undone from the block: not undone or not applied, and not expired with its entry gone.
		public boolean undoable() {
			return state != State.UNDONE && state != State.NOT_APPLIED && !(state == State.EXPIRED && lastSkip != null && GONE.equals(lastSkip.reason()));
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
	// M4 rule, no comparison across such a session) and whether it was mostly idle (FixGate.idle, RW-17).
	public record SessionEnd(Instant startedAt, String source, SessionOutcome outcome, FixConditions atStart, FixConditions atEnd, boolean excluded,
			boolean idle) {
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
		if (atStart == null || session.atEnd().settings().get(r.key()) == null) {
			// Unknown (a config file mid-write), never "replaced".
			Record skipped = r.skip(new Skip(UNREAD, List.of(r.key())));
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
		return d.isEmpty() ? null : new Skip(d.getFirst().reason().id(), d.getFirst().args());
	}
}
