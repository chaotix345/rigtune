package io.github.chaotix345.rigtune.core.stutter;

// The render thread's per-frame capture (docs/research/v0.4/stutter.md §2.3, §2.5): allocated once when capture starts,
// never grown, and allocation-free per frame (FrameRingAllocationTest). Render thread only: no locking.
// - A ring of frame-end nanos (bit 0 set = an excluded frame: a menu, an unfocused window, world loading, the frame
//   after a pause). Durations are the differences between neighbouring ends.
// - The 9-bucket frame-time histogram of gameplay frames (counts and time), for the whole capture.
// - Candidates: gameplay frames of at least 20 ms and 1.5 x the running baseline (a clamped EWMA), with the frame's
//   phase times, the phases' own baselines and the chunk loads of this and the previous frame, so attribution works for
//   frames older than the frame ring too.
// - Per frame, next to its end (review-8 ST-2): the frame's phase excess over the running baselines (packets, ticks,
//   render; 9 bits each, rounded down, so never more than measured) and its chunk loads (5 bits, saturating), so a spike
//   still in the frame ring keeps its evidence however many candidates came after it.
// - The baselines re-warm (reseed, then a faster EWMA for REWARM_FRAMES frames) after an excluded span or a pause of at
//   least REWARM_NANOS (review-8 ST-3): after world loading they follow the new place, not the one before.
public final class FrameRing {
	public static final int SESSION_FRAMES = 1 << 17;
	public static final int SESSION_CANDIDATES = 4096;
	public static final int BENCHMARK_FRAMES = 1 << 15;
	public static final int BENCHMARK_CANDIDATES = 1024;
	public static final long MS = 1_000_000L;
	public static final long CANDIDATE_MIN = 20 * MS;
	public static final long REWARM_NANOS = 1_000 * MS;
	static final int REWARM_FRAMES = 16;
	// The per-frame phase word: packets, ticks and render excess (9-bit codes of 64 ns units: a 5-bit exponent and a 4-bit
	// mantissa) and the chunk loads (5 bits, up to 31).
	private static final int PHASE_BITS = 9;
	private static final int PHASE_MASK = (1 << PHASE_BITS) - 1;
	private static final int CHUNK_SHIFT = 3 * PHASE_BITS;
	public static final int MAX_FRAME_CHUNKS = 31;

	// Candidate record layout.
	public static final int STRIDE = 10;
	public static final int C_END = 0;
	public static final int C_DURATION = 1;
	public static final int C_BASELINE = 2;
	public static final int C_PACKETS = 3;
	public static final int C_TICKS = 4;
	public static final int C_RENDER = 5;
	public static final int C_PACKETS_BASE = 6;
	public static final int C_TICKS_BASE = 7;
	public static final int C_RENDER_BASE = 8;
	// This frame's chunk loads in the low 32 bits, the previous frame's in the high 32.
	public static final int C_CHUNKS = 9;

	// Upper edges of the display buckets: < 4.2 · 4.2-8.3 · 8.3-16.7 · 16.7-33 · 33-50 · 50-100 · 100-250 · 250-1000 · ≥ 1000 ms.
	public static final long[] EDGES = {4_166_667L, 8_333_333L, 16_666_667L, 33_333_333L, 50 * MS, 100 * MS, 250 * MS, 1000 * MS};
	public static final int BUCKETS = EDGES.length + 1;

	private final long[] ends;
	private final int[] phases;
	private final int mask;
	private long frames;
	private final long[] candidates;
	private final int candidateCapacity;
	private long candidateCount;
	private final long[] histogramCounts = new long[BUCKETS];
	private final long[] histogramNanos = new long[BUCKETS];
	private long gameplayFrames;
	private long gameplayNanos;
	private long excludedFrames;
	private long baseline;
	private long packetsBase = -1;
	private long ticksBase = -1;
	private long renderBase = -1;
	private int lastChunkLoads;
	private long lastGameplayEnd;
	private boolean afterExcluded;
	private int warm;

	public FrameRing(int frameCapacity, int candidateCapacity) {
		if (Integer.bitCount(frameCapacity) != 1 || candidateCapacity <= 0) {
			throw new IllegalArgumentException("frame capacity must be a power of two");
		}
		this.ends = new long[frameCapacity];
		this.phases = new int[frameCapacity];
		this.mask = frameCapacity - 1;
		this.candidates = new long[candidateCapacity * STRIDE];
		this.candidateCapacity = candidateCapacity;
	}

	// now: System.nanoTime() at the frame's end; duration: the game's own frame-to-frame time. The phases are this
	// frame's (0 when phase timing is off).
	public void frame(long now, long duration, boolean excluded, long packets, long ticks, long render, int chunkLoads) {
		int slot = (int) (frames++ & mask);
		ends[slot] = excluded ? now | 1L : now & ~1L;
		int chunks = Math.min(MAX_FRAME_CHUNKS, Math.max(0, chunkLoads)) << CHUNK_SHIFT;
		if (excluded) {
			phases[slot] = chunks;
			excludedFrames++;
			lastChunkLoads = chunkLoads;
			afterExcluded = true;
			return;
		}
		if (afterExcluded && lastGameplayEnd != 0 && now - lastGameplayEnd >= REWARM_NANOS) {
			baseline = 0;
			packetsBase = -1;
			ticksBase = -1;
			renderBase = -1;
			warm = 0;
		}
		afterExcluded = false;
		lastGameplayEnd = now;
		phases[slot] = chunks | encodePhase(excess(packets, packetsBase)) | encodePhase(excess(ticks, ticksBase)) << PHASE_BITS
				| encodePhase(excess(render, renderBase)) << (2 * PHASE_BITS);
		int bucket = bucket(duration);
		histogramCounts[bucket]++;
		histogramNanos[bucket] += duration;
		gameplayFrames++;
		gameplayNanos += duration;
		long b = baseline;
		if (b == 0) {
			b = duration;
		}
		if (duration >= CANDIDATE_MIN && duration >= b + (b >> 1)) {
			int at = (int) (candidateCount++ % candidateCapacity) * STRIDE;
			candidates[at + C_END] = now;
			candidates[at + C_DURATION] = duration;
			candidates[at + C_BASELINE] = b;
			candidates[at + C_PACKETS] = packets;
			candidates[at + C_TICKS] = ticks;
			candidates[at + C_RENDER] = render;
			candidates[at + C_PACKETS_BASE] = Math.max(0, packetsBase);
			candidates[at + C_TICKS_BASE] = Math.max(0, ticksBase);
			candidates[at + C_RENDER_BASE] = Math.max(0, renderBase);
			candidates[at + C_CHUNKS] = ((long) lastChunkLoads << 32) | (chunkLoads & 0xFFFFFFFFL);
		}
		// Warming up: alpha 1/4 for the first frames after a (re)start, then 1/32.
		int shift = 5;
		if (warm < REWARM_FRAMES) {
			warm++;
			shift = 2;
		}
		baseline = b + ((Math.min(duration, 2 * b) - b) >> shift);
		packetsBase = ewma(packetsBase, packets, shift);
		ticksBase = ewma(ticksBase, ticks, shift);
		renderBase = ewma(renderBase, render, shift);
		lastChunkLoads = chunkLoads;
	}

	// A frame's phase time over its baseline; nothing before the phase has a baseline.
	private static long excess(long value, long base) {
		return base < 0 ? 0 : Math.max(0, value - base);
	}

	// ns -> a 9-bit code of 64 ns units: exact below 1024 ns, else 5 significant bits (rounded down: at most 1/16 under).
	public static int encodePhase(long nanos) {
		if (nanos <= 0) {
			return 0;
		}
		long u = nanos >>> 6;
		if (u < 16) {
			return (int) u;
		}
		int n = 63 - Long.numberOfLeadingZeros(u);
		int e = n - 3;
		if (e > 31) {
			return PHASE_MASK;
		}
		return (e << 4) | (int) ((u >>> (n - 4)) & 15);
	}

	public static long decodePhase(int code) {
		int c = code & PHASE_MASK;
		if (c < 16) {
			return (long) c << 6;
		}
		return ((16L | (c & 15)) << ((c >>> 4) - 1)) << 6;
	}

	public static long packetsExcess(int framePhase) {
		return decodePhase(framePhase);
	}

	public static long ticksExcess(int framePhase) {
		return decodePhase(framePhase >>> PHASE_BITS);
	}

	public static long renderExcess(int framePhase) {
		return decodePhase(framePhase >>> (2 * PHASE_BITS));
	}

	public static int chunkLoads(int framePhase) {
		return framePhase >>> CHUNK_SHIFT;
	}

	// A clamped EWMA (alpha 1/2^shift) that can rise from 0: one outlier moves it by at most (2 base + 1 ms) / 2^shift.
	private static long ewma(long base, long value, int shift) {
		if (base < 0) {
			return value;
		}
		return base + ((Math.min(value, 2 * base + MS) - base) >> shift);
	}

	public static int bucket(long duration) {
		for (int i = 0; i < EDGES.length; i++) {
			if (duration < EDGES[i]) {
				return i;
			}
		}
		return EDGES.length;
	}

	public long frames() {
		return frames;
	}

	public long gameplayFrames() {
		return gameplayFrames;
	}

	public long retainedBytes() {
		return (ends.length + candidates.length + histogramCounts.length + histogramNanos.length) * 8L + phases.length * 4L;
	}

	public Snapshot snapshot() {
		int held = (int) Math.min(frames, ends.length);
		long[] out = new long[held];
		int[] framePhases = new int[held];
		long first = frames - held;
		for (int i = 0; i < held; i++) {
			int slot = (int) ((first + i) & mask);
			out[i] = ends[slot];
			framePhases[i] = phases[slot];
		}
		int heldCandidates = (int) Math.min(candidateCount, candidateCapacity);
		long[] cands = new long[heldCandidates * STRIDE];
		long firstCandidate = candidateCount - heldCandidates;
		for (int i = 0; i < heldCandidates; i++) {
			System.arraycopy(candidates, (int) ((firstCandidate + i) % candidateCapacity) * STRIDE, cands, i * STRIDE, STRIDE);
		}
		return new Snapshot(out, cands, histogramCounts.clone(), histogramNanos.clone(), frames, gameplayFrames, gameplayNanos, excludedFrames,
				candidateCount, new long[]{Math.max(0, packetsBase), Math.max(0, ticksBase), Math.max(0, renderBase)}, framePhases);
	}

	// ends: chronological (bit 0 = excluded); candidates: chronological records of STRIDE longs; the histogram and
	// counters cover the whole capture, even what the rings no longer hold. phaseBaselines: the running packets, ticks and
	// render baselines (ns) at the snapshot, for the logs. framePhases: each held frame's phase word (packetsExcess,
	// ticksExcess, renderExcess, chunkLoads), aligned with ends; empty when unknown.
	public record Snapshot(long[] ends, long[] candidates, long[] histogramCounts, long[] histogramNanos, long frames, long gameplayFrames,
			long gameplayNanos, long excludedFrames, long candidateCount, long[] phaseBaselines, int[] framePhases) {
		public static final Snapshot EMPTY = new Snapshot(new long[0], new long[0], new long[BUCKETS], new long[BUCKETS], 0, 0, 0, 0, 0, new long[3]);

		public Snapshot(long[] ends, long[] candidates, long[] histogramCounts, long[] histogramNanos, long frames, long gameplayFrames,
				long gameplayNanos, long excludedFrames, long candidateCount, long[] phaseBaselines) {
			this(ends, candidates, histogramCounts, histogramNanos, frames, gameplayFrames, gameplayNanos, excludedFrames, candidateCount, phaseBaselines,
					new int[0]);
		}

		// Whether framePhases lines up with ends (every held frame has its phase word).
		public boolean hasFramePhases() {
			return ends.length > 0 && framePhases.length == ends.length;
		}

		public int candidateRecords() {
			return candidates.length / STRIDE;
		}

		public long candidate(int record, int field) {
			return candidates[record * STRIDE + field];
		}

		public static boolean excluded(long end) {
			return (end & 1L) != 0;
		}
	}
}
