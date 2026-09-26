package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

// One capture's summary: what StutterScreen shows and what stutter.json keeps (docs/v0.4/SPEC.md "Shared contracts" C1,
// plus the header's display fields). The raw rings are never persisted.
// causes: share of the lost time each cause claimed (0-1, "unknown" = not explained); tags: how many spikes carried
// each correlational tag; worst: the 10 longest spikes (t = seconds into the capture). facts.gcOffsetMs is null until
// the GC clock was calibrated. hitches: spikes less than 100 ms apart counted once.
// A hand-edited file may hold nulls anywhere: the compact constructors keep them out of the lists.
public record StutterReport(String startedAt, String source, @Nullable String mc, @Nullable String collector, long heapMaxMb,
		double sessionSeconds, double gameplaySeconds, long frames, double avgFps, double onePercentLowFps, long[] histogramCounts,
		long[] histogramTimeMs, Spikes spikes, double lostMs, Map<String, Double> causes, Map<String, Integer> tags, List<Worst> worst,
		Facts facts, List<String> advice, boolean enoughData, boolean phaseTiming, int hitches) {
	public static final String MONITOR = "monitor";
	public static final String BENCHMARK = "benchmark";
	public static final int MAX_WORST = 10;

	public StutterReport {
		histogramCounts = histogramCounts == null ? new long[FrameRing.BUCKETS] : histogramCounts;
		histogramTimeMs = histogramTimeMs == null ? new long[FrameRing.BUCKETS] : histogramTimeMs;
		spikes = spikes == null ? new Spikes(0, 0, 0, 0) : spikes;
		causes = causes == null ? Map.of() : causes;
		tags = tags == null ? Map.of() : tags;
		worst = worst == null ? List.of() : worst.stream().filter(Objects::nonNull).toList();
		facts = facts == null ? new Facts(null, 0, 0, 0, null) : facts;
		advice = advice == null ? List.of() : advice.stream().filter(Objects::nonNull).toList();
	}

	public record Spikes(int minor, int major, int severe, int freeze) {
		public int total() {
			return minor + major + severe + freeze;
		}
	}

	// causes: Attributor notes ("gc:high:FULL", "worldSave:low", ...).
	public record Worst(double t, double ms, double baseMs, List<String> causes) {
		public Worst {
			causes = causes == null ? List.of() : causes.stream().filter(Objects::nonNull).toList();
		}
	}

	public record Facts(@Nullable Integer liveSetPct, int fullGcs, int stalls, int explicitGcs, @Nullable Double gcOffsetMs) {
	}

	public StutterReport withAdvice(List<String> ids) {
		return new StutterReport(startedAt, source, mc, collector, heapMaxMb, sessionSeconds, gameplaySeconds, frames, avgFps, onePercentLowFps,
				histogramCounts, histogramTimeMs, spikes, lostMs, causes, tags, worst, facts, List.copyOf(ids), enoughData, phaseTiming, hitches);
	}
}
