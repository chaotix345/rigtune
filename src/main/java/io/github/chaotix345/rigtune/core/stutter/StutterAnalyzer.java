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
	static final long SETTINGS_WINDOW = 10 * SECOND;
	static final double CONTENTION = 0.85;
	static final int VANILLA_BACKLOG = 8;
	// The tags whose evidence is the sampler's (SD-1).
	static final List<String> SAMPLE_TAGS = List.of(Attributor.DH, Attributor.CPU_CONTENTION);

	// The capture: its frame ring, the shared rings (filtered to [startNanos, endNanos]), and what the report needs about
	// the machine. collector: the family (g1, zgc, ...); totalRamMb null when unknown. phaseTiming: the phase timers were
	// complete (S-M1). deferModeWaits: Sodium's Chunk Updates mode makes frames wait for builds. gcMeasured: a GC listener
	// ran during the capture. idleNanos (v0.5 RW-17): how long the game throttled its frame rate during the capture.
	public record Input(FrameRing.Snapshot frames, StutterRings.Snapshot rings, long startNanos, long endNanos, Instant startedAt, String source,
			@Nullable String mc, @Nullable String collector, long heapMaxMb, @Nullable Long totalRamMb, int cores, boolean phaseTiming,
			boolean deferModeWaits, boolean gcMeasured, long idleNanos) {
		public Input(FrameRing.Snapshot frames, StutterRings.Snapshot rings, long startNanos, long endNanos, Instant startedAt, String source,
				@Nullable String mc, @Nullable String collector, long heapMaxMb, @Nullable Long totalRamMb, int cores, boolean phaseTiming,
				boolean deferModeWaits, boolean gcMeasured) {
			this(frames, rings, startNanos, endNanos, startedAt, source, mc, collector, heapMaxMb, totalRamMb, cores, phaseTiming, deferModeWaits, gcMeasured,
					0);
		}

		public Input(FrameRing.Snapshot frames, StutterRings.Snapshot rings, long startNanos, long endNanos, Instant startedAt, String source,
				@Nullable String mc, @Nullable String collector, long heapMaxMb, @Nullable Long totalRamMb, int cores, boolean phaseTiming,
				boolean deferModeWaits) {
			this(frames, rings, startNanos, endNanos, startedAt, source, mc, collector, heapMaxMb, totalRamMb, cores, phaseTiming, deferModeWaits, true);
		}
	}

	// dhWorldGenCores (v0.5, docs/v0.5/SPEC.md 2S for 2B's RW-6): the core-equivalents Distant Horizons' world generation
	// used over the sampler windows the capture recorded (dhWorldGenCores(Input)); null without such a window.
	// compared (C20's comparison, review-11 STUTTER-2 and review-12 R12STUTTER-1/2): the part of the capture a stutter fix's
	// comparison takes (Compared); null only in a hand-built result (the whole capture then). dhWorldGenPeakCores (review-11 STUTTER-4, C20's WS-B rule): the busiest minute of Distant Horizons'
	// world generation over the whole capture (StutterRings.Totals), or over the held samples for a hand-built snapshot;
	// null when nothing was sampled. settingChanges (review-12 R12STUTTER-5): the setting changes during the capture (a
	// change and back too), from the whole capture's count or the held events.
	public record Result(StutterReport report, StutterFacts facts, List<Attributor.Attribution> attributions, @Nullable Double dhWorldGenCores,
			@Nullable Compared compared, @Nullable Double dhWorldGenPeakCores, int settingChanges) {
		public Result(StutterReport report, StutterFacts facts, List<Attributor.Attribution> attributions, @Nullable Double dhWorldGenCores) {
			this(report, facts, attributions, dhWorldGenCores, null, dhWorldGenCores, 0);
		}
	}

	// From fromNanos to the capture's end, with its gameplay and its wall length (seconds): spikes and gameplay always over
	// the same span. It starts at the capture's settle mark (SessionOutcome.SETTLE_NANOS: a world join's chunk streaming
	// never counts, on either side), or later where the rings no longer know every spike: once both the frame ring and the
	// candidate ring wrapped, from the oldest candidate still held when it is older than the frame ring (its gameplay stamp
	// says how much play follows it), else from the frame ring's first frame.
	public record Compared(long fromNanos, double gameplaySeconds, double seconds) {
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
				in.phaseTiming(), in.deferModeWaits(), chunkLoading(f, ringStart), settingsChanged(in));
		List<Attributor.Attribution> attributions = new ArrayList<>();
		for (SpikeDetector.Spike s : spikes) {
			Attributor.Phases p = phases.get(s.end());
			attributions.add(Attributor.attribute(s, p != null ? p : framePhases(f, s.end()), ctx));
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
		// SD-1: the rules' GC share is taken over the spikes the GC ring still covers, and the dh and cpuContention shares
		// over those the sample ring covers (both are every spike until a ring wrapped); the screen's shares and counts stay
		// over the whole capture.
		long sampleCover = sampleCoverStart(in);
		long gcLost = 0;
		long gcClaimed = 0;
		int sampleSpikes = 0;
		Map<String, Integer> sampleTagCounts = new LinkedHashMap<>();
		for (Attributor.Attribution a : attributions) {
			if (a.spike().end() >= gc.coverStart()) {
				gcLost += a.spike().lost();
				gcClaimed += a.claims().getOrDefault(Attributor.GC, 0L);
			}
			if (a.spike().end() >= sampleCover) {
				sampleSpikes++;
				for (String tag : SAMPLE_TAGS) {
					if (a.tags().contains(tag)) {
						sampleTagCounts.merge(tag, 1, Integer::sum);
					}
				}
			}
		}
		Map<String, Double> causes = new LinkedHashMap<>();
		Map<String, Double> claimedShares = new LinkedHashMap<>();
		for (String cause : Attributor.CAUSES) {
			Long ns = claimed.get(cause);
			if (ns != null && lost > 0) {
				causes.put(cause, round((double) ns / lost, 2));
				claimedShares.put(cause, !Attributor.GC.equals(cause) ? 100.0 * ns / lost : gcLost > 0 ? 100.0 * gcClaimed / gcLost : 0);
			}
		}
		Map<String, Double> taggedShares = new LinkedHashMap<>();
		Map<String, Integer> tags = new LinkedHashMap<>();
		for (String tag : Attributor.TAGS) {
			Integer n = tagCounts.get(tag);
			if (n != null) {
				tags.put(tag, n);
				if (SAMPLE_TAGS.contains(tag)) {
					int covered = sampleTagCounts.getOrDefault(tag, 0);
					taggedShares.put(tag, sampleSpikes > 0 ? 100.0 * covered / sampleSpikes : 0);
				} else {
					taggedShares.put(tag, 100.0 * n / spikes.size());
				}
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
		// SD-2: once the frame ring wrapped, frames, average and 1 % low all describe its window (StutterReport.windowSeconds),
		// unless the window holds no gameplay frame at all (a long stay in a menu): then as in 0.4.
		boolean wrapped = f.frames() > ends.length && stats.frames() > 0;
		long frames = wrapped ? stats.frames() : f.gameplayFrames();
		double avgFps = wrapped ? stats.avgFps() : gameplaySeconds > 0 ? f.gameplayFrames() / gameplaySeconds : 0;
		long[] histogramMs = Arrays.stream(f.histogramNanos()).map(ns -> Math.round(ns / 1e6)).toArray();
		boolean enough = spikes.size() >= MIN_SPIKES && gameplaySeconds >= MIN_GAMEPLAY_SECONDS;
		int hitches = SpikeDetector.hitches(spikes).size();
		StutterReport.Facts facts = new StutterReport.Facts(gc.liveSetPercent() == null ? null : (int) Math.round(gc.liveSetPercent()), gc.fullPauses(),
				gc.stalls(), gc.explicit(), in.rings().clock().calibrated() ? round(in.rings().clock().offsetMs(), 1) : null);
		StutterReport report = new StutterReport(in.startedAt().toString(), in.source(), in.mc(), displayName(in.collector()), in.heapMaxMb(),
				round((in.endNanos() - in.startNanos()) / 1e9, 1), round(gameplaySeconds, 1), frames, round(avgFps, 1), round(stats.onePercentLowFps(), 1),
				f.histogramCounts().clone(),
				histogramMs, new StutterReport.Spikes(severity[0], severity[1], severity[2], severity[3]), round(lost / 1e6, 1), causes, tags, worst, facts,
				List.of(), enough, in.phaseTiming(), hitches, null, null, in.idleNanos() > 0 ? round(in.idleNanos() / 1e9, 1) : null);

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
				unmeasured, FixEvidence.dominatedSpikes(attributions));
		Double dhWorldGen = dhWorldGenCores(in);
		StutterRings.Totals totals = in.rings().totals();
		Double dhPeak = totals == null ? dhWorldGen : Double.isNaN(totals.dhWorldGenPeakCores()) ? null : totals.dhWorldGenPeakCores();
		int settingChanges = totals != null ? totals.settingChanges() : heldSettingChanges(in);
		return new Result(report, stutterFacts, attributions, dhWorldGen, compared(f, ringStart, in.startNanos(), in.endNanos()), dhPeak, settingChanges);
	}

	// A hand-built snapshot's setting changes: the held SETTINGS_CHANGED events from the capture's start on.
	private static int heldSettingChanges(Input in) {
		long[] e = in.rings().events();
		int n = 0;
		for (int i = 0; i + StutterRings.EVENT_STRIDE <= e.length; i += StutterRings.EVENT_STRIDE) {
			if (e[i] == StutterRings.SETTINGS_CHANGED && (e[i + 2] & StutterRings.SETTING_BITS) != 0 && e[i + 1] - in.startNanos() >= 0) {
				n++;
			}
		}
		return n;
	}

	static Compared compared(FrameRing.Snapshot f, long ringStart, long startNanos, long endNanos) {
		long total = f.gameplayNanos();
		long from = startNanos;
		long after = total;
		if (f.frames() > f.ends().length && f.candidateCount() > f.candidateRecords()) {
			long oldest = f.candidateRecords() > 0 ? f.candidate(0, FrameRing.C_END) & ~1L : ringStart;
			if (oldest - ringStart < 0) {
				from = oldest;
				after = total - f.candidate(0, FrameRing.C_GAMEPLAY);
			} else {
				from = ringStart;
				after = 0;
				for (long d : gameplayDurations(f.ends())) {
					after += d;
				}
			}
		}
		if (f.markNanos() != FrameRing.NO_MARK && f.markNanos() - from > 0) {
			from = f.markNanos();
			after = f.gameplayAtMark() < 0 ? 0 : total - f.gameplayAtMark();
		}
		return new Compared(from, Math.max(0, after) / 1e9, Math.max(0, endNanos - from) / 1e9);
	}

	// review-8 ST-2: the phases of a frame the frame ring still holds, from its own phase word (the excess over the
	// baselines, so the baselines are 0), for a spike whose candidate record was pushed out; null when it isn't held.
	static Attributor.@Nullable Phases framePhases(FrameRing.Snapshot f, long end) {
		if (!f.hasFramePhases()) {
			return null;
		}
		int i = indexOf(f.ends(), end);
		if (i < 0) {
			return null;
		}
		int word = f.framePhases()[i];
		int previous = i > 0 ? FrameRing.chunkLoads(f.framePhases()[i - 1]) : 0;
		return new Attributor.Phases(FrameRing.packetsExcess(word), FrameRing.ticksExcess(word), FrameRing.renderExcess(word), 0, 0, 0,
				FrameRing.chunkLoads(word), previous);
	}

	// The index of the frame ending at `end` (ends ascend; bit 0 is the excluded flag), or -1.
	static int indexOf(long[] ends, long end) {
		int lo = 0;
		int hi = ends.length - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			long v = ends[mid] & ~1L;
			if (v < end) {
				lo = mid + 1;
			} else if (v > end) {
				hi = mid - 1;
			} else {
				return mid;
			}
		}
		return -1;
	}

	// review-8 P5A-F2: when the client loaded chunks, oldest first, spans less than Attributor.CHUNK_NEAR apart merged
	// (which never tags a spike the window wouldn't). Frames older than the frame ring count through their candidate
	// records (their own and the previous frame's loads), the frame ring's frames through their phase words.
	static List<Attributor.Interval> chunkLoading(FrameRing.Snapshot f, long ringStart) {
		List<Attributor.Interval> out = new ArrayList<>();
		boolean ring = f.hasFramePhases();
		for (int r = 0; r < f.candidateRecords(); r++) {
			long end = f.candidate(r, FrameRing.C_END) & ~1L;
			if (ring && end >= ringStart) {
				break;
			}
			long chunks = f.candidate(r, FrameRing.C_CHUNKS);
			boolean own = (int) chunks > 0;
			boolean previous = (int) (chunks >>> 32) > 0;
			if (own || previous) {
				long start = end - f.candidate(r, FrameRing.C_DURATION) - (previous ? f.candidate(r, FrameRing.C_BASELINE) : 0);
				addSpan(out, start, end);
			}
		}
		if (ring) {
			long[] ends = f.ends();
			int[] words = f.framePhases();
			for (int i = 0; i < ends.length; i++) {
				if (FrameRing.chunkLoads(words[i]) > 0) {
					long end = ends[i] & ~1L;
					addSpan(out, i > 0 ? ends[i - 1] & ~1L : end, end);
				}
			}
		}
		return out;
	}

	private static void addSpan(List<Attributor.Interval> out, long start, long end) {
		if (!out.isEmpty()) {
			Attributor.Interval last = out.getLast();
			if (start >= last.start() && start - last.end() <= Attributor.CHUNK_NEAR) {
				out.set(out.size() - 1, new Attributor.Interval(last.start(), Math.max(last.end(), end)));
				return;
			}
		}
		out.add(new Attributor.Interval(start, end));
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

	// coverStart: the oldest GC record the ring still holds once it wrapped (Long.MIN_VALUE: it covers the whole capture).
	private record GcSummary(List<Attributor.GcEvent> events, int fullPauses, int stalls, int explicit, @Nullable Double liveSetPercent,
			long coverStart) {
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
		// SD-1: the whole capture's counts once the GC ring wrapped, and the live-set samples from their own ring.
		StutterRings.Totals totals = in.rings().totals();
		boolean wrapped = totals != null && totals.gcAdded() > StutterRings.GC_CAPACITY;
		if (wrapped) {
			full = totals.fullGcs();
			stalls = totals.stalls();
			explicit = totals.explicitGcs();
		}
		if (totals != null) {
			live.clear();
			long[] samples = totals.liveSamples();
			for (int i = 0; i + StutterRings.LIVE_STRIDE <= samples.length; i += StutterRings.LIVE_STRIDE) {
				long received = samples[i];
				if (received >= in.startNanos() && received <= in.endNanos() + SECOND) {
					live.add(samples[i + 1]);
				}
			}
		}
		long coverStart = !wrapped || records.length == 0 ? Long.MIN_VALUE
				: clock.calibrated() ? clock.pauseStart(records[StutterRings.G_START_MS]) : records[StutterRings.G_RECEIVED];
		Double liveSet = null;
		if (!live.isEmpty() && in.heapMaxMb() > 0) {
			live.sort(null);
			liveSet = 100.0 * live.get(live.size() / 2) / (in.heapMaxMb() * 1024.0 * 1024.0);
		}
		return new GcSummary(events, full, stalls, explicit, liveSet, coverStart);
	}

	// SD-1: the start of the oldest sample the ring still holds once it wrapped (Long.MIN_VALUE: it covers the whole capture).
	static long sampleCoverStart(Input in) {
		StutterRings.Totals totals = in.rings().totals();
		long[] s = in.rings().samples();
		// By the capacity: a sample added between the snapshot's two reads mustn't look like a wrap.
		if (totals == null || s.length == 0 || totals.samplesAdded() <= StutterRings.SAMPLE_CAPACITY) {
			return Long.MIN_VALUE;
		}
		return s[StutterRings.S_TIME] - s[StutterRings.S_WINDOW];
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

	// v0.5 RW-11: after each settings change or resource reload, the next 10 s. The event is dated when the old value was
	// last seen; its value's bits from StutterRings.SETTINGS_LEAD_SHIFT say how many ms later the change was seen, so the window runs
	// from the one to 10 s after the other.
	private static List<Attributor.Interval> settingsChanged(Input in) {
		return events(in, StutterRings.SETTINGS_CHANGED).stream()
				.map(t -> new Attributor.Interval(t[0], t[0] + (t[1] >>> StutterRings.SETTINGS_LEAD_SHIFT) * MS + SETTINGS_WINDOW)).toList();
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
			long dh = s[i + StutterRings.S_DH] + s[i + StutterRings.S_DH_WORLD_GEN];
			String top = null;
			long topCpu = -1;
			for (int g = StutterRings.S_SERVER; g <= StutterRings.S_OTHER; g++) {
				long cpu = g == StutterRings.S_DH ? dh : s[i + g];
				if (cpu > topCpu) {
					topCpu = cpu;
					top = StutterRings.GROUPS[g - StutterRings.S_RENDER];
				}
			}
			long scheduled = s[i + StutterRings.S_BACKLOG];
			long busy = s[i + StutterRings.S_BUSY];
			long total = s[i + StutterRings.S_TOTAL];
			// Sodium: jobs waiting with every builder busy. Vanilla (no thread counts): a real queue, not the few sections that
			// are always in flight while moving.
			boolean backlog = total > 0 ? scheduled > 0 && busy >= total : busy < 0 && scheduled >= VANILLA_BACKLOG;
			out.add(new Attributor.Sample(t - window, t, (double) dh / window, (double) s[i + StutterRings.S_PROCESS] / window, top, backlog));
		}
		return out;
	}

	// Distant Horizons' world generation CPU per second of the sampler windows the capture recorded: a window counts when
	// its midpoint lies outside every pause (PAUSE_BEGIN to PAUSE_END; a benchmark capture is paused between its sweeps and
	// for an excluded step). Null when no window counts (no sampler, or nothing recorded).
	static @Nullable Double dhWorldGenCores(Input in) {
		List<Attributor.Interval> paused = pairs(in, StutterRings.PAUSE_BEGIN, StutterRings.PAUSE_END, Long.MAX_VALUE / 4);
		long[] s = in.rings().samples();
		long cpu = 0;
		long time = 0;
		int p = 0;
		for (int i = 0; i + StutterRings.SAMPLE_STRIDE <= s.length; i += StutterRings.SAMPLE_STRIDE) {
			long t = s[i + StutterRings.S_TIME];
			long window = s[i + StutterRings.S_WINDOW];
			if (window <= 0 || t < in.startNanos() || t - window > in.endNanos()) {
				continue;
			}
			long mid = t - window / 2;
			while (p < paused.size() && paused.get(p).end() < mid) {
				p++;
			}
			if (p == paused.size() || paused.get(p).start() > mid) {
				cpu += s[i + StutterRings.S_DH_WORLD_GEN];
				time += window;
			}
		}
		return time == 0 ? null : (double) cpu / time;
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
