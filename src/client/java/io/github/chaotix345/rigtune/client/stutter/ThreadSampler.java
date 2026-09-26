package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

// The 4 Hz thread-CPU sampler (docs/v0.4/SPEC.md 5; research §3.5), a daemon thread that runs only while a capture is
// on: every 250 ms it groups each Java thread's CPU time by name (Render thread, Server thread, Worker-Main-*,
// IO-Worker-*, Sodium's Chunk Render Task Executor #*, DH-*, other), reads the process's CPU time, and records them with
// the chunk-build backlog the render thread published (BuildBacklog). getThreadInfo with depth 0 needs no safepoint.
// No system-wide CPU load: that JDK call takes over 100 ms on Windows. The first sample of a capture logs the
// thread-name census (for the induced-stutter runs).
final class ThreadSampler implements Runnable {
	static final String THREAD_NAME = "RigTune stutter sampler";
	static final long PERIOD_MS = 250;

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
		Map<Long, Long> last = new HashMap<>();
		Map<Long, Long> next = new HashMap<>();
		long[] record = new long[StutterRings.SAMPLE_STRIDE];
		long lastTime = System.nanoTime();
		long lastProcess = os == null ? -1 : os.getProcessCpuTime();
		boolean first = true;
		boolean census = false;
		try {
			while (thread == Thread.currentThread()) {
				Thread.sleep(PERIOD_MS);
				StutterRings target = rings;
				if (target == null) {
					return;
				}
				long[] ids = threads.getAllThreadIds();
				long[] times = cpu.getThreadCpuTime(ids);
				ThreadInfo[] infos = threads.getThreadInfo(ids, 0);
				long now = System.nanoTime();
				Arrays.fill(record, 0);
				next.clear();
				Map<String, Integer> names = census ? null : new TreeMap<>();
				for (int i = 0; i < ids.length; i++) {
					if (times[i] < 0 || infos[i] == null) {
						continue;
					}
					String name = infos[i].getThreadName();
					next.put(ids[i], times[i]);
					Long before = last.get(ids[i]);
					if (!first) {
						record[group(name)] += Math.max(0, times[i] - (before == null ? 0 : before));
					}
					if (names != null) {
						names.merge(censusName(name), 1, Integer::sum);
					}
				}
				long process = os == null ? -1 : os.getProcessCpuTime();
				record[StutterRings.S_TIME] = now;
				record[StutterRings.S_WINDOW] = now - lastTime;
				record[StutterRings.S_PROCESS] = process < 0 || lastProcess < 0 ? 0 : Math.max(0, process - lastProcess);
				record[StutterRings.S_BACKLOG] = BuildBacklog.scheduled();
				record[StutterRings.S_BUSY] = BuildBacklog.busy();
				record[StutterRings.S_TOTAL] = BuildBacklog.total();
				if (!first) {
					target.sample(record);
				}
				if (names != null) {
					census = true;
					RigTune.LOGGER.info("Stutter Doctor: {} threads {}", ids.length, names);
				}
				Map<Long, Long> swap = last;
				last = next;
				next = swap;
				lastTime = now;
				lastProcess = process;
				first = false;
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
}
