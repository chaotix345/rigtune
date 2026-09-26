package io.github.chaotix345.rigtune.client.probe;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.management.VMOption;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.jvm.JvmArgs;
import io.github.chaotix345.rigtune.core.jvm.JvmFinding;
import io.github.chaotix345.rigtune.core.jvm.JvmFlagClassifier;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.jvm.JvmSnapshot;
import io.github.chaotix345.rigtune.core.jvm.VmOptions;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import org.jspecify.annotations.Nullable;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// JVM and GC advice (docs/v0.4/SPEC.md 6): reads the running JVM once per session on the worker pool, like LauncherProbe,
// and classifies it right away, so the raw arguments (they hold paths with the Windows user name) never leave read()
// and are never logged or shown. Every call is guarded: a JVM without HotSpot's diagnostic bean (OpenJ9) turns the
// check off (no facts), and anything unexpected gives JvmReport.UNAVAILABLE. Each caller waits at most TIMEOUT_MS; a
// probe that finishes later is used by the next rescan.
public final class JvmProbe {
	public static final long TIMEOUT_MS = 3000;
	private static @Nullable CompletableFuture<JvmReport> probe;
	private static @Nullable JvmSnapshot injected;
	private static volatile JvmReport last = JvmReport.UNAVAILABLE;

	private JvmProbe() {
	}

	public static synchronized CompletableFuture<JvmReport> probeAsync() {
		ensureStarted();
		return withTimeout(probe, TIMEOUT_MS);
	}

	// Starts the once-per-session probe if none is running or finished; no waiting.
	public static synchronized void ensureStarted() {
		if (probe == null) {
			JvmSnapshot seam = injected;
			CompletableFuture<JvmReport> started = start(seam != null ? () -> seam : JvmProbe::read, Probes.EXECUTOR);
			probe = started;
			// A probe replaced by reset()/inject() never overwrites its successor's report.
			started.thenAccept(report -> {
				synchronized (JvmProbe.class) {
					if (probe == started) {
						last = report;
					}
				}
			});
		}
	}

	// The latest finished report (UNAVAILABLE until the first probe finishes).
	public static JvmReport current() {
		return last;
	}

	/** Game tests only: the next probe classifies this snapshot instead of the running JVM (null: the real one again). */
	public static synchronized void inject(@Nullable JvmSnapshot snapshot) {
		injected = snapshot;
		reset();
	}

	/** Game tests only: the next probe reads again (until then there's no report, never a stale one). */
	public static synchronized void reset() {
		probe = null;
		last = JvmReport.UNAVAILABLE;
	}

	// The report's facts added to the profile's flags (HardwareProbe's one call).
	public static HardwareProfile withFacts(HardwareProfile hw, JvmReport jvm) {
		if (jvm == null || jvm.facts().isEmpty()) {
			return hw;
		}
		Set<String> flags = new TreeSet<>(hw.flags() == null ? Set.of() : hw.flags());
		flags.addAll(jvm.facts());
		return new HardwareProfile(hw.cpu(), hw.gpu(), hw.totalRamMb(), hw.maxHeapMb(), hw.display(), hw.hasBattery(), hw.onBattery(),
				hw.osName(), hw.mcVersion(), Set.copyOf(flags));
	}

	static CompletableFuture<JvmReport> start(Supplier<JvmSnapshot> read, Executor executor) {
		try {
			return CompletableFuture.supplyAsync(() -> classify(read.get()), executor).exceptionally(t -> {
				RigTune.LOGGER.warn("RigTune: the Java check is off ({})", t.getClass().getSimpleName());
				return failed();
			});
		} catch (RuntimeException e) {
			return CompletableFuture.completedFuture(failed());
		}
	}

	// After a failure the screen says the check is off (not "Checking…" forever): the version and vendor, no facts.
	static JvmReport failed() {
		return new JvmReport(false, javaVersion(), property("java.vendor"), null, false, -1, -1, List.of(), Set.of());
	}

	// "25.0.3": the version numbers without the build and opt parts (Runtime.version() prints "25.0.3+9-LTS").
	static @Nullable String javaVersion() {
		try {
			return String.join(".", Runtime.version().version().stream().map(String::valueOf).toList());
		} catch (Throwable t) {
			return property("java.version");
		}
	}

	private static @Nullable String property(String name) {
		try {
			return System.getProperty(name);
		} catch (Throwable t) {
			return null;
		}
	}

	// Only what the report holds is logged: version, vendor, collector and the flag names RigTune shows.
	private static JvmReport classify(JvmSnapshot snapshot) {
		JvmReport report = JvmFlagClassifier.classify(snapshot);
		if (report.available()) {
			RigTune.LOGGER.info("RigTune: Java {} ({}), {} ({}), {} argument notes {}", report.javaVersion(), report.vendor(),
					report.collectorName() == null ? "unknown collector" : report.collectorName(), report.collectorTyped() ? "typed" : "Java's choice",
					report.findings().size(), report.findings().stream().map(JvmFinding::flag).toList());
		} else {
			RigTune.LOGGER.info("RigTune: Java {} ({}); the Java argument check needs HotSpot's diagnostic bean, so it is off",
					report.javaVersion(), report.vendor());
		}
		return report;
	}

	// A copy, so one caller's timeout never completes the shared probe.
	static CompletableFuture<JvmReport> withTimeout(CompletableFuture<JvmReport> probe, long timeoutMs) {
		return probe.copy().completeOnTimeout(JvmReport.UNAVAILABLE, timeoutMs, TimeUnit.MILLISECONDS);
	}

	static JvmSnapshot read() {
		List<String> arguments = List.of();
		try {
			arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
		} catch (Throwable t) {
			RigTune.LOGGER.debug("RigTune: no JVM input arguments ({})", t.getClass().getSimpleName());
		}
		VmOptions options = null;
		try {
			options = HotSpot.options();
		} catch (Throwable t) {
			RigTune.LOGGER.debug("RigTune: no HotSpot diagnostic bean ({})", t.getClass().getSimpleName());
		}
		List<String> beans = new ArrayList<>();
		try {
			for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
				beans.add(bean.getName());
			}
		} catch (Throwable t) {
			beans.clear();
		}
		long maxHeap = -1;
		try {
			maxHeap = Runtime.getRuntime().maxMemory();
		} catch (Throwable ignored) {
			// unknown
		}
		long initialHeap = initialHeap(options);
		return new JvmSnapshot(arguments, options, beans, maxHeap, initialHeap, javaVersion(), property("java.vendor"));
	}

	private static long initialHeap(@Nullable VmOptions options) {
		try {
			if (options != null) {
				VmOptions.Lookup lookup = options.lookup("InitialHeapSize");
				long bytes = lookup.found() ? JvmArgs.size(lookup.value()) : -1;
				if (bytes > 0) {
					return bytes;
				}
			}
			return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getInit();
		} catch (Throwable t) {
			return -1;
		}
	}

	// Kept apart so a JVM without com.sun.management only fails here, inside read()'s guard.
	private static final class HotSpot {
		static @Nullable VmOptions options() {
			HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
			return bean == null ? null : name -> lookup(bean, name);
		}

		static VmOptions.Lookup lookup(HotSpotDiagnosticMXBean bean, String name) {
			try {
				VMOption option = bean.getVMOption(name);
				return option == null ? VmOptions.Lookup.FAILED : VmOptions.Lookup.found(String.valueOf(option.getValue()), origin(option.getOrigin()));
			} catch (IllegalArgumentException e) {
				// "VM option ... does not exist" (research §1.3).
				return VmOptions.Lookup.MISSING;
			} catch (Throwable t) {
				return VmOptions.Lookup.FAILED;
			}
		}

		private static VmOptions.Origin origin(VMOption.@Nullable Origin origin) {
			if (origin == null) {
				return VmOptions.Origin.OTHER;
			}
			try {
				return VmOptions.Origin.valueOf(origin.name());
			} catch (IllegalArgumentException e) {
				return VmOptions.Origin.OTHER;
			}
		}
	}
}
