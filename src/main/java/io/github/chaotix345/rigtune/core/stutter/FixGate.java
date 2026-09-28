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

	// The first reason that blocks, in this order: BENCHMARK, EXCLUDED, STORE, BUSY, LENGTH; null when the floor holds.
	// excluded: the session ran around a benchmark run or while Distant Horizons generated terrain (WS-B's M4 rule: no
	// comparison across such a session). busy: another fix is staged or being measured.
	public static FixOffer.@Nullable Reason check(StutterReport report, boolean excluded, boolean busy, boolean storeWritable) {
		if (!StutterReport.MONITOR.equals(report.source())) {
			return FixOffer.Reason.BENCHMARK;
		}
		if (excluded) {
			return FixOffer.Reason.EXCLUDED;
		}
		if (!storeWritable) {
			return FixOffer.Reason.STORE;
		}
		if (busy) {
			return FixOffer.Reason.BUSY;
		}
		if (report.hitches() < MIN_HITCHES || report.gameplaySeconds() < MIN_GAMEPLAY_SECONDS) {
			return FixOffer.Reason.LENGTH;
		}
		return null;
	}
}
