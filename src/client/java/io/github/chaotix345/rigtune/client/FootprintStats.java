package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// RigTune's own startup cost (docs/v0.4/SPEC.md 10), measured where it happens so the footprint game test can read it
// whenever it runs: wall and render-thread CPU time of RigTunePreLaunch, onInitializeClient and the CLIENT_STARTED
// handler, and the CPU every "RigTune..." thread used in the first WINDOW_MILLIS after CLIENT_STARTED. One INFO line
// when the window closes. No Minecraft types here: this class loads during preLaunch.
public final class FootprintStats {
	public static final String THREAD_PREFIX = "RigTune";
	public static final long WINDOW_MILLIS = 5_000;
	public static final long UNSET = -1;

	private static volatile long preLaunchWallNs = UNSET;
	private static volatile long preLaunchCpuNs = UNSET;
	private static volatile long initWallNs = UNSET;
	private static volatile long initCpuNs = UNSET;
	private static volatile long clientStartedWallNs = UNSET;
	private static volatile long clientStartedCpuNs = UNSET;
	private static volatile long mxInitNs = UNSET;
	private static volatile @Nullable Map<String, Long> windowCpuNs;
	// The CPU clock at each start: preLaunch and init both run on the main thread, one after the other.
	private static long preLaunchCpuStart;
	private static long initCpuStart;
	private static @Nullable Map<Long, Long> windowBaseline;
	private static @Nullable ThreadMXBean threads;

	private FootprintStats() {
	}

	// Everything measured so far; UNSET (or null) for what hasn't happened (or can't be measured on this JVM).
	public record Snapshot(long preLaunchWallNs, long preLaunchCpuNs, long initWallNs, long initCpuNs, long clientStartedWallNs,
			long clientStartedCpuNs, long mxInitNs, @Nullable Map<String, Long> windowCpuNs) {
		public @Nullable Long windowCpuTotalNs() {
			return windowCpuNs == null ? null : windowCpuNs.values().stream().mapToLong(Long::longValue).sum();
		}
	}

	public static Snapshot snapshot() {
		return new Snapshot(preLaunchWallNs, preLaunchCpuNs, initWallNs, initCpuNs, clientStartedWallNs, clientStartedCpuNs, mxInitNs,
				windowCpuNs);
	}

	public static long preLaunchStart() {
		preLaunchCpuStart = cpu();
		return System.nanoTime();
	}

	// Only the launch's own call counts: a game test calls onPreLaunch() again later (with the apply lock held).
	public static void preLaunchEnd(long start) {
		if (preLaunchWallNs == UNSET) {
			preLaunchWallNs = System.nanoTime() - start;
			preLaunchCpuNs = since(preLaunchCpuStart);
		}
	}

	public static long initStart() {
		initCpuStart = cpu();
		return System.nanoTime();
	}

	public static void initEnd(long start) {
		if (initWallNs == UNSET) {
			initWallNs = System.nanoTime() - start;
			initCpuNs = since(initCpuStart);
		}
	}

	// The CLIENT_STARTED handler: opens the worker window (closed on another thread WINDOW_MILLIS later), then times start.
	public static void clientStarted(Runnable start) {
		try {
			windowBaseline = rigTuneThreadCpu();
			CompletableFuture.delayedExecutor(WINDOW_MILLIS, TimeUnit.MILLISECONDS).execute(FootprintStats::closeWindow);
		} catch (RuntimeException e) {
			RigTune.LOGGER.debug("RigTune's startup footprint window is off", e);
		}
		long cpuStart = cpu();
		long wallStart = System.nanoTime();
		try {
			start.run();
		} finally {
			clientStartedWallNs = System.nanoTime() - wallStart;
			clientStartedCpuNs = since(cpuStart);
		}
	}

	private static void closeWindow() {
		Map<Long, Long> baseline = windowBaseline;
		Map<String, Long> byName = new TreeMap<>();
		ThreadMXBean mx = threads();
		if (mx == null || baseline == null) {
			return;
		}
		long[] ids = mx.getAllThreadIds();
		ThreadInfo[] infos = mx.getThreadInfo(ids);
		for (int i = 0; i < ids.length; i++) {
			if (infos[i] == null || !infos[i].getThreadName().startsWith(THREAD_PREFIX)) {
				continue;
			}
			long cpu = mx.getThreadCpuTime(ids[i]);
			if (cpu >= 0) {
				byName.merge(infos[i].getThreadName(), Math.max(0, cpu - baseline.getOrDefault(ids[i], 0L)), Long::sum);
			}
		}
		windowCpuNs = Map.copyOf(byName);
		Snapshot s = snapshot();
		RigTune.LOGGER.info("RigTune startup footprint: preLaunch + init {} ms on the render thread (CPU {} ms), client start {} ms; "
						+ "RigTune threads used {} ms of CPU in the first {} s",
				ms(add(s.preLaunchWallNs(), s.initWallNs())), ms(add(s.preLaunchCpuNs(), s.initCpuNs())), ms(s.clientStartedWallNs()),
				ms(s.windowCpuTotalNs()), WINDOW_MILLIS / 1000);
	}

	// Thread id -> CPU nanoseconds for every live "RigTune..." thread.
	private static Map<Long, Long> rigTuneThreadCpu() {
		Map<Long, Long> out = new HashMap<>();
		ThreadMXBean mx = threads();
		if (mx == null) {
			return out;
		}
		long[] ids = mx.getAllThreadIds();
		ThreadInfo[] infos = mx.getThreadInfo(ids);
		for (int i = 0; i < ids.length; i++) {
			if (infos[i] != null && infos[i].getThreadName().startsWith(THREAD_PREFIX)) {
				long cpu = mx.getThreadCpuTime(ids[i]);
				if (cpu >= 0) {
					out.put(ids[i], cpu);
				}
			}
		}
		return out;
	}

	private static long cpu() {
		ThreadMXBean mx = threads();
		return mx == null ? UNSET : mx.getCurrentThreadCpuTime();
	}

	private static long since(long cpuStart) {
		long now = cpu();
		return cpuStart < 0 || now < 0 ? UNSET : now - cpuStart;
	}

	// The first call's cost (loading the management classes, if nothing else has yet) is kept apart in mxInitNs.
	private static synchronized @Nullable ThreadMXBean threads() {
		if (threads == null && mxInitNs == UNSET) {
			long start = System.nanoTime();
			try {
				ThreadMXBean mx = ManagementFactory.getThreadMXBean();
				if (mx.isThreadCpuTimeSupported() && mx.isThreadCpuTimeEnabled()) {
					threads = mx;
				}
			} catch (RuntimeException | LinkageError e) {
				RigTune.LOGGER.debug("No thread CPU times on this JVM", e);
			}
			mxInitNs = System.nanoTime() - start;
		}
		return threads;
	}

	private static @Nullable Long add(long a, long b) {
		return a < 0 || b < 0 ? null : a + b;
	}

	private static String ms(@Nullable Long nanos) {
		return nanos == null || nanos < 0 ? "?" : String.format(Locale.ROOT, "%.1f", nanos / 1e6);
	}
}
