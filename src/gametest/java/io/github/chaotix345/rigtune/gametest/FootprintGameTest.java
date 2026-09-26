package io.github.chaotix345.rigtune.gametest;

import com.google.gson.GsonBuilder;
import com.sun.management.GcInfo;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkConditions;
import com.sun.management.HotSpotDiagnosticMXBean;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.FootprintStats;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.client.probe.PowerWatcher;
import io.github.chaotix345.rigtune.client.stutter.StutterHooks;
import io.github.chaotix345.rigtune.client.stutter.StutterMonitor;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.management.ObjectName;
import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// docs/v0.4/SPEC.md 10 and 13 (AC10.4, AC13.2; plan review F-M1, F-L1). Every CI leg runs this in the production client
// after the other game tests. Startup numbers come from FootprintStats (measured by RigTune as they happened); this test
// adds the tick hook's cost, the class histogram (DiagnosticCommand gcClassHistogram, in-process) and the heap after that
// full GC before and after 20 open/close cycles of RigTuneScreen and the Tools hub, checks the startup-time record and
// the hub line, then turns the Stutter Doctor's session monitor on in a singleplayer world (its retained rings, the
// sampler's CPU over 60 s, the tick work with the monitor on) and off again (F-L1), writes
// footprint/footprint-<mc>-<backend>.json (a CI artifact) and gates it with tools/footprint-budgets.json.
public class FootprintGameTest implements FabricClientGameTest {
	private static final String RIGTUNE = "io.github.chaotix345.rigtune.";
	private static final String TEST_CLASSES = "io.github.chaotix345.rigtune.gametest.";
	private static final Pattern HISTOGRAM_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
	private static final int CYCLES = 20;
	private static final int TICK_CALLS = 100_000;
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};
	private static final String SAMPLER = "RigTune stutter sampler";
	private static final long SAMPLER_WINDOW_NANOS = 60_000_000_000L;
	private static final long SAMPLER_EARLY_NANOS = 5_000_000_000L;
	// The session capture's objects: none may stay alive once the monitor is off and its session is saved (F-M1), except
	// each Snapshot class's EMPTY constant (one instance once the class is initialised).
	private static final Map<String, Integer> CAPTURE_CLASSES = Map.of(RIGTUNE + "core.stutter.FrameRing", 0, RIGTUNE + "core.stutter.FrameRing$Snapshot", 1,
			RIGTUNE + "core.stutter.StutterRings", 0, RIGTUNE + "core.stutter.StutterRings$Snapshot", 1, RIGTUNE + "core.stutter.RecordRing", 0,
			RIGTUNE + "client.stutter.StutterMonitor$Capture", 0, RIGTUNE + "client.stutter.StutterCapture$Copy", 0);

	private record ClassCount(long instances, long bytes) {
	}

	private record Histogram(Map<String, ClassCount> rigtune, long heapAfterGcBytes, String collector, double millis) {
		long bytes() {
			return rigtune.values().stream().mapToLong(ClassCount::bytes).sum();
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.hardware() != null && RigTuneClient.controller().report() != null, 1200);
		context.waitFor(mc -> FootprintStats.snapshot().windowCpuNs() != null, 400);
		RigTuneController controller = RigTuneClient.controller();
		check(controller instanceof RealController, "the real controller is back after the earlier tests: " + controller);
		HardwareProfile hardware = RigTuneClient.hardware();

		Map<String, Object> out = new LinkedHashMap<>();
		Map<String, Number> measured = new LinkedHashMap<>();
		String mc = FabricLoader.getInstance().getRawGameVersion();
		String backend = backendName(hardware.gpu().backend());
		out.put("mcVersion", mc);
		out.put("backend", backend);
		out.put("rigtuneVersion", ((RealController) controller).modVersion());
		out.put("java", Runtime.version().toString());
		out.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
		out.put("processors", Runtime.getRuntime().availableProcessors());

		boolean complete = false;
		try {
			startup(out, measured);
			checkThreads(hardware, out);
			checkStartupTimes(context, controller, out);
			tickHook(context, measured, out);
			noticeEvaluation(context, controller, out);
			retention(context, measured, out);
			sessionMonitor(context, controller, hardware, measured, out);
			complete = true;
		} finally {
			// A failed check still leaves the numbers measured so far in the artifact.
			if (!complete) {
				out.put("measured", measured);
				out.put("incomplete", true);
				write(mc, backend, out);
			}
		}

		FootprintBudgets budgets;
		// P5-A F5: without compressed oops (ZGC) shallow sizes are up to twice as big; that budget scales, the leak checks don't.
		Boolean compressedOops = compressedOops();
		out.put("compressedOops", compressedOops);
		try {
			budgets = FootprintBudgets.load().forCompressedOops(!Boolean.FALSE.equals(compressedOops));
		} catch (IOException e) {
			throw new AssertionError("Could not read the footprint budgets", e);
		}
		List<FootprintBudgets.Violation> violations = budgets.check(measured);
		out.put("measured", measured);
		out.put("budgetMode", budgets.mode().name().toLowerCase(Locale.ROOT));
		out.put("violations", violations.stream().map(FootprintBudgets.Violation::message).toList());
		write(mc, backend, out);
		budgets.enforce(violations, RigTune.LOGGER::warn);
		RigTune.LOGGER.info("FootprintGameTest: {} budget(s), {} over (mode {})", budgets.budgets().size(), violations.size(), budgets.mode());
	}

	// What RigTune measured about its own startup (FootprintStats).
	private static void startup(Map<String, Object> out, Map<String, Number> measured) {
		FootprintStats.Snapshot s = FootprintStats.snapshot();
		check(s.preLaunchWallNs() >= 0 && s.initWallNs() >= 0 && s.clientStartedWallNs() >= 0, "startup timings recorded: " + s);
		out.put("preLaunchWallMs", ms(s.preLaunchWallNs()));
		out.put("preLaunchCpuMs", ms(s.preLaunchCpuNs()));
		out.put("initWallMs", ms(s.initWallNs()));
		out.put("initCpuMs", ms(s.initCpuNs()));
		out.put("clientStartedCpuMs", ms(s.clientStartedCpuNs()));
		out.put("threadMxBeanInitMs", ms(s.mxInitNs()));
		Map<String, Double> byThread = new TreeMap<>();
		s.windowCpuNs().forEach((name, nanos) -> byThread.put(name, ms(nanos)));
		out.put("workerCpuMsByThread", byThread);
		measured.put("renderThreadInitWallMs", ms(s.preLaunchWallNs() + s.initWallNs()));
		measured.put("renderThreadInitCpuMs", s.preLaunchCpuNs() < 0 || s.initCpuNs() < 0 ? null : ms(s.preLaunchCpuNs() + s.initCpuNs()));
		measured.put("clientStartedWallMs", ms(s.clientStartedWallNs()));
		measured.put("workerCpuMs5s", ms(s.windowCpuTotalNs()));
	}

	// SPEC X5 / AC10.6: the always-on threads that must not exist here: PowerWatcher without a battery (AC4.9) and the
	// stutter sampler with the session monitor off.
	private static void checkThreads(HardwareProfile hardware, Map<String, Object> out) {
		List<String> names = rigtuneThreads();
		out.put("rigtuneThreads", names);
		out.put("hasBattery", hardware.hasBattery());
		checkNoPowerWatcher(hardware, names);
		if (!ClientSettings.shared(FabricLoader.getInstance().getConfigDir()).stutterMonitor) {
			check(!names.contains(SAMPLER), "no stutter sampler with the session monitor off: " + names);
		}
	}

	// AC4.9 / AC10.6: without a real battery PowerWatcher never starts (CI runners have none).
	private static void checkNoPowerWatcher(HardwareProfile hardware, List<String> threads) {
		if (!hardware.hasBattery()) {
			check(!threads.contains("RigTune power") && !PowerWatcher.isRunning(), "no PowerWatcher without a battery: " + threads);
		}
	}

	private static List<String> rigtuneThreads() {
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		List<String> names = new ArrayList<>();
		for (ThreadInfo info : mx.getThreadInfo(mx.getAllThreadIds())) {
			if (info != null && info.getThreadName().startsWith(FootprintStats.THREAD_PREFIX)) {
				names.add(info.getThreadName());
			}
		}
		names.sort(null);
		return names;
	}

	// HotSpot's UseCompressedOops (false under ZGC and on heaps over 32 GB); null when it can't be read.
	private static @Nullable Boolean compressedOops() {
		try {
			return Boolean.valueOf(ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).getVMOption("UseCompressedOops").getValue());
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("FootprintGameTest: UseCompressedOops unreadable; budgets as for compressed oops", e);
			return null;
		}
	}

	private static long threadId(String name) {
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		for (ThreadInfo info : mx.getThreadInfo(mx.getAllThreadIds())) {
			if (info != null && name.equals(info.getThreadName())) {
				return info.getThreadId();
			}
		}
		return -1;
	}

	// AC13.2: this launch wrote exactly one run (the run dir is fresh, and every earlier test went back to a new title
	// screen, which must not record again), and the hub shows the line.
	private static void checkStartupTimes(ClientGameTestContext context, RigTuneController controller, Map<String, Object> out) {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		context.waitFor(mc -> !new StartupTimesStore(configDir).runs().isEmpty(), 200);
		List<StartupTimesStore.Run> runs = new StartupTimesStore(configDir).runs();
		check(runs.size() == 1, "one startup-times.json run for one launch: " + runs);
		StartupTimesStore.Run run = runs.getFirst();
		long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
		check(run.ms() > 0 && run.ms() < uptime, "launch-to-title " + run.ms() + " ms within this JVM's " + uptime + " ms");
		check(FabricLoader.getInstance().getRawGameVersion().equals(run.mcVersion()), "run's MC version: " + run);
		check(((RealController) controller).modVersion().equals(run.rigtuneVersion()), "run's RigTune version: " + run);
		check(run.mods() >= 3 && run.modSetHash() != null && run.modSetHash().matches("[0-9a-f]{64}"), "mod count and hash: " + run);
		// review-8 BF-1: the same mod-set hash as the benchmark's, RigTune itself left out.
		check(run.modSetHash().equals(BenchmarkConditions.modSetHash()), "the startup mod-set hash leaves RigTune out like the benchmark's: " + run);
		out.put("launchToTitleMs", run.ms());
		out.put("mods", run.mods());

		String expected = String.format(Locale.ROOT, "%.1f", run.ms() / 1000.0);
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new TitleScreen(), controller)));
		context.waitForScreen(ToolsScreen.class);
		context.waitTicks(3);
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			String where = size[0] + "x" + size[1] + "@" + size[2];
			context.runOnClient(mc -> {
				ToolsScreen tools = (ToolsScreen) mc.gui.screen();
				Component line = tools.startupLine();
				check(line != null && line.getString().contains(expected + " s"), "the hub's startup line at " + where + ": " + line);
				check(mc.font.width(line) <= tools.width - 4, "the startup line fits at " + where + ": " + line.getString());
				checkDetail(mc, tools, where);
			});
			context.takeScreenshot("footprint-tools-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}

		// The longest case, which one fresh launch can't produce: 12 runs and a changed mod set (a canned view).
		StubController stub = new StubController(RigTuneClient::hardware);
		StartupTimes.View trend = new StartupTimes.View(15_125L, 14_517L, 12, true);
		RigTuneController canned = (RigTuneController) Proxy.newProxyInstance(RigTuneController.class.getClassLoader(),
				new Class<?>[]{RigTuneController.class}, (proxy, method, args) -> "startupTimes".equals(method.getName())
						? trend : invoke(method, stub, args));
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new TitleScreen(), canned)));
		context.waitForScreen(ToolsScreen.class);
		for (int[] size : SIZES) {
			resize(context, size[0], size[1], size[2]);
			String where = "the canned trend at " + size[0] + "x" + size[1] + "@" + size[2];
			context.runOnClient(mc -> {
				ToolsScreen tools = (ToolsScreen) mc.gui.screen();
				Component line = tools.startupLine();
				check(line != null && line.getString().equals("Last launch 15.1 s · median of the last 10: 14.5 s"), where + ": " + line);
				check(mc.font.width(line) <= tools.width - 4, where + ": the startup line fits");
				check(tools.startupDetail().size() >= 2, where + ": the mod-set note and the advice");
				checkDetail(mc, tools, where);
			});
			context.takeScreenshot("footprint-tools-trend-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		resize(context, 854, 480, 0);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
	}

	private static void checkDetail(net.minecraft.client.Minecraft mc, ToolsScreen tools, String where) {
		List<FormattedCharSequence> detail = tools.startupDetail();
		check(!detail.isEmpty(), "the general advice under the startup line at " + where);
		check(!tools.startupDetailClipped(), "every line under the startup line fits above Done at " + where);
		for (FormattedCharSequence d : detail) {
			check(mc.font.width(d) <= tools.width - 4, "an advice line fits at " + where);
		}
	}

	private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	// RigTune's END_CLIENT_TICK hook on the render thread with a (non-title, non-RigTune) screen open: the path it takes
	// during play. Best of 3 rounds after a warm-up, so a JIT compile landing in one round doesn't count.
	private static void tickHook(ClientGameTestContext context, Map<String, Number> measured, Map<String, Object> out) {
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new TitleScreen(), RigTuneClient.controller())));
		context.waitForScreen(ToolsScreen.class);
		long[] best = context.computeOnClient(mc -> {
			com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
			for (int i = 0; i < 2 * TICK_CALLS; i++) {
				RigTuneClient.onTick(mc);
			}
			long nanos = Long.MAX_VALUE;
			long bytes = Long.MAX_VALUE;
			for (int round = 0; round < 3; round++) {
				long allocated = mx.getCurrentThreadAllocatedBytes();
				long start = System.nanoTime();
				for (int i = 0; i < TICK_CALLS; i++) {
					RigTuneClient.onTick(mc);
				}
				nanos = Math.min(nanos, System.nanoTime() - start);
				bytes = Math.min(bytes, mx.getCurrentThreadAllocatedBytes() - allocated);
			}
			return new long[]{nanos, bytes};
		});
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		measured.put("tickHookNsPerCall", round2((double) best[0] / TICK_CALLS));
		measured.put("tickHookAllocBytes", best[1]);
		out.put("tickHookCalls", TICK_CALLS);
	}

	// SPEC X5: notices are evaluated on screen init/rebuild only; their cost per evaluation, for the record.
	private static void noticeEvaluation(ClientGameTestContext context, RigTuneController controller, Map<String, Object> out) {
		double micros = context.computeOnClient(mc -> {
			controller.notices();
			long start = System.nanoTime();
			for (int i = 0; i < 100; i++) {
				controller.notices();
			}
			return (System.nanoTime() - start) / 100 / 1000.0;
		});
		out.put("noticeEvaluationUs", round2(micros));
	}

	// F-M1: the heap after the histogram's full GC before and after CYCLES open/close cycles (whole-heap delta), and the
	// RigTune classes whose live instances grew by at least one per cycle (leak suspects). The RigTune-class bytes at idle
	// are shallow sizes (RigTune's own objects, not the JDK objects they hold): a diagnostic under the SPEC's 8 MiB cap.
	private static void retention(ClientGameTestContext context, Map<String, Number> measured, Map<String, Object> out) {
		cycle(context);
		cycle(context);
		Histogram idle = histogram();
		for (int i = 0; i < CYCLES; i++) {
			cycle(context);
		}
		Histogram after = histogram();
		Map<String, Long> growth = new TreeMap<>();
		for (Map.Entry<String, ClassCount> e : after.rigtune().entrySet()) {
			long before = idle.rigtune().getOrDefault(e.getKey(), new ClassCount(0, 0)).instances();
			if (e.getValue().instances() > before) {
				growth.put(e.getKey(), e.getValue().instances() - before);
			}
		}
		List<String> suspects = growth.entrySet().stream().filter(e -> e.getValue() >= CYCLES).map(Map.Entry::getKey).toList();
		measured.put("rigtuneClassBytesIdle", idle.bytes());
		measured.put("heapGrowthAfterCyclesBytes", after.heapAfterGcBytes() - idle.heapAfterGcBytes());
		measured.put("leakSuspects", suspects.size());
		out.put("rigtuneClassBytesAfterCycles", after.bytes());
		out.put("rigtuneInstancesIdle", idle.rigtune().values().stream().mapToLong(ClassCount::instances).sum());
		out.put("heapAfterGcIdleBytes", idle.heapAfterGcBytes());
		out.put("heapAfterGcCyclesBytes", after.heapAfterGcBytes());
		out.put("collector", after.collector());
		out.put("histogramMs", round2(Math.max(idle.millis(), after.millis())));
		out.put("cycles", CYCLES);
		out.put("instanceGrowth", growth);
		out.put("leakSuspectClasses", suspects);
		out.put("largestRigtuneClassesIdle", idle.rigtune().entrySet().stream()
				.sorted(Comparator.comparingLong((Map.Entry<String, ClassCount> e) -> e.getValue().bytes()).reversed())
				.limit(12).collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().bytes(), (a, b) -> a, LinkedHashMap::new)));
	}

	// F-L1 (AC10.4; F-M1): the session monitor in a singleplayer world, on the play path (no screen open). Retention by
	// explicit accounting: StutterMonitor.retainedBytes() is 0 at idle, the rings while on, and must be back after it's
	// off, with no sampler thread, no GC listener and no capture object alive (class histogram once the session is saved).
	// The sampler's CPU is gated in steady state, over 60 s of wall time from 5 s after its thread started (coordinator,
	// 2026-09-26); its first 5 s (start-up, the logged census) are recorded apart. The END_CLIENT_TICK work with the monitor on
	// is RigTuneClient.onTick plus the monitor's own listener (StutterHooks.tick, private, called through a method handle);
	// the same pair is timed first with the monitor off (every player's play path).
	// The heap after a full GC before, during and after is a diagnostic only: a live world moves it by megabytes.
	private static void sessionMonitor(ClientGameTestContext context, RigTuneController controller, HardwareProfile hardware,
			Map<String, Number> measured, Map<String, Object> out) {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		check(!ClientSettings.shared(configDir).stutterMonitor && !StutterMonitor.active(), "the monitor is off before the world");
		MethodHandle stutterTick = stutterTick();
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.waitTicks(100);
			long idleRetained = StutterMonitor.retainedBytes();
			check(idleRetained == 0 && StutterMonitor.session() == null, "nothing captured or retained with the monitor off: " + idleRetained);
			Histogram idle = histogram();
			context.runOnClient(mc -> mc.gui.setScreen(null));
			long[] tickOff = context.computeOnClient(mc -> timeTicks(mc, stutterTick));
			check(StutterMonitor.session() == null, "the monitor stayed off through the tick timing");

			long start = System.nanoTime();
			context.runOnClient(mc -> controller.setStutterMonitor(true));
			context.waitFor(mc -> StutterMonitor.session() != null, 100);
			String startedAt = StutterMonitor.session().startedAt().toString();
			long sampler = threadId(SAMPLER);
			check(sampler >= 0 && StutterHooks.gcListenerActive(), "the sampler thread and the GC listener run while capturing: " + rigtuneThreads());
			long early = start + SAMPLER_EARLY_NANOS;
			context.waitFor(mc -> System.nanoTime() - early >= 0, ClientGameTestContext.NO_TIMEOUT);
			long earlyCpu = ManagementFactory.getThreadMXBean().getThreadCpuTime(sampler);
			long earlyWindow = System.nanoTime() - start;
			long deadline = start + SAMPLER_EARLY_NANOS + SAMPLER_WINDOW_NANOS;
			context.waitFor(mc -> System.nanoTime() - deadline >= 0, ClientGameTestContext.NO_TIMEOUT);
			long samplerCpu = ManagementFactory.getThreadMXBean().getThreadCpuTime(sampler);
			long window = System.nanoTime() - start;
			check(samplerCpu >= 0, "the sampler thread is alive after " + window / 1_000_000 + " ms");
			long samples = samplerSamples();
			out.put("samplerJdkFloorMsPer240", samplerFloor());
			List<String> threadsOn = rigtuneThreads();
			checkNoPowerWatcher(hardware, threadsOn);

			context.runOnClient(mc -> mc.gui.setScreen(null));
			long[] tick = context.computeOnClient(mc -> timeTicks(mc, stutterTick));
			check(StutterMonitor.session() != null, "still capturing after the tick timing");
			long onRetained = StutterMonitor.retainedBytes();
			long frames = sessionFrames();
			boolean phaseTimersSeen = StutterMonitor.phaseTiming();
			Histogram on = histogram();

			context.runOnClient(mc -> controller.setStutterMonitor(false));
			context.waitTicks(5);
			check(StutterMonitor.session() == null && !StutterMonitor.active(), "no capture after the monitor is off");
			// review-8 ST-1: stopping doesn't wait for the sampler thread; it ends on its own right after.
			context.waitFor(mc -> threadId(SAMPLER) < 0, 100);
			check(!StutterHooks.gcListenerActive() && threadId(SAMPLER) < 0, "no GC listener and no sampler thread after it's off: " + rigtuneThreads());
			long offRetained = StutterMonitor.retainedBytes();
			context.waitFor(mc -> new StutterStore(configDir).sessions().stream().anyMatch(r -> startedAt.equals(r.startedAt())), 400);
			context.waitTicks(5);
			// The save task logs from its copy just after writing stutter.json, so a capture object can outlive the file write
			// by a moment: up to 3 histograms before counting leftovers.
			Histogram off = histogram();
			Map<String, Long> leftover = captureInstances(off);
			for (int retry = 0; retry < 2 && !leftover.isEmpty(); retry++) {
				context.waitTicks(20);
				off = histogram();
				leftover = captureInstances(off);
			}

			measured.put("monitorOnRetainedBytes", onRetained);
			measured.put("monitorOffRetainedBytes", offRetained);
			measured.put("monitorOffLeftoverInstances", leftover.values().stream().mapToLong(Long::longValue).sum());
			measured.put("samplerCpuMsPer60s", round2((samplerCpu - earlyCpu) / 1e6 * SAMPLER_WINDOW_NANOS / (window - earlyWindow)));
			measured.put("tickHookNsPerCallOn", round2((double) tick[0] / TICK_CALLS));
			measured.put("tickHookAllocBytesOn", tick[1]);
			measured.put("tickHookNsPerCallWorld", round2((double) tickOff[0] / TICK_CALLS));
			measured.put("tickHookAllocBytesWorld", tickOff[1]);
			out.put("monitorIdleRetainedBytes", idleRetained);
			out.put("monitorFrames", frames);
			// StutterMonitor's phase-timer bits are static: every timer seen since the JVM started, not only this session.
			out.put("phaseTimersSeen", phaseTimersSeen);
			out.put("samplerCpuMs", ms(samplerCpu));
			out.put("samplerWindowMs", ms(window));
			out.put("samplerCpuMsFirst5s", ms(earlyCpu));
			out.put("samplerCpuMsSteadyWindowMs", ms(window - earlyWindow));
			out.put("samplerSamples", samples);
			out.put("rigtuneThreadsMonitorOn", threadsOn);
			out.put("heapAfterGcWorldIdleBytes", idle.heapAfterGcBytes());
			out.put("heapAfterGcMonitorOnBytes", on.heapAfterGcBytes());
			out.put("heapAfterGcMonitorOffBytes", off.heapAfterGcBytes());
			out.put("monitorOnHeapDeltaBytes", on.heapAfterGcBytes() - idle.heapAfterGcBytes());
			out.put("monitorOffHeapDeltaBytes", off.heapAfterGcBytes() - idle.heapAfterGcBytes());
			out.put("captureInstancesWorldIdle", captureInstances(idle));
			out.put("captureInstancesMonitorOn", captureInstances(on));
			out.put("captureInstancesMonitorOff", leftover);
		} finally {
			context.runOnClient(mc -> controller.setStutterMonitor(false));
		}
		context.waitForScreen(TitleScreen.class);
	}

	private static MethodHandle stutterTick() {
		try {
			Method tick = StutterHooks.class.getDeclaredMethod("tick", Minecraft.class);
			tick.setAccessible(true);
			return MethodHandles.lookup().unreflect(tick);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("the monitor's END_CLIENT_TICK listener StutterHooks.tick(Minecraft)", e);
		}
	}

	// RigTune's two END_CLIENT_TICK listeners, as tickHook: best of 3 rounds after a warm-up, render thread.
	private static long[] timeTicks(Minecraft mc, MethodHandle stutterTick) {
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		try {
			for (int i = 0; i < 2 * TICK_CALLS; i++) {
				RigTuneClient.onTick(mc);
				stutterTick.invokeExact(mc);
			}
			long nanos = Long.MAX_VALUE;
			long bytes = Long.MAX_VALUE;
			for (int round = 0; round < 3; round++) {
				long allocated = mx.getCurrentThreadAllocatedBytes();
				long start = System.nanoTime();
				for (int i = 0; i < TICK_CALLS; i++) {
					RigTuneClient.onTick(mc);
					stutterTick.invokeExact(mc);
				}
				nanos = Math.min(nanos, System.nanoTime() - start);
				bytes = Math.min(bytes, mx.getCurrentThreadAllocatedBytes() - allocated);
			}
			return new long[]{nanos, bytes};
		} catch (Throwable t) {
			throw new AssertionError("timing RigTune's tick listeners failed", t);
		}
	}

	// Live capture objects beyond the permanent EMPTY constants.
	private static Map<String, Long> captureInstances(Histogram histogram) {
		Map<String, Long> out = new TreeMap<>();
		histogram.rigtune().forEach((name, count) -> {
			Integer permanent = CAPTURE_CLASSES.get(name);
			if (permanent != null && count.instances() > permanent) {
				out.put(name, count.instances() - permanent);
			}
		});
		return out;
	}

	// A diagnostic under samplerCpuMsPer60s: the CPU of the JDK calls one sample makes, alone, 240 times (60 s at 4 Hz) on
	// this thread with the same threads alive, plus getThreadInfo for every thread (which the sampler now does only for
	// threads it hasn't seen).
	private static Map<String, Number> samplerFloor() {
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
		int n = 240;
		long[] ids = mx.getAllThreadIds();
		long c0 = mx.getCurrentThreadCpuTime();
		for (int i = 0; i < n; i++) {
			ids = mx.getAllThreadIds();
		}
		long c1 = mx.getCurrentThreadCpuTime();
		for (int i = 0; i < n; i++) {
			mx.getThreadCpuTime(ids);
		}
		long c2 = mx.getCurrentThreadCpuTime();
		for (int i = 0; i < n; i++) {
			os.getProcessCpuTime();
		}
		long c3 = mx.getCurrentThreadCpuTime();
		for (int i = 0; i < n; i++) {
			mx.getThreadInfo(ids, 0);
		}
		long c4 = mx.getCurrentThreadCpuTime();
		Map<String, Number> out = new LinkedHashMap<>();
		out.put("threads", ids.length);
		out.put("getAllThreadIds", ms(c1 - c0));
		out.put("getThreadCpuTime", ms(c2 - c1));
		out.put("getProcessCpuTime", ms(c3 - c2));
		out.put("getThreadInfoEveryThread", ms(c4 - c3));
		return out;
	}

	// The sampler's records in the rings; a separate method so no local variable of the test keeps the rings alive.
	private static long samplerSamples() {
		StutterRings rings = StutterMonitor.rings();
		return rings == null ? 0 : rings.snapshot().samples().length / StutterRings.SAMPLE_STRIDE;
	}

	private static long sessionFrames() {
		StutterMonitor.Capture session = StutterMonitor.session();
		return session == null ? -1 : session.snapshot().frames();
	}

	private static void cycle(ClientGameTestContext context) {
		context.runOnClient(mc -> RigTuneClient.open(new TitleScreen()));
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(1);
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(mc.gui.screen(), RigTuneClient.controller())));
		context.waitForScreen(ToolsScreen.class);
		context.waitTicks(1);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		context.waitTicks(1);
	}

	// GC.class_histogram through the DiagnosticCommand MBean (a full GC first), then the heap right after that GC.
	private static Histogram histogram() {
		Map<String, Long> countsBefore = new HashMap<>();
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			countsBefore.put(gc.getName(), gc.getCollectionCount());
		}
		String text;
		long start = System.nanoTime();
		try {
			text = (String) ManagementFactory.getPlatformMBeanServer().invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
					"gcClassHistogram", new Object[]{new String[0]}, new String[]{String[].class.getName()});
		} catch (Exception e) {
			throw new AssertionError("gcClassHistogram failed", e);
		}
		double millis = (System.nanoTime() - start) / 1e6;
		Map<String, ClassCount> rigtune = new HashMap<>();
		for (String line : text.split("\n")) {
			Matcher m = HISTOGRAM_LINE.matcher(line);
			if (!m.find()) {
				continue;
			}
			String name = m.group(3);
			String element = name.replaceFirst("^\\[+L", "").replaceFirst(";$", "");
			if (element.startsWith(RIGTUNE) && !element.startsWith(TEST_CLASSES)) {
				rigtune.merge(name, new ClassCount(Long.parseLong(m.group(1)), Long.parseLong(m.group(2))),
						(a, b) -> new ClassCount(a.instances() + b.instances(), a.bytes() + b.bytes()));
			}
		}
		check(!rigtune.isEmpty(), "the histogram lists RigTune classes");
		String[] collector = new String[1];
		long heap = heapAfterFullGc(countsBefore, collector);
		return new Histogram(rigtune, heap, collector[0], millis);
	}

	// The heap pools' usage right after the histogram's full GC, from that collector's GcInfo (a young GC that ran after it
	// would count garbage in the old generation): the full-GC bean (G1 "Old Generation", "MarkSweep", ZGC "Major") whose
	// count went up; the current heap usage if the JVM doesn't say.
	private static long heapAfterFullGc(Map<String, Long> countsBefore, String[] collector) {
		Set<String> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP)
				.map(MemoryPoolMXBean::getName).collect(Collectors.toSet());
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			String name = gc.getName();
			boolean full = name.contains("Old") || name.contains("MarkSweep") || name.contains("Major");
			if (!full || gc.getCollectionCount() <= countsBefore.getOrDefault(name, 0L)
					|| !(gc instanceof com.sun.management.GarbageCollectorMXBean bean)) {
				continue;
			}
			GcInfo info = bean.getLastGcInfo();
			if (info != null) {
				collector[0] = name;
				return info.getMemoryUsageAfterGc().entrySet().stream().filter(e -> heapPools.contains(e.getKey()))
						.mapToLong(e -> e.getValue().getUsed()).sum();
			}
		}
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		collector[0] = "none (MemoryMXBean)";
		return heap.getUsed();
	}

	private static void write(String mc, String backend, Map<String, Object> out) {
		Path file = FabricLoader.getInstance().getGameDir().resolve("footprint").resolve("footprint-" + mc + "-" + backend + ".json");
		String json = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create().toJson(out);
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError("Could not write " + file, e);
		}
		RigTune.LOGGER.info("FootprintGameTest: wrote {}:\n{}", file, json);
	}

	private static String backendName(GraphicsBackend backend) {
		return switch (backend) {
			case OPENGL -> "OpenGL";
			case VULKAN -> "Vulkan";
			case UNKNOWN -> "unknown";
		};
	}

	private static void resize(ClientGameTestContext context, int width, int height, int guiScale) {
		context.getInput().resizeWindow(width, height);
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		context.waitTicks(3);
	}

	private static Double ms(Long nanos) {
		return nanos == null || nanos < 0 ? null : round2(nanos / 1e6);
	}

	private static double round2(double value) {
		return Math.round(value * 100) / 100.0;
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
