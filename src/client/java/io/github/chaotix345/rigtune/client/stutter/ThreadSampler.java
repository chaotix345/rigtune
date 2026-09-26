package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

// The 4 Hz thread-CPU sampler (docs/v0.4/SPEC.md 5; research §3.5), a daemon thread that runs only while a capture is
// on: every 250 ms it groups each Java thread's CPU time by name (Render thread, Server thread, Worker-Main-*,
// IO-Worker-*, Sodium's Chunk Render Task Executor #*, DH-*, other), reads the process's CPU time, and records them with
// the chunk-build backlog the render thread published (BuildBacklog). getThreadInfo with depth 0 needs no safepoint.
// No system-wide CPU load: that JDK call takes over 100 ms on Windows. The first sample of a capture logs the
// thread-name census (for the induced-stutter runs).
// Cost (SPEC 10, 30 ms of CPU per 60 s): a thread's name is read only the first time its id shows up (Tally), and the
// per-thread bookkeeping is primitive, so a steady-state sample allocates only the two arrays the JDK calls return.
final class ThreadSampler implements Runnable {
	static final String THREAD_NAME = "RigTune stutter sampler";
	static final long PERIOD_MS = 250;

	// Where a sample's numbers come from: the JVM's ThreadMXBean in the game, a fake in tests.
	interface Source {
		long[] threadIds();

		// CPU time (ns) per id; -1 for a thread that has ended.
		long[] cpuTimes(long[] ids);

		// The name per id; null for a thread that has ended.
		@Nullable String[] names(long[] ids);

		// The process's CPU time (ns); -1 when unknown.
		long processCpu();
	}

	private volatile @Nullable Thread thread;
	private volatile @Nullable StutterRings rings;

	synchronized void start(StutterRings target) {
		stop();
		rings = target;
		Thread t = new Thread(this, THREAD_NAME);
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		thread = t;
		t.start();
	}

	synchronized void stop() {
		Thread t = thread;
		thread = null;
		rings = null;
		if (t != null) {
			t.interrupt();
			try {
				t.join(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	boolean running() {
		Thread t = thread;
		return t != null && t.isAlive();
	}

	static int group(@Nullable String name) {
		if (name == null) {
			return StutterRings.S_OTHER;
		}
		if (name.equals("Render thread")) {
			return StutterRings.S_RENDER;
		}
		if (name.equals("Server thread")) {
			return StutterRings.S_SERVER;
		}
		if (name.startsWith("Worker-Main-")) {
			return StutterRings.S_WORKER;
		}
		if (name.startsWith("IO-Worker-")) {
			return StutterRings.S_IO;
		}
		if (name.startsWith("Chunk Render Task Executor")) {
			return StutterRings.S_BUILDER;
		}
		return name.startsWith("DH-") ? StutterRings.S_DH : StutterRings.S_OTHER;
	}

	@Override
	public void run() {
		ThreadMXBean threads = ManagementFactory.getThreadMXBean();
		if (!(threads instanceof com.sun.management.ThreadMXBean cpu) || !threads.isThreadCpuTimeSupported() || !threads.isThreadCpuTimeEnabled()) {
			RigTune.LOGGER.warn("Stutter Doctor: thread CPU times unavailable; the sampler is off");
			return;
		}
		com.sun.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean o ? o : null;
		loop(new JvmSource(cpu, os));
	}

	private void loop(Source source) {
		Tally tally = new Tally();
		long[] record = new long[StutterRings.SAMPLE_STRIDE];
		long lastTime = System.nanoTime();
		long lastProcess = source.processCpu();
		try {
			while (thread == Thread.currentThread()) {
				Thread.sleep(PERIOD_MS);
				StutterRings target = rings;
				if (target == null) {
					return;
				}
				long[] ids = source.threadIds();
				long[] times = source.cpuTimes(ids);
				long now = System.nanoTime();
				Arrays.fill(record, 0);
				Map<String, Integer> census = tally.first() ? new TreeMap<>() : null;
				boolean counted = tally.add(ids, times, source, record, census);
				long process = source.processCpu();
				record[StutterRings.S_TIME] = now;
				record[StutterRings.S_WINDOW] = now - lastTime;
				record[StutterRings.S_PROCESS] = process < 0 || lastProcess < 0 ? 0 : Math.max(0, process - lastProcess);
				record[StutterRings.S_BACKLOG] = BuildBacklog.scheduled();
				record[StutterRings.S_BUSY] = BuildBacklog.busy();
				record[StutterRings.S_TOTAL] = BuildBacklog.total();
				if (counted) {
					target.sample(record);
				}
				if (census != null) {
					RigTune.LOGGER.info("Stutter Doctor: {} threads {}", ids.length, census);
				}
				lastTime = now;
				lastProcess = process;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: the sampler stopped", e);
		}
	}

	// Numbered pool threads collapse to "<prefix>*" in the census.
	static String censusName(String name) {
		int end = name.length();
		while (end > 0 && Character.isDigit(name.charAt(end - 1))) {
			end--;
		}
		return end < name.length() ? name.substring(0, end) + "*" : name;
	}

	// Each live thread's CPU since the previous sample, by group. Two open-addressing tables keyed by thread id (the
	// previous sample's and this one's, swapped every sample) hold each thread's CPU time and group, so nothing is boxed.
	// A thread's name (and so its group) is read only the first time its id shows up: thread ids are never reused, and the
	// game's threads are named when they're created. A thread that ended (CPU time -1) is dropped; one seen for the first
	// time counts from 0, as before. The first sample only sets the baseline. Steady state (no new thread, no more threads
	// than the tables hold) allocates nothing.
	static final class Tally {
		private static final long EMPTY = Long.MIN_VALUE;
		private static final int ENDED = -1;

		private long[] keys;
		private long[] cpu;
		private byte[] groups;
		private long[] nextKeys;
		private long[] nextCpu;
		private byte[] nextGroups;
		private int[] slots = new int[128];
		private long[] fresh = new long[128];
		private boolean first = true;

		Tally() {
			this(256);
		}

		Tally(int capacity) {
			int size = Integer.highestOneBit(Math.max(4, capacity - 1)) << 1;
			keys = empty(size);
			cpu = new long[size];
			groups = new byte[size];
			nextKeys = empty(size);
			nextCpu = new long[size];
			nextGroups = new byte[size];
		}

		boolean first() {
			return first;
		}

		int capacity() {
			return keys.length;
		}

		// Adds each live thread's CPU since the previous sample to record[its group] and returns true, except for the first
		// sample (false). census, when given, counts the new threads' names by censusName.
		boolean add(long[] ids, long[] times, Source source, long[] record, @Nullable Map<String, Integer> census) {
			int n = ids.length;
			if (2 * n > keys.length) {
				grow(2 * n);
			}
			if (slots.length < n) {
				slots = new int[2 * n];
			}
			// First pass: each id's slot in the previous sample's table, or its index among the new ids (as -2 - index).
			int freshCount = 0;
			for (int i = 0; i < n; i++) {
				if (times[i] < 0) {
					slots[i] = ENDED;
					continue;
				}
				int slot = find(keys, ids[i]);
				if (slot < 0) {
					if (freshCount == fresh.length) {
						fresh = Arrays.copyOf(fresh, 2 * freshCount);
					}
					slot = -2 - freshCount;
					fresh[freshCount++] = ids[i];
				}
				slots[i] = slot;
			}
			@Nullable String[] names = freshCount == 0 ? null : source.names(Arrays.copyOf(fresh, freshCount));
			boolean counting = !first;
			for (int i = 0; i < n; i++) {
				int slot = slots[i];
				if (slot == ENDED) {
					continue;
				}
				int group;
				long before;
				if (slot >= 0) {
					group = groups[slot];
					before = cpu[slot];
				} else {
					String name = names[-2 - slot];
					if (name == null) {
						continue;
					}
					group = group(name);
					before = 0;
					if (census != null) {
						census.merge(censusName(name), 1, Integer::sum);
					}
				}
				if (counting) {
					record[group] += Math.max(0, times[i] - before);
				}
				put(nextKeys, nextCpu, nextGroups, ids[i], times[i], (byte) group);
			}
			long[] k = keys;
			keys = nextKeys;
			nextKeys = k;
			long[] c = cpu;
			cpu = nextCpu;
			nextCpu = c;
			byte[] g = groups;
			groups = nextGroups;
			nextGroups = g;
			Arrays.fill(nextKeys, EMPTY);
			first = false;
			return counting;
		}

		// Bigger tables (more threads than ever before); the previous sample's entries move over.
		private void grow(int atLeast) {
			int size = Integer.highestOneBit(atLeast - 1) << 1;
			long[] oldKeys = keys;
			long[] oldCpu = cpu;
			byte[] oldGroups = groups;
			keys = empty(size);
			cpu = new long[size];
			groups = new byte[size];
			for (int i = 0; i < oldKeys.length; i++) {
				if (oldKeys[i] != EMPTY) {
					put(keys, cpu, groups, oldKeys[i], oldCpu[i], oldGroups[i]);
				}
			}
			nextKeys = empty(size);
			nextCpu = new long[size];
			nextGroups = new byte[size];
		}

		private static long[] empty(int size) {
			long[] out = new long[size];
			Arrays.fill(out, EMPTY);
			return out;
		}

		private static int hash(long id) {
			long h = id * 0x9E3779B97F4A7C15L;
			return (int) (h ^ (h >>> 32));
		}

		private static int find(long[] keys, long id) {
			int mask = keys.length - 1;
			for (int i = hash(id) & mask; ; i = (i + 1) & mask) {
				long k = keys[i];
				if (k == id) {
					return i;
				}
				if (k == EMPTY) {
					return -1;
				}
			}
		}

		private static void put(long[] keys, long[] cpu, byte[] groups, long id, long time, byte group) {
			int mask = keys.length - 1;
			int i = hash(id) & mask;
			while (keys[i] != EMPTY && keys[i] != id) {
				i = (i + 1) & mask;
			}
			keys[i] = id;
			cpu[i] = time;
			groups[i] = group;
		}
	}

	private record JvmSource(com.sun.management.ThreadMXBean threads, com.sun.management.@Nullable OperatingSystemMXBean os) implements Source {
		@Override
		public long[] threadIds() {
			return threads.getAllThreadIds();
		}

		@Override
		public long[] cpuTimes(long[] ids) {
			return threads.getThreadCpuTime(ids);
		}

		@Override
		public @Nullable String[] names(long[] ids) {
			ThreadInfo[] infos = threads.getThreadInfo(ids, 0);
			@Nullable String[] out = new String[ids.length];
			for (int i = 0; i < ids.length; i++) {
				out[i] = infos[i] == null ? null : infos[i].getThreadName();
			}
			return out;
		}

		@Override
		public long processCpu() {
			return os == null ? -1 : os.getProcessCpuTime();
		}
	}
}
