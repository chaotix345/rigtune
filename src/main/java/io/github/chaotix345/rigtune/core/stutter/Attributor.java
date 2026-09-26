package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// Where a spike's lost time e = d - b likely went (docs/v0.4/SPEC.md 5; research §4.2). Measured causes claim
// milliseconds in a fixed order, each capped at what is left: GC pause overlap, then chunk-packet handling (the packets
// phase, with chunk loads), then client ticks, then the render phase as chunk building (only with evidence: a Sodium
// build backlog or a waiting Chunk Updates mode; without it the render excess stays unexplained). Correlations (a world
// save, Distant Horizons, CPU contention, after a teleport, chunks loading nearby, moving fast) are tags that never claim
// milliseconds. The
// unexplained remainder is always kept. Everything here is "likely", never a proven cause.
public final class Attributor {
	public static final long MS = 1_000_000L;

	public static final String GC = "gc";
	public static final String CHUNK_LOAD = "chunkLoad";
	public static final String CHUNK_BUILD = "chunkBuild";
	public static final String TICK = "tick";
	public static final String RENDER = "render";
	public static final String UNKNOWN = "unknown";
	public static final List<String> CAUSES = List.of(GC, CHUNK_LOAD, CHUNK_BUILD, TICK, RENDER, UNKNOWN);

	public static final String WORLD_SAVE = "worldSave";
	public static final String DH = "dh";
	public static final String CPU_CONTENTION = "cpuContention";
	public static final String AFTER_TELEPORT = "afterTeleport";
	public static final String MOVING_FAST = "movingFast";
	// review-8 P5A-F2: chunks loaded within CHUNK_NEAR of the spike (the client's CHUNK_LOAD count, not a measurement of
	// what they cost).
	public static final String CHUNKS_LOADING = "chunksLoading";
	public static final List<String> TAGS = List.of(WORLD_SAVE, DH, CPU_CONTENTION, AFTER_TELEPORT, CHUNKS_LOADING, MOVING_FAST);

	static final long STALL_NEAR = 100 * MS;
	static final long SAVE_NEAR = 50 * MS;
	static final long SAMPLE_NEAR = 125 * MS;
	static final long CHUNK_NEAR = 250 * MS;
	static final long MIN_PACKETS = 2 * MS;
	static final int CHUNK_BURST = 8;

	public static final String FULL = "FULL";
	public static final String EXPLICIT = "EXPLICIT";
	public static final String STALL = "STALL";
	static final String CONTEXT = "context";

	public enum Confidence {
		HIGH, MEDIUM, LOW;

		public String id() {
			return name().toLowerCase(Locale.ROOT);
		}

		static @Nullable Confidence of(String id) {
			for (Confidence c : values()) {
				if (c.id().equals(id)) {
					return c;
				}
			}
			return null;
		}
	}

	// A note read back ("gc:high:FULL:EXPLICIT", "cpuContention:low:builder", "afterTeleport:context"): the cause or tag,
	// its confidence (null for context tags), the GC flags, and the busiest thread group of a contention note.
	public record Note(String name, @Nullable Confidence confidence, boolean full, boolean explicit, boolean stall, @Nullable String group) {
		public static Note parse(String note) {
			String[] p = note.split(":");
			Confidence confidence = p.length > 1 ? Confidence.of(p[1]) : null;
			boolean full = false;
			boolean explicit = false;
			boolean stall = false;
			String group = null;
			for (int i = 2; i < p.length; i++) {
				switch (p[i]) {
					case FULL -> full = true;
					case EXPLICIT -> explicit = true;
					case STALL -> stall = true;
					default -> group = p[i];
				}
			}
			return new Note(p[0], confidence, full, explicit, stall, group);
		}
	}

	// This frame's phase times and the phases' running baselines (ns), and the chunk loads of this and the previous frame.
	public record Phases(long packets, long ticks, long render, long packetsBase, long ticksBase, long renderBase, int chunkLoads,
			int previousChunkLoads) {
	}

	// A GC pause mapped onto nanoTime (end includes the +1 ms guard), or any GC event for the stall check.
	public record GcEvent(long start, long end, int flags) {
	}

	public record Interval(long start, long end) {
		boolean overlaps(long from, long to) {
			return start <= to && end >= from;
		}
	}

	// A sampler window: core-equivalents used by DH threads and by the whole process, the busiest background group, and
	// whether chunk builds were backed up.
	public record Sample(long start, long end, double dhCores, double processCores, String topGroup, boolean backlog) {
	}

	// The capture's evidence. phaseTiming: the phase timers were complete (S-M1); otherwise the phases are ignored.
	// deferModeWaits: Sodium's Chunk Updates mode makes frames wait for builds (ZERO_FRAMES/ONE_FRAME). chunkLoading: the
	// spans in which the client loaded chunks (review-8 P5A-F2).
	public record Context(List<GcEvent> gc, List<Interval> saves, List<Interval> afterTeleport, List<Interval> movingFast, List<Sample> samples,
			int cores, boolean phaseTiming, boolean deferModeWaits, List<Interval> chunkLoading) {
		public Context(List<GcEvent> gc, List<Interval> saves, List<Interval> afterTeleport, List<Interval> movingFast, List<Sample> samples, int cores,
				boolean phaseTiming, boolean deferModeWaits) {
			this(gc, saves, afterTeleport, movingFast, samples, cores, phaseTiming, deferModeWaits, List.of());
		}

		public static Context empty() {
			return new Context(List.of(), List.of(), List.of(), List.of(), List.of(), 1, false, false);
		}
	}

	// claims: cause -> ns (never over the lost time); notes: "cause:confidence[:FLAG...]" in claim order, then tags;
	// tags: the correlational tags; unexplained: lost time nothing claimed.
	public record Attribution(SpikeDetector.Spike spike, Map<String, Long> claims, List<String> notes, Set<String> tags, long unexplained) {
		public long claimed() {
			return spike.lost() - unexplained;
		}
	}

	private Attributor() {
	}

	public static Attribution attribute(SpikeDetector.Spike spike, @Nullable Phases phases, Context ctx) {
		long e = Math.max(0, spike.lost());
		long from = spike.start();
		long to = spike.end();
		Map<String, Long> claims = new LinkedHashMap<>();
		List<String> notes = new ArrayList<>();
		Set<String> tags = new LinkedHashSet<>();
		long left = e;

		// 1. GC pauses overlapping the frame (measured).
		long gc = 0;
		boolean full = false;
		boolean explicit = false;
		boolean stall = false;
		for (GcEvent event : ctx.gc()) {
			if ((event.flags() & GcKind.STALL_HINT) != 0 && event.start() <= to + STALL_NEAR && event.end() >= from - STALL_NEAR) {
				stall = true;
			}
			if (!GcKind.pause(event.flags())) {
				continue;
			}
			long overlap = Math.min(to, event.end()) - Math.max(from, event.start());
			if (overlap > 0) {
				gc += overlap;
				full |= (event.flags() & GcKind.FULL) != 0;
				explicit |= (event.flags() & GcKind.EXPLICIT) != 0;
			}
		}
		gc = Math.min(gc, left);
		if (gc > 0) {
			claims.put(GC, gc);
			left -= gc;
			notes.add(GC + ":" + share(gc, e).id() + (full ? ":" + FULL : "") + (explicit ? ":" + EXPLICIT : "") + (stall ? ":" + STALL : ""));
		} else if (stall) {
			notes.add(GC + ":" + Confidence.MEDIUM.id() + ":" + STALL);
		}

		boolean measured = ctx.phaseTiming() && phases != null;
		int chunks = phases == null ? 0 : Math.max(0, phases.chunkLoads()) + Math.max(0, phases.previousChunkLoads());
		if (measured) {
			// 2. Chunk packets (measured).
			long packets = Math.min(left, Math.max(0, phases.packets() - phases.packetsBase()));
			if (packets > MIN_PACKETS && chunks > 0) {
				claims.put(CHUNK_LOAD, packets);
				left -= packets;
				notes.add(CHUNK_LOAD + ":" + (2 * packets >= e ? Confidence.HIGH : Confidence.MEDIUM).id());
			}
			// 3. Client ticks (measured).
			long ticks = Math.max(0, phases.ticks() - phases.ticksBase());
			if (2 * ticks >= e && left > 0) {
				long claim = Math.min(left, ticks);
				claims.put(TICK, claim);
				left -= claim;
				notes.add(TICK + ":" + Confidence.MEDIUM.id());
			}
			// 4. The render phase: chunk building with evidence, otherwise unexplained.
			long render = Math.max(0, phases.render() - phases.renderBase());
			if (2 * render > e && left > 0) {
				if (ctx.deferModeWaits() || backlog(ctx, from, to)) {
					long claim = Math.min(left, render);
					claims.put(CHUNK_BUILD, claim);
					left -= claim;
					notes.add(CHUNK_BUILD + ":" + Confidence.MEDIUM.id());
				} else {
					notes.add(RENDER + ":" + Confidence.LOW.id());
				}
			}
		} else if (chunks >= CHUNK_BURST) {
			notes.add(CHUNK_LOAD + ":" + Confidence.LOW.id());
		}

		// Tags (correlational; never claim).
		for (Interval save : ctx.saves()) {
			if (save.overlaps(from - SAVE_NEAR, to + SAVE_NEAR)) {
				tags.add(WORLD_SAVE);
				notes.add(WORLD_SAVE + ":" + Confidence.LOW.id());
				break;
			}
		}
		Sample busiest = null;
		for (Sample s : ctx.samples()) {
			if (s.start() <= to + SAMPLE_NEAR && s.end() >= from - SAMPLE_NEAR && (busiest == null || s.processCores() > busiest.processCores())) {
				busiest = s;
			}
		}
		if (busiest != null) {
			boolean contended = busiest.processCores() >= 0.85 * ctx.cores();
			if (busiest.dhCores() >= 1.0 && contended) {
				tags.add(DH);
				notes.add(DH + ":" + (DH.equals(busiest.topGroup()) ? Confidence.MEDIUM : Confidence.LOW).id());
			} else if (busiest.processCores() >= 0.9 * ctx.cores()) {
				tags.add(CPU_CONTENTION);
				notes.add(CPU_CONTENTION + ":" + Confidence.LOW.id() + ":" + busiest.topGroup());
			}
		}
		for (Interval t : ctx.afterTeleport()) {
			if (t.overlaps(from, to)) {
				tags.add(AFTER_TELEPORT);
				notes.add(AFTER_TELEPORT + ":" + CONTEXT);
				break;
			}
		}
		for (Interval c : ctx.chunkLoading()) {
			if (c.overlaps(from - CHUNK_NEAR, to + CHUNK_NEAR)) {
				tags.add(CHUNKS_LOADING);
				// A chunk-loading claim or note already says so.
				if (notes.stream().noneMatch(n -> n.startsWith(CHUNK_LOAD + ":"))) {
					notes.add(CHUNKS_LOADING + ":" + CONTEXT);
				}
				break;
			}
		}
		for (Interval f : ctx.movingFast()) {
			if (f.overlaps(from, to)) {
				tags.add(MOVING_FAST);
				notes.add(MOVING_FAST + ":" + CONTEXT);
				break;
			}
		}
		return new Attribution(spike, claims, notes, tags, left);
	}

	private static boolean backlog(Context ctx, long from, long to) {
		for (Sample s : ctx.samples()) {
			if (s.backlog() && s.start() <= to + SAMPLE_NEAR && s.end() >= from - SAMPLE_NEAR) {
				return true;
			}
		}
		return false;
	}

	private static Confidence share(long claimed, long lost) {
		if (2 * claimed >= lost) {
			return Confidence.HIGH;
		}
		return 5 * claimed >= lost ? Confidence.MEDIUM : Confidence.LOW;
	}
}
