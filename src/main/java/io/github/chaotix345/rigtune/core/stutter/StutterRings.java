package io.github.chaotix345.rigtune.core.stutter;

// The capture rings that aren't per frame, shared by the session monitor and the benchmark's capture while either is on
// (docs/research/v0.4/stutter.md §2.5): events (server thread saves, render thread level changes/teleports/movement/
// pauses), GC notifications (Notification Thread, with the GcClock) and the 4 Hz thread-CPU samples. Each record is a
// few longs; every write goes through the ring's lock and allocates nothing.
public final class StutterRings {
	public static final int EVENT_CAPACITY = 4096;
	public static final int GC_CAPACITY = 2048;
	public static final int SAMPLE_CAPACITY = 4096;

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
	public static final int SAMPLE_STRIDE = 13;
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
	public static final String[] GROUPS = {"render", "server", "worker", "io", "builder", "dh", "other"};

	private final RecordRing events = new RecordRing(EVENT_CAPACITY, EVENT_STRIDE);
	private final RecordRing gc = new RecordRing(GC_CAPACITY, GC_STRIDE);
	private final RecordRing samples = new RecordRing(SAMPLE_CAPACITY, SAMPLE_STRIDE);
	private final GcClock clock;
	private final long[] gcRecord = new long[GC_STRIDE];

	public StutterRings(long nanosAtUptimeZero) {
		this.clock = new GcClock(nanosAtUptimeZero);
	}

	public void event(int kind, long nanos, long value) {
		events.add(kind, nanos, value);
	}

	public synchronized void gc(long receivedNanos, long startMs, long endMs, int flags, long usedAfterBytes) {
		clock.observe(receivedNanos, endMs);
		gcRecord[G_RECEIVED] = receivedNanos;
		gcRecord[G_START_MS] = startMs;
		gcRecord[G_END_MS] = endMs;
		gcRecord[G_FLAGS] = flags;
		gcRecord[G_USED_AFTER] = usedAfterBytes;
		gc.add(gcRecord);
	}

	public void sample(long[] record) {
		samples.add(record);
	}

	public long retainedBytes() {
		return events.retainedBytes() + gc.retainedBytes() + samples.retainedBytes();
	}

	public synchronized Snapshot snapshot() {
		return new Snapshot(events.snapshot(), gc.snapshot(), samples.snapshot(), clock.calibration());
	}

	public synchronized GcClock.Calibration calibration() {
		return clock.calibration();
	}

	public record Snapshot(long[] events, long[] gc, long[] samples, GcClock.Calibration clock) {
		public static final Snapshot EMPTY = new Snapshot(new long[0], new long[0], new long[0], new GcClock.Calibration(0, Double.NaN));
	}
}
