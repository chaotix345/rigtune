package io.github.chaotix345.rigtune.gametest;

import com.google.gson.GsonBuilder;
import com.sun.management.GcInfo;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.FootprintStats;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import io.github.chaotix345.rigtune.core.footprint.FootprintBudgets;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import javax.management.ObjectName;
import java.io.IOException;
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
// the hub line, writes footprint/footprint-<mc>-<backend>.json (a CI artifact) and gates it with
// tools/footprint-budgets.json. The session-monitor numbers (monitorOn/OffRetainedBytes, samplerCpuMsPer60s) come with
// item 5 (F-L1) and are null until then.
public class FootprintGameTest implements FabricClientGameTest {
	private static final String RIGTUNE = "io.github.chaotix345.rigtune.";
	private static final String TEST_CLASSES = "io.github.chaotix345.rigtune.gametest.";
	private static final Pattern HISTOGRAM_LINE = Pattern.compile("^\\s*\\d+:\\s+(\\d+)\\s+(\\d+)\\s+(\\S+)");
	private static final int CYCLES = 20;
	private static final int TICK_CALLS = 100_000;
	private static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};

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

		startup(out, measured);
		checkThreads(hardware, out);
		checkStartupTimes(context, controller, out);
		tickHook(context, measured, out);
		noticeEvaluation(context, controller, out);
		retention(context, measured, out);
		measured.put("monitorOnRetainedBytes", null);
		measured.put("monitorOffRetainedBytes", null);
		measured.put("samplerCpuMsPer60s", null);

		FootprintBudgets budgets;
		try {
			budgets = FootprintBudgets.load();
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
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		List<String> names = new ArrayList<>();
		for (ThreadInfo info : mx.getThreadInfo(mx.getAllThreadIds())) {
			if (info != null && info.getThreadName().startsWith(FootprintStats.THREAD_PREFIX)) {
				names.add(info.getThreadName());
			}
		}
		names.sort(null);
		out.put("rigtuneThreads", names);
		if (!hardware.hasBattery()) {
			check(!names.contains("RigTune power"), "no PowerWatcher thread without a battery: " + names);
		}
		if (!ClientSettings.shared(FabricLoader.getInstance().getConfigDir()).stutterMonitor) {
			check(!names.contains("RigTune stutter sampler"), "no stutter sampler with the session monitor off: " + names);
		}
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
