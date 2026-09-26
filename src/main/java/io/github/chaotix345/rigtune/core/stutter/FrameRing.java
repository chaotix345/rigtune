package io.github.chaotix345.rigtune.core.stutter;

// The render thread's per-frame capture (docs/research/v0.4/stutter.md §2.3, §2.5): allocated once when capture starts,
// never grown, and allocation-free per frame (FrameRingAllocationTest). Render thread only: no locking.
// - A ring of frame-end nanos (bit 0 set = an excluded frame: a menu, an unfocused window, world loading, the frame
//   after a pause). Durations are the differences between neighbouring ends.
// - The 9-bucket frame-time histogram of gameplay frames (counts and time), for the whole capture.
// - Candidates: gameplay frames of at least 20 ms and 1.5 x the running baseline (a clamped EWMA), with the frame's
//   phase times, the phases' own baselines and the chunk loads of this and the previous frame, so attribution works for
//   frames older than the frame ring too.
public final class FrameRing {
	public static final int SESSION_FRAMES = 1 << 17;
	public static final int SESSION_CANDIDATES = 4096;
	public static final int BENCHMARK_FRAMES = 1 << 15;
	public static final int BENCHMARK_CANDIDATES = 1024;
	public static final long MS = 1_000_000L;
	public static final long CANDIDATE_MIN = 20 * MS;

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

	public FrameRing(int frameCapacity, int candidateCapacity) {
		if (Integer.bitCount(frameCapacity) != 1 || candidateCapacity <= 0) {
			throw new IllegalArgumentException("frame capacity must be a power of two");
		}
		this.ends = new long[frameCapacity];
		this.mask = frameCapacity - 1;
		this.candidates = new long[candidateCapacity * STRIDE];
		this.candidateCapacity = candidateCapacity;
	}

	// now: System.nanoTime() at the frame's end; duration: the game's own frame-to-frame time. The phases are this
	// frame's (0 when phase timing is off).
	public void frame(long now, long duration, boolean excluded, long packets, long ticks, long render, int chunkLoads) {
		ends[(int) (frames++ & mask)] = excluded ? now | 1L : now & ~1L;
		if (excluded) {
			excludedFrames++;
			lastChunkLoads = chunkLoads;
			return;
		}
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
		baseline = b + ((Math.min(duration, 2 * b) - b) >> 5);
		packetsBase = ewma(packetsBase, packets);
		ticksBase = ewma(ticksBase, ticks);
		renderBase = ewma(renderBase, render);
		lastChunkLoads = chunkLoads;
	}

	// A clamped EWMA (alpha 1/32) that can rise from 0: one outlier moves it by at most (2 base + 1 ms) / 32.
	private static long ewma(long base, long value) {
		if (base < 0) {
			return value;
		}
		return base + ((Math.min(value, 2 * base + MS) - base) >> 5);
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
		return (ends.length + candidates.length + histogramCounts.length + histogramNanos.length) * 8L;
	}

	public Snapshot snapshot() {
		int held = (int) Math.min(frames, ends.length);
		long[] out = new long[held];
		long first = frames - held;
		for (int i = 0; i < held; i++) {
			out[i] = ends[(int) ((first + i) & mask)];
		}
		int heldCandidates = (int) Math.min(candidateCount, candidateCapacity);
		long[] cands = new long[heldCandidates * STRIDE];
		long firstCandidate = candidateCount - heldCandidates;
		for (int i = 0; i < heldCandidates; i++) {
			System.arraycopy(candidates, (int) ((firstCandidate + i) % candidateCapacity) * STRIDE, cands, i * STRIDE, STRIDE);
		}
		return new Snapshot(out, cands, histogramCounts.clone(), histogramNanos.clone(), frames, gameplayFrames, gameplayNanos, excludedFrames,
				candidateCount, new long[]{Math.max(0, packetsBase), Math.max(0, ticksBase), Math.max(0, renderBase)});
	}

	// ends: chronological (bit 0 = excluded); candidates: chronological records of STRIDE longs; the histogram and
	// counters cover the whole capture, even what the rings no longer hold. phaseBaselines: the running packets, ticks and
	// render baselines (ns) at the snapshot, for the logs.
	public record Snapshot(long[] ends, long[] candidates, long[] histogramCounts, long[] histogramNanos, long frames, long gameplayFrames,
			long gameplayNanos, long excludedFrames, long candidateCount, long[] phaseBaselines) {
		public static final Snapshot EMPTY = new Snapshot(new long[0], new long[0], new long[BUCKETS], new long[BUCKETS], 0, 0, 0, 0, 0, new long[3]);

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
