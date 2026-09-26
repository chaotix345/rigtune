package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.benchmark.FrameStats;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Turns a capture (copies of its rings) into a StutterReport and StutterFacts (docs/v0.4/SPEC.md 5): spikes, their
// attribution, the session aggregates and the GC facts. Pure and off the render thread (StutterService runs it on a
// worker when StutterScreen opens, at world exit, and when a benchmark ends).
public final class StutterAnalyzer {
	public static final long MS = 1_000_000L;
	public static final long SECOND = 1_000 * MS;
	public static final int MIN_SPIKES = 3;
	public static final double MIN_GAMEPLAY_SECONDS = 120;
	static final long SAVE_TIMEOUT = 10 * SECOND;
	static final long TELEPORT_WINDOW = 10 * SECOND;
	static final double CONTENTION = 0.85;
	static final int VANILLA_BACKLOG = 8;

	// The capture: its frame ring, the shared rings (filtered to [startNanos, endNanos]), and what the report needs about
	// the machine. collector: the family (g1, zgc, ...); totalRamMb null when unknown. phaseTiming: the phase timers were
	// complete (S-M1). deferModeWaits: Sodium's Chunk Updates mode makes frames wait for builds. gcMeasured: a GC listener
	// ran during the capture.
	public record Input(FrameRing.Snapshot frames, StutterRings.Snapshot rings, long startNanos, long endNanos, Instant startedAt, String source,
			@Nullable String mc, @Nullable String collector, long heapMaxMb, @Nullable Long totalRamMb, int cores, boolean phaseTiming,
			boolean deferModeWaits, boolean gcMeasured) {
		public Input(FrameRing.Snapshot frames, StutterRings.Snapshot rings, long startNanos, long endNanos, Instant startedAt, String source,
				@Nullable String mc, @Nullable String collector, long heapMaxMb, @Nullable Long totalRamMb, int cores, boolean phaseTiming,
				boolean deferModeWaits) {
			this(frames, rings, startNanos, endNanos, startedAt, source, mc, collector, heapMaxMb, totalRamMb, cores, phaseTiming, deferModeWaits, true);
		}
	}

	public record Result(StutterReport report, StutterFacts facts, List<Attributor.Attribution> attributions) {
	}

	private StutterAnalyzer() {
	}

	public static Result analyze(Input in) {
		FrameRing.Snapshot f = in.frames();
		long[] ends = f.ends();
		long ringStart = ends.length == 0 ? Long.MAX_VALUE : ends[0] & ~1L;
		List<SpikeDetector.Spike> spikes = new ArrayList<>(SpikeDetector.fromCandidates(f, ringStart));
		spikes.addAll(SpikeDetector.detect(ends));
		spikes.sort(Comparator.comparingLong(SpikeDetector.Spike::end));

		Map<Long, Attributor.Phases> phases = new HashMap<>();
		for (int r = 0; r < f.candidateRecords(); r++) {
			long chunks = f.candidate(r, FrameRing.C_CHUNKS);
			phases.put(f.candidate(r, FrameRing.C_END) & ~1L, new Attributor.Phases(f.candidate(r, FrameRing.C_PACKETS), f.candidate(r, FrameRing.C_TICKS),
					f.candidate(r, FrameRing.C_RENDER), f.candidate(r, FrameRing.C_PACKETS_BASE), f.candidate(r, FrameRing.C_TICKS_BASE),
					f.candidate(r, FrameRing.C_RENDER_BASE), (int) chunks, (int) (chunks >>> 32)));
		}
		GcSummary gc = gc(in);
		List<Attributor.Sample> samples = samples(in);
		Attributor.Context ctx = new Attributor.Context(gc.events(), saves(in), teleports(in), movingFast(in), samples, Math.max(1, in.cores()),
				in.phaseTiming(), in.deferModeWaits());
		List<Attributor.Attribution> attributions = new ArrayList<>();
		for (SpikeDetector.Spike s : spikes) {
			attributions.add(Attributor.attribute(s, phases.get(s.end()), ctx));
		}

		long lost = 0;
		Map<String, Long> claimed = new LinkedHashMap<>();
		Map<String, Integer> tagCounts = new LinkedHashMap<>();
		int[] severity = new int[SpikeDetector.Severity.values().length];
		for (Attributor.Attribution a : attributions) {
			lost += a.spike().lost();
			a.claims().forEach((cause, ns) -> claimed.merge(cause, ns, Long::sum));
			claimed.merge(Attributor.UNKNOWN, a.unexplained(), Long::sum);
			for (String tag : a.tags()) {
				tagCounts.merge(tag, 1, Integer::sum);
			}
			severity[a.spike().severity().ordinal()]++;
		}
		Map<String, Double> causes = new LinkedHashMap<>();
		Map<String, Double> claimedShares = new LinkedHashMap<>();
		for (String cause : Attributor.CAUSES) {
			Long ns = claimed.get(cause);
			if (ns != null && lost > 0) {
				causes.put(cause, round((double) ns / lost, 2));
				claimedShares.put(cause, 100.0 * ns / lost);
			}
		}
		Map<String, Double> taggedShares = new LinkedHashMap<>();
		Map<String, Integer> tags = new LinkedHashMap<>();
		for (String tag : Attributor.TAGS) {
			Integer n = tagCounts.get(tag);
			if (n != null) {
				tags.put(tag, n);
				taggedShares.put(tag, 100.0 * n / spikes.size());
			}
		}

		List<StutterReport.Worst> worst = attributions.stream()
				.sorted(Comparator.comparingLong((Attributor.Attribution a) -> a.spike().duration()).reversed())
				.limit(StutterReport.MAX_WORST)
				.map(a -> new StutterReport.Worst(round((a.spike().end() - in.startNanos()) / 1e9, 1), round(a.spike().duration() / 1e6, 1),
						round(a.spike().baseline() / 1e6, 1), a.notes()))
				.toList();

		double gameplaySeconds = f.gameplayNanos() / 1e9;
		FrameStats stats = FrameStats.of(gameplayDurations(ends));
		long[] histogramMs = Arrays.stream(f.histogramNanos()).map(ns -> Math.round(ns / 1e6)).toArray();
		boolean enough = spikes.size() >= MIN_SPIKES && gameplaySeconds >= MIN_GAMEPLAY_SECONDS;
		int hitches = SpikeDetector.hitches(spikes).size();
		StutterReport.Facts facts = new StutterReport.Facts(gc.liveSetPercent() == null ? null : (int) Math.round(gc.liveSetPercent()), gc.fullPauses(),
				gc.stalls(), gc.explicit(), in.rings().clock().calibrated() ? round(in.rings().clock().offsetMs(), 1) : null);
		StutterReport report = new StutterReport(in.startedAt().toString(), in.source(), in.mc(), displayName(in.collector()), in.heapMaxMb(),
				round((in.endNanos() - in.startNanos()) / 1e9, 1), round(gameplaySeconds, 1), f.gameplayFrames(),
				round(gameplaySeconds > 0 ? f.gameplayFrames() / gameplaySeconds : 0, 1), round(stats.onePercentLowFps(), 1), f.histogramCounts().clone(),
				histogramMs, new StutterReport.Spikes(severity[0], severity[1], severity[2], severity[3]), round(lost / 1e6, 1), causes, tags, worst, facts,
				List.of(), enough, in.phaseTiming(), hitches);

		Long room = in.totalRamMb() == null || in.totalRamMb() <= 0 ? null : Math.min(in.totalRamMb() / 2, in.totalRamMb() - 4096) - in.heapMaxMb();
		Set<String> unmeasured = new HashSet<>(List.of(Attributor.RENDER));
		if (!in.rings().clock().calibrated() || !in.gcMeasured()) {
			unmeasured.add(Attributor.GC);
		}
		if (!in.phaseTiming()) {
			unmeasured.addAll(List.of(Attributor.CHUNK_LOAD, Attributor.CHUNK_BUILD, Attributor.TICK));
		}
		if (samples.isEmpty()) {
			unmeasured.addAll(List.of(Attributor.DH, Attributor.CPU_CONTENTION));
		}
		StutterFacts stutterFacts = new StutterFacts(claimedShares, taggedShares, gc.fullPauses(), gc.stalls(), gc.explicit(), gc.liveSetPercent(), room,
				contentionShare(in, samples), gameplaySeconds > 0 ? spikes.size() / (gameplaySeconds / 60) : 0, in.collector(), in.gcMeasured(),
				unmeasured);
		return new Result(report, stutterFacts, attributions);
	}

	static long[] gameplayDurations(long[] ends) {
		long[] out = new long[ends.length];
		int n = 0;
		for (int i = 1; i < ends.length; i++) {
			if (!FrameRing.Snapshot.excluded(ends[i])) {
				long d = (ends[i] & ~1L) - (ends[i - 1] & ~1L);
				if (d > 0) {
					out[n++] = d;
				}
			}
		}
		return Arrays.copyOf(out, n);
	}

	private record GcSummary(List<Attributor.GcEvent> events, int fullPauses, int stalls, int explicit, @Nullable Double liveSetPercent) {
	}

	private static GcSummary gc(Input in) {
		long[] records = in.rings().gc();
		GcClock.Calibration clock = in.rings().clock();
		List<Attributor.GcEvent> events = new ArrayList<>();
		List<Long> live = new ArrayList<>();
		int full = 0;
		int stalls = 0;
		int explicit = 0;
		for (int i = 0; i + StutterRings.GC_STRIDE <= records.length; i += StutterRings.GC_STRIDE) {
			long received = records[i + StutterRings.G_RECEIVED];
			if (received < in.startNanos() || received > in.endNanos() + SECOND) {
				continue;
			}
			int flags = (int) records[i + StutterRings.G_FLAGS];
			if (clock.calibrated()) {
				events.add(new Attributor.GcEvent(clock.pauseStart(records[i + StutterRings.G_START_MS]), clock.pauseEnd(records[i + StutterRings.G_END_MS]), flags));
			}
			if (GcKind.collection(flags)) {
				if ((flags & GcKind.FULL) != 0 && (flags & GcKind.EXPLICIT) == 0) {
					full++;
				}
				if ((flags & GcKind.STALL_HINT) != 0) {
					stalls++;
				}
				if ((flags & GcKind.EXPLICIT) != 0) {
					explicit++;
				}
			}
			if ((flags & GcKind.MAJOR) != 0 && records[i + StutterRings.G_USED_AFTER] > 0) {
				live.add(records[i + StutterRings.G_USED_AFTER]);
			}
		}
		Double liveSet = null;
		if (!live.isEmpty() && in.heapMaxMb() > 0) {
			live.sort(null);
			liveSet = 100.0 * live.get(live.size() / 2) / (in.heapMaxMb() * 1024.0 * 1024.0);
		}
		return new GcSummary(events, full, stalls, explicit, liveSet);
	}

	private static List<long[]> events(Input in, int kind) {
		List<long[]> out = new ArrayList<>();
		long[] e = in.rings().events();
		for (int i = 0; i + StutterRings.EVENT_STRIDE <= e.length; i += StutterRings.EVENT_STRIDE) {
			if (e[i] == kind) {
				out.add(new long[]{e[i + 1], e[i + 2]});
			}
		}
		return out;
	}

	// Begin/end pairs in order; a begin without an end closes after `timeout` (or at the capture's end).
	private static List<Attributor.Interval> pairs(Input in, int begin, int end, long timeout) {
		List<long[]> all = new ArrayList<>();
		long[] e = in.rings().events();
		for (int i = 0; i + StutterRings.EVENT_STRIDE <= e.length; i += StutterRings.EVENT_STRIDE) {
			if (e[i] == begin || e[i] == end) {
				all.add(new long[]{e[i], e[i + 1]});
			}
		}
		List<Attributor.Interval> out = new ArrayList<>();
		Long open = null;
		for (long[] ev : all) {
			if (ev[0] == begin) {
				if (open != null) {
					out.add(new Attributor.Interval(open, Math.min(open + timeout, ev[1])));
				}
				open = ev[1];
			} else if (open != null) {
				out.add(new Attributor.Interval(open, ev[1]));
				open = null;
			}
		}
		if (open != null) {
			out.add(new Attributor.Interval(open, Math.min(open + timeout, in.endNanos())));
		}
		return out;
	}

	private static List<Attributor.Interval> saves(Input in) {
		return pairs(in, StutterRings.SAVE_BEGIN, StutterRings.SAVE_END, SAVE_TIMEOUT);
	}

	private static List<Attributor.Interval> movingFast(Input in) {
		return pairs(in, StutterRings.FAST_BEGIN, StutterRings.FAST_END, Long.MAX_VALUE / 4);
	}

	private static List<Attributor.Interval> teleports(Input in) {
		return events(in, StutterRings.TELEPORT).stream().map(t -> new Attributor.Interval(t[0], t[0] + TELEPORT_WINDOW)).toList();
	}

	public static List<Attributor.Sample> samples(Input in) {
		List<Attributor.Sample> out = new ArrayList<>();
		long[] s = in.rings().samples();
		for (int i = 0; i + StutterRings.SAMPLE_STRIDE <= s.length; i += StutterRings.SAMPLE_STRIDE) {
			long t = s[i + StutterRings.S_TIME];
			long window = s[i + StutterRings.S_WINDOW];
			if (window <= 0 || t < in.startNanos() || t - window > in.endNanos()) {
				continue;
			}
			String top = null;
			long topCpu = -1;
			for (int g = StutterRings.S_SERVER; g <= StutterRings.S_OTHER; g++) {
				if (s[i + g] > topCpu) {
					topCpu = s[i + g];
					top = StutterRings.GROUPS[g - StutterRings.S_RENDER];
				}
			}
			long scheduled = s[i + StutterRings.S_BACKLOG];
			long busy = s[i + StutterRings.S_BUSY];
			long total = s[i + StutterRings.S_TOTAL];
			// Sodium: jobs waiting with every builder busy. Vanilla (no thread counts): a real queue, not the few sections that
			// are always in flight while moving.
			boolean backlog = total > 0 ? scheduled > 0 && busy >= total : busy < 0 && scheduled >= VANILLA_BACKLOG;
			out.add(new Attributor.Sample(t - window, t, (double) s[i + StutterRings.S_DH] / window, (double) s[i + StutterRings.S_PROCESS] / window, top,
					backlog));
		}
		return out;
	}

	private static @Nullable Double contentionShare(Input in, List<Attributor.Sample> samples) {
		if (samples.isEmpty()) {
			return null;
		}
		long contended = samples.stream().filter(s -> s.processCores() >= CONTENTION * Math.max(1, in.cores())).count();
		return 100.0 * contended / samples.size();
	}

	public static @Nullable String displayName(@Nullable String family) {
		if (family == null) {
			return null;
		}
		return switch (family) {
			case "g1" -> "G1";
			case "zgc" -> "ZGC";
			case "shenandoah" -> "Shenandoah";
			case "parallel" -> "Parallel";
			case "serial" -> "Serial";
			default -> family.toUpperCase(Locale.ROOT);
		};
	}

	static double round(double value, int decimals) {
		double scale = Math.pow(10, decimals);
		return Math.round(value * scale) / scale;
	}
}
