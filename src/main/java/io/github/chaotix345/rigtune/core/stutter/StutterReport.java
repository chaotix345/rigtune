package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// One capture's summary: what StutterScreen shows and what stutter.json keeps (docs/v0.4/SPEC.md "Shared contracts" C1,
// plus the header's display fields). The raw rings are never persisted.
// causes: share of the lost time each cause claimed (0-1, "unknown" = not explained); tags: how many spikes carried
// each correlational tag; worst: the 10 longest spikes (t = seconds into the capture). facts.gcOffsetMs is null until
// the GC clock was calibrated. hitches: spikes less than 100 ms apart counted once.
// A hand-edited file may hold nulls anywhere: the compact constructors keep them out of the lists and, since v0.5 (SD-5:
// a null "unknown" share crashed Copy summary's click handler), out of the maps' values.
// v0.5 (docs/v0.5/SPEC.md C1, RW-11; optional): settingsAtStart/settingsAtEnd, the managed settings when the session
// started and ended (null in older files and when not captured; not written when null; 0.4.0 ignores them and drops them
// on rewrite).
public record StutterReport(String startedAt, String source, @Nullable String mc, @Nullable String collector, long heapMaxMb,
		double sessionSeconds, double gameplaySeconds, long frames, double avgFps, double onePercentLowFps, long[] histogramCounts,
		long[] histogramTimeMs, Spikes spikes, double lostMs, Map<String, Double> causes, Map<String, Integer> tags, List<Worst> worst,
		Facts facts, List<String> advice, boolean enoughData, boolean phaseTiming, int hitches, @Nullable Map<String, String> settingsAtStart,
		@Nullable Map<String, String> settingsAtEnd) {
	public static final String MONITOR = "monitor";
	public static final String BENCHMARK = "benchmark";
	public static final int MAX_WORST = 10;

	public StutterReport {
		histogramCounts = histogramCounts == null ? new long[FrameRing.BUCKETS] : histogramCounts;
		histogramTimeMs = histogramTimeMs == null ? new long[FrameRing.BUCKETS] : histogramTimeMs;
		spikes = spikes == null ? new Spikes(0, 0, 0, 0) : spikes;
		causes = causes == null ? Map.of() : withoutNulls(causes);
		tags = tags == null ? Map.of() : withoutNulls(tags);
		worst = worst == null ? List.of() : worst.stream().filter(Objects::nonNull).toList();
		facts = facts == null ? new Facts(null, 0, 0, 0, null) : facts;
		advice = advice == null ? List.of() : advice.stream().filter(Objects::nonNull).toList();
		settingsAtStart = settingsAtStart == null ? null : withoutNulls(settingsAtStart);
		settingsAtEnd = settingsAtEnd == null ? null : withoutNulls(settingsAtEnd);
	}

	// The map itself when no value is null (its order kept), else a copy without those entries.
	private static <V> Map<String, V> withoutNulls(Map<String, V> map) {
		if (map.values().stream().noneMatch(Objects::isNull)) {
			return map;
		}
		Map<String, V> out = new LinkedHashMap<>();
		map.forEach((k, v) -> {
			if (v != null) {
				out.put(k, v);
			}
		});
		return out;
	}

	public StutterReport(String startedAt, String source, @Nullable String mc, @Nullable String collector, long heapMaxMb, double sessionSeconds,
			double gameplaySeconds, long frames, double avgFps, double onePercentLowFps, long[] histogramCounts, long[] histogramTimeMs, Spikes spikes,
			double lostMs, Map<String, Double> causes, Map<String, Integer> tags, List<Worst> worst, Facts facts, List<String> advice, boolean enoughData,
			boolean phaseTiming, int hitches) {
		this(startedAt, source, mc, collector, heapMaxMb, sessionSeconds, gameplaySeconds, frames, avgFps, onePercentLowFps, histogramCounts,
				histogramTimeMs, spikes, lostMs, causes, tags, worst, facts, advice, enoughData, phaseTiming, hitches, null, null);
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

	// v0.5 SD-2 (docs/v0.5/SPEC.md 2S): once a capture outgrew the frame ring, frames, avgFps and onePercentLowFps cover the
	// ring's window (its newest frames); every gameplay frame of the capture is still in the histogram, so then the
	// histogram holds more frames than `frames`. The window's length in seconds, or null when the numbers cover the whole
	// capture (always so in a 0.4 session).
	public @Nullable Double windowSeconds() {
		long all = 0;
		for (long n : histogramCounts) {
			all += n;
		}
		return all > frames && avgFps > 0 ? frames / avgFps : null;
	}

	public StutterReport withAdvice(List<String> ids) {
		return new StutterReport(startedAt, source, mc, collector, heapMaxMb, sessionSeconds, gameplaySeconds, frames, avgFps, onePercentLowFps,
				histogramCounts, histogramTimeMs, spikes, lostMs, causes, tags, worst, facts, List.copyOf(ids), enoughData, phaseTiming, hitches,
				settingsAtStart, settingsAtEnd);
	}

	public StutterReport withSettings(@Nullable Map<String, String> atStart, @Nullable Map<String, String> atEnd) {
		return new StutterReport(startedAt, source, mc, collector, heapMaxMb, sessionSeconds, gameplaySeconds, frames, avgFps, onePercentLowFps,
				histogramCounts, histogramTimeMs, spikes, lostMs, causes, tags, worst, facts, advice, enoughData, phaseTiming, hitches, atStart, atEnd);
	}
}
