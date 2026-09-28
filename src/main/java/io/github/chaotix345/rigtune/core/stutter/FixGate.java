package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 5 (C20), the evidence gate's second layer: the client floor, constants in code that rules can only add
// to (their `evidence` is the third layer). A fix is offered only for the player's own play session (a monitor session,
// never a benchmark capture, whose sweeps change render distance on purpose), with at least MIN_HITCHES hitches (below it
// even a perfect fix, 8 -> 0 at equal play, can't be told from chance: p = 0.5^8 = 0.0039) and MIN_GAMEPLAY_SECONDS of
// gameplay, while no other fix is staged or being measured (one at a time, or the comparison is confounded) and
// stutter-fixes.json can be written.
public final class FixGate {
	public static final int MIN_HITCHES = 8;
	public static final double MIN_GAMEPLAY_SECONDS = 300;

	private FixGate() {
	}

	// The first reason that blocks, in this order: BENCHMARK, EXCLUDED, IDLE, CHANGED, STORE, BUSY, LENGTH; null when the
	// floor holds. outcome: what the comparison would take from the session (review-11 STUTTER-2/STUTTER-6: the covered
	// window, settingsChanged spikes left out), so the floor holds for the before side itself. excluded: the session ran
	// around a benchmark run or while Distant Horizons generated terrain (WS-B's M4 rule: no comparison across such a
	// session). IDLE: idle() below. changed: FixConditions moved between the session's start and the analysis; RW-11's
	// start and end settings count too (STUTTER-3). busy: another fix is staged or being measured.
	public static FixOffer.@Nullable Reason check(StutterReport report, SessionOutcome outcome, boolean excluded, boolean changed, boolean busy,
			boolean storeWritable) {
		if (!StutterReport.MONITOR.equals(report.source())) {
			return FixOffer.Reason.BENCHMARK;
		}
		if (excluded) {
			return FixOffer.Reason.EXCLUDED;
		}
		if (idle(report)) {
			return FixOffer.Reason.IDLE;
		}
		if (changed || !report.settingChanges().isEmpty()) {
			return FixOffer.Reason.CHANGED;
		}
		if (!storeWritable) {
			return FixOffer.Reason.STORE;
		}
		if (busy) {
			return FixOffer.Reason.BUSY;
		}
		if (outcome.hitches() < MIN_HITCHES || outcome.gameplaySeconds() < MIN_GAMEPLAY_SECONDS) {
			return FixOffer.Reason.LENGTH;
		}
		return null;
	}

	// The report's own numbers as the outcome (no conditions known beyond the report).
	public static FixOffer.@Nullable Reason check(StutterReport report, boolean excluded, boolean busy, boolean storeWritable) {
		return check(report, outcome(report), excluded, false, busy, storeWritable);
	}

	static SessionOutcome outcome(StutterReport report) {
		return new SessionOutcome(1, report.gameplaySeconds(), report.hitches(), report.lostMs(), 0, 0, 0);
	}

	// RW-17 (the coordinator's rule for C20): the game throttled its frame rate (AFK, minimised, Dynamic FPS) for longer
	// than the session was played; such a session is no comparison side, before or after.
	public static boolean idle(StutterReport report) {
		return report.idleSeconds() != null && report.idleSeconds() > report.gameplaySeconds();
	}
}
