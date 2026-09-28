package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

// The capture rings that aren't per frame, shared by the session monitor and the benchmark's capture while either is on
// (docs/research/v0.4/stutter.md §2.5): events (server thread saves, render thread level changes/teleports/movement/
// pauses), GC notifications (Notification Thread, with the GcClock) and the 4 Hz thread-CPU samples. Each record is a
// few longs; every write goes through the ring's lock and allocates nothing.
// v0.5 SD-1 (docs/v0.5/SPEC.md 2S): long sessions outlast the GC ring (~35 min of young pauses) and the sample ring
// (~17 min), so the whole capture's full, explicit and stall collections are also counted as they arrive, and the live-set
// samples (major collections, rare) keep their own small ring the young pauses can't push out; the snapshot carries these
// with how many records each ring ever took, so the analysis knows what the rings still cover.
public final class StutterRings {
	public static final int EVENT_CAPACITY = 4096;
	public static final int GC_CAPACITY = 2048;
	public static final int SAMPLE_CAPACITY = 4096;
	public static final int LIVE_CAPACITY = 256;
	// Live-set samples: received nanos, bytes, GC flags.
	public static final int LIVE_STRIDE = 3;

	// Event records: kind, nanoTime, value.
	public static final int EVENT_STRIDE = 3;
	public static final int SAVE_BEGIN = 1;
	public static final int SAVE_END = 2;
	public static final int LEVEL_CHANGE = 3;
	public static final int TELEPORT = 4;
	public static final int FAST_BEGIN = 5;
	public static final int FAST_END = 6;
	public static final int PAUSE_BEGIN = 7;
	public static final int PAUSE_END = 8;
	// v0.5 RW-11: a setting changed or resources reloaded while a session ran; value: the SettingsWatch bits of what changed.
	public static final int SETTINGS_CHANGED = 9;
	// A SETTINGS_CHANGED value's bits from here up: the ms between the event's time (the old value last seen) and the check
	// that saw the change.
	public static final int SETTINGS_LEAD_SHIFT = 16;

	// GC records.
	public static final int GC_STRIDE = 5;
	public static final int G_RECEIVED = 0;
	public static final int G_START_MS = 1;
	public static final int G_END_MS = 2;
	public static final int G_FLAGS = 3;
	// The old generation's usage after a MAJOR collection (all pools when there is no old one), bytes.
	public static final int G_USED_AFTER = 4;

	// Sample records: the thread CPU (ns) each group used in the window ending at S_TIME, the whole process's CPU, and the
	// chunk-build backlog at that moment (Sodium: scheduled jobs, busy and total builder threads; vanilla: the compile
	// queue in S_BACKLOG and -1 in the other two; -1 everywhere when unknown).
	// v0.5 (docs/v0.5/SPEC.md 2S, RW-6): S_DH_WORLD_GEN holds Distant Horizons' world generation threads, apart from the
	// other DH threads in S_DH; it comes last so every older slot keeps its index. As evidence of DH work (the dh tag, the
	// busiest group) the two count together.
	public static final int SAMPLE_STRIDE = 14;
	public static final int S_TIME = 0;
	public static final int S_WINDOW = 1;
	public static final int S_RENDER = 2;
	public static final int S_SERVER = 3;
	public static final int S_WORKER = 4;
	public static final int S_IO = 5;
	public static final int S_BUILDER = 6;
	public static final int S_DH = 7;
	public static final int S_OTHER = 8;
	public static final int S_PROCESS = 9;
	public static final int S_BACKLOG = 10;
	public static final int S_BUSY = 11;
	public static final int S_TOTAL = 12;
	public static final int S_DH_WORLD_GEN = 13;
	public static final String[] GROUPS = {"render", "server", "worker", "io", "builder", "dh", "other"};

	private final RecordRing events = new RecordRing(EVENT_CAPACITY, EVENT_STRIDE);
	private final RecordRing gc = new RecordRing(GC_CAPACITY, GC_STRIDE);
	private final RecordRing samples = new RecordRing(SAMPLE_CAPACITY, SAMPLE_STRIDE);
	private final GcClock clock;
	private final long[] gcRecord = new long[GC_STRIDE];
	private final RecordRing live = new RecordRing(LIVE_CAPACITY, LIVE_STRIDE);
	private int fullGcs;
	private int explicitGcs;
	private int stalls;
	// review-11 STUTTER-4 (C20, WS-B's M4 rule): Distant Horizons' world generation over the whole capture, not only the
	// samples the ring still holds: the busiest DH_BLOCK_NANOS block of sampled time outside pauses, in cores (NaN before
	// one), and the block being filled. The pause state follows this ring's own PAUSE events.
	static final long DH_BLOCK_NANOS = 60_000_000_000L;
	private volatile boolean paused;
	private long dhBlockCpu;
	private long dhBlockTime;
	private double dhPeakCores = Double.NaN;
	// review-12 R12STUTTER-5 (C20): the setting changes over the whole capture (a SETTINGS_CHANGED event with one of the
	// SETTING_BITS: render or simulation distance, shaders, Distant Horizons' rendering; not a resource reload), dated from
	// the capture's start on (an immediate fix's own change, which restarts the session, is dated before it).
	static final long SETTING_BITS = 0xF;
	private volatile boolean countingSettings;
	private volatile long settingsFrom;
	private volatile int settingChanges;

	public StutterRings(long nanosAtUptimeZero) {
		this.clock = new GcClock(nanosAtUptimeZero);
	}

	public void event(int kind, long nanos, long value) {
		events.add(kind, nanos, value);
		if (kind == PAUSE_BEGIN || kind == PAUSE_END) {
			paused = kind == PAUSE_BEGIN;
		} else if (kind == SETTINGS_CHANGED && (value & SETTING_BITS) != 0 && countingSettings && nanos - settingsFrom >= 0) {
			settingChanges++;
		}
	}

	// The capture starts (render thread): setting changes count from `nanos` on.
	public void countSettingChangesFrom(long nanos) {
		settingsFrom = nanos;
		countingSettings = true;
	}

	public synchronized void gc(long receivedNanos, long startMs, long endMs, int flags, long usedAfterBytes) {
		clock.observe(receivedNanos, endMs);
		gcRecord[G_RECEIVED] = receivedNanos;
		gcRecord[G_START_MS] = startMs;
		gcRecord[G_END_MS] = endMs;
		gcRecord[G_FLAGS] = flags;
		gcRecord[G_USED_AFTER] = usedAfterBytes;
		gc.add(gcRecord);
		// The same rules as the analysis's count over the held records (one collection each, phases not counted).
		if (GcKind.collection(flags)) {
			if ((flags & GcKind.FULL) != 0 && (flags & GcKind.EXPLICIT) == 0) {
				fullGcs++;
			}
			if ((flags & GcKind.STALL_HINT) != 0) {
				stalls++;
			}
			if ((flags & GcKind.EXPLICIT) != 0) {
				explicitGcs++;
			}
		}
		if ((flags & GcKind.MAJOR) != 0 && usedAfterBytes > 0) {
			live.add(receivedNanos, usedAfterBytes, flags);
		}
	}

	public void sample(long[] record) {
		samples.add(record);
		if (!paused && record.length > S_DH_WORLD_GEN && record[S_WINDOW] > 0) {
			synchronized (this) {
				dhBlockCpu += Math.max(0, record[S_DH_WORLD_GEN]);
				dhBlockTime += record[S_WINDOW];
				if (dhBlockTime >= DH_BLOCK_NANOS) {
					dhPeakCores = peak(dhPeakCores, dhBlockCpu, dhBlockTime);
					dhBlockCpu = 0;
					dhBlockTime = 0;
				}
			}
		}
	}

	private static double peak(double peak, long cpu, long time) {
		double cores = (double) cpu / time;
		return Double.isNaN(peak) ? cores : Math.max(peak, cores);
	}

	public long retainedBytes() {
		return events.retainedBytes() + gc.retainedBytes() + samples.retainedBytes() + live.retainedBytes();
	}

	public synchronized Snapshot snapshot() {
		// The sampler writes under the samples ring's own lock, so its records and count are read in one call.
		RecordRing.Held held = samples.held();
		return new Snapshot(events.snapshot(), gc.snapshot(), held.records(), clock.calibration(),
				new Totals(gc.added(), held.added(), fullGcs, explicitGcs, stalls, live.snapshot(),
						dhBlockTime >= DH_BLOCK_NANOS / 2 ? peak(dhPeakCores, dhBlockCpu, dhBlockTime) : dhPeakCores, settingChanges));
	}

	public synchronized GcClock.Calibration calibration() {
		return clock.calibration();
	}

	// totals: null in a snapshot built by hand (the analysis then goes by the held records alone).
	public record Snapshot(long[] events, long[] gc, long[] samples, GcClock.Calibration clock, @Nullable Totals totals) {
		public static final Snapshot EMPTY = new Snapshot(new long[0], new long[0], new long[0], new GcClock.Calibration(0, Double.NaN));

		public Snapshot(long[] events, long[] gc, long[] samples, GcClock.Calibration clock) {
			this(events, gc, samples, clock, null);
		}
	}

	// The whole capture's GC counts, how many GC and sample records the rings ever took (more than they hold: they wrapped),
	// and the live-set samples (LIVE_STRIDE longs each, oldest first). dhWorldGenPeakCores (review-11 STUTTER-4): the busiest
	// block of Distant Horizons' world generation (a half-filled last block counts), NaN when no block was sampled.
	// settingChanges (review-12 R12STUTTER-5): the setting changes since the capture's start.
	public record Totals(long gcAdded, long samplesAdded, int fullGcs, int explicitGcs, int stalls, long[] liveSamples, double dhWorldGenPeakCores,
			int settingChanges) {
		public Totals(long gcAdded, long samplesAdded, int fullGcs, int explicitGcs, int stalls, long[] liveSamples, double dhWorldGenPeakCores) {
			this(gcAdded, samplesAdded, fullGcs, explicitGcs, stalls, liveSamples, dhWorldGenPeakCores, 0);
		}

		public Totals(long gcAdded, long samplesAdded, int fullGcs, int explicitGcs, int stalls, long[] liveSamples) {
			this(gcAdded, samplesAdded, fullGcs, explicitGcs, stalls, liveSamples, Double.NaN);
		}
	}
}
