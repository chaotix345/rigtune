package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Set;

// docs/v0.4/SPEC.md 5 (C2): what the Stutter Doctor knows about one session, for the stutter condition keys in
// rules-v2 `stutterAdvice`. It rides on EvalContext.stutter (EvalContext.withStutter); the main list passes none, so the
// stutter keys are UNKNOWN there. Shares and percentages are 0-100. A null field is unknown (its key is UNKNOWN).
// claimedShares: cause -> share of the lost time it claimed (gc, chunkLoad, chunkBuild, tick, render, unknown), for
//   stutterShareAtLeast. taggedShares: tag -> share of spikes carrying it (worldSave, dh, cpuContention, afterTeleport,
//   movingFast), for stutterTaggedShareAtLeast.
// gcFullPauses/gcStalls/gcExplicitPauses: gcFullPausesAtLeast/gcStallsAtLeast/gcExplicitPausesAtLeast.
// liveSetPercent: liveSetPercentAtLeast. heapRaiseRoomMb (min(ram/2, ram - 4096) - heap): heapRaiseRoomMbAtLeast.
// cpuContentionShare: cpuContentionShareAtLeast. spikesPerMinute: spikesPerMinuteAtLeast, whose threshold is x10.
// The rules' thresholds are whole numbers (plan review K-M1); these facts are measured values.
// gcCollector (g1, zgc, shenandoah, parallel, serial): gcCollector.
// What wasn't measured stays UNKNOWN, also under `not` (fail closed): gcMeasured is false when no GC listener ran (the
// gc* counts are unknown then), and `unmeasured` names the causes and tags this capture couldn't measure (gc without a
// calibrated GC clock; chunkLoad/chunkBuild/tick without phase timing; render, which never claims; dh/cpuContention
// without sampler data). A cause or tag that was measurable and is absent from its map is 0.
public record StutterFacts(Map<String, Double> claimedShares, Map<String, Double> taggedShares, int gcFullPauses, int gcStalls,
		int gcExplicitPauses, @Nullable Double liveSetPercent, @Nullable Long heapRaiseRoomMb, @Nullable Double cpuContentionShare,
		double spikesPerMinute, @Nullable String gcCollector, boolean gcMeasured, Set<String> unmeasured) {
	public StutterFacts {
		claimedShares = claimedShares == null ? Map.of() : Map.copyOf(claimedShares);
		taggedShares = taggedShares == null ? Map.of() : Map.copyOf(taggedShares);
		unmeasured = unmeasured == null ? Set.of() : Set.copyOf(unmeasured);
	}

	// Everything measured (the contracts' shape).
	public StutterFacts(Map<String, Double> claimedShares, Map<String, Double> taggedShares, int gcFullPauses, int gcStalls, int gcExplicitPauses,
			@Nullable Double liveSetPercent, @Nullable Long heapRaiseRoomMb, @Nullable Double cpuContentionShare, double spikesPerMinute,
			@Nullable String gcCollector) {
		this(claimedShares, taggedShares, gcFullPauses, gcStalls, gcExplicitPauses, liveSetPercent, heapRaiseRoomMb, cpuContentionShare, spikesPerMinute,
				gcCollector, true, Set.of());
	}
}
