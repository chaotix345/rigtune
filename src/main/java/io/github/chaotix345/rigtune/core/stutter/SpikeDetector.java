package io.github.chaotix345.rigtune.core.stutter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Finds spikes in a capture (docs/v0.4/SPEC.md 5; research §4.1), after the fact, off the render thread.
// - A gameplay frame is a spike when d > max(2 b, b + 8 ms, 20 ms), b = the median of the previous 120 non-spike
//   gameplay frames (a clamped EWMA until 30 exist). 2x is a clearly visible hitch, +8 ms keeps 3 -> 7 ms jitter at
//   300 fps out, and 20 ms is just over one 60 Hz frame.
// - Excluded frames (menus, an unfocused window, world loading, the frame after a pause) are never spikes and never
//   enter the baseline.
// - A steady run of STREAK consecutive spikes is a new frame rate, not a hitch: those frames become the baseline and
//   stop counting as spikes (otherwise a lasting drop to under half the frame rate would flag every frame forever).
// - Frames older than the frame ring are judged from the candidate records with their running baseline.
// - Spikes less than 100 ms apart form one hitch.
public final class SpikeDetector {
	public static final long MS = 1_000_000L;
	public static final int WINDOW = 120;
	public static final int MIN_WINDOW = 30;
	public static final int STREAK = 60;
	public static final long FLOOR = 20 * MS;
	public static final long MARGIN = 8 * MS;
	public static final long HITCH_GAP = 100 * MS;

	public enum Severity { MINOR, MAJOR, SEVERE, FREEZE }

	public record Spike(long end, long duration, long baseline) {
		public long start() {
			return end - duration;
		}

		public long lost() {
			return duration - baseline;
		}

		public Severity severity() {
			return severity(duration);
		}

		public static Severity severity(long duration) {
			if (duration < 50 * MS) {
				return Severity.MINOR;
			}
			if (duration < 100 * MS) {
				return Severity.MAJOR;
			}
			return duration < 500 * MS ? Severity.SEVERE : Severity.FREEZE;
		}
	}

	public record Hitch(List<Spike> spikes) {
		public long start() {
			return spikes.getFirst().start();
		}

		public long end() {
			return spikes.getLast().end();
		}

		public long lost() {
			return spikes.stream().mapToLong(Spike::lost).sum();
		}
	}

	private SpikeDetector() {
	}

	public static boolean isSpike(long duration, long baseline) {
		return duration > Math.max(2 * baseline, Math.max(baseline + MARGIN, FLOOR));
	}

	// ends: chronological frame ends, bit 0 = excluded (FrameRing.Snapshot). The first frame has no duration.
	public static List<Spike> detect(long[] ends) {
		List<Spike> spikes = new ArrayList<>();
		Window window = new Window();
		long ewma = 0;
		int streak = 0;
		for (int i = 1; i < ends.length; i++) {
			if (FrameRing.Snapshot.excluded(ends[i])) {
				continue;
			}
			long end = ends[i] & ~1L;
			long d = end - (ends[i - 1] & ~1L);
			if (d <= 0) {
				continue;
			}
			long b = window.size() >= MIN_WINDOW ? window.median() : ewma == 0 ? d : ewma;
			if (isSpike(d, b)) {
				spikes.add(new Spike(end, d, b));
				if (++streak >= STREAK) {
					window.clear();
					for (int k = 0; k < STREAK; k++) {
						window.add(spikes.removeLast().duration());
					}
					streak = 0;
				}
			} else {
				streak = 0;
				window.add(d);
			}
			ewma = ewma == 0 ? d : ewma + ((Math.min(d, 2 * ewma) - ewma) >> 5);
		}
		return spikes;
	}

	// Candidate records (FrameRing.Snapshot.candidates) that ended before `before`: the ones the frame ring no longer
	// holds, judged against the running baseline recorded with them.
	public static List<Spike> fromCandidates(FrameRing.Snapshot snapshot, long before) {
		List<Spike> spikes = new ArrayList<>();
		for (int r = 0; r < snapshot.candidateRecords(); r++) {
			long end = snapshot.candidate(r, FrameRing.C_END) & ~1L;
			long d = snapshot.candidate(r, FrameRing.C_DURATION);
			long b = snapshot.candidate(r, FrameRing.C_BASELINE);
			if (end < before && isSpike(d, b)) {
				spikes.add(new Spike(end, d, b));
			}
		}
		return spikes;
	}

	public static List<Hitch> hitches(List<Spike> spikes) {
		List<Hitch> hitches = new ArrayList<>();
		List<Spike> current = new ArrayList<>();
		for (Spike s : spikes) {
			if (!current.isEmpty() && s.start() - current.getLast().end() >= HITCH_GAP) {
				hitches.add(new Hitch(List.copyOf(current)));
				current.clear();
			}
			current.add(s);
		}
		if (!current.isEmpty()) {
			hitches.add(new Hitch(List.copyOf(current)));
		}
		return hitches;
	}

	// The last WINDOW durations, kept sorted for the median (binary-search insert and delete: O(WINDOW) per frame).
	private static final class Window {
		private final long[] fifo = new long[WINDOW];
		private final long[] sorted = new long[WINDOW];
		private int size;
		private int head;

		int size() {
			return size;
		}

		// The upper median, as the research prototype.
		long median() {
			return sorted[size / 2];
		}

		void clear() {
			size = 0;
			head = 0;
		}

		void add(long d) {
			if (size == WINDOW) {
				remove(fifo[head]);
			}
			fifo[head] = d;
			head = (head + 1) % WINDOW;
			int at = Arrays.binarySearch(sorted, 0, size, d);
			if (at < 0) {
				at = -at - 1;
			}
			System.arraycopy(sorted, at, sorted, at + 1, size - at);
			sorted[at] = d;
			size++;
		}

		private void remove(long d) {
			int n = size;
			int at = Arrays.binarySearch(sorted, 0, n, d);
			System.arraycopy(sorted, at + 1, sorted, at, n - at - 1);
			size--;
		}
	}
}
