package io.github.chaotix345.rigtune.gametest;

import com.google.gson.GsonBuilder;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.stutter.StutterHooks;
import io.github.chaotix345.rigtune.client.stutter.StutterMonitor;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.CompilationMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// RESEARCH ONLY (research/v05-ci, r-ci): compares estimators for the per-call tick-hook timings of FootprintGameTest.
// For each case it records the current estimator (warm-up 200k, best of 5 blocks of 100k, wall) and an interleaved design:
// BLOCKS triples of [hook x CALLS][hook twice x CALLS (a deliberate 2x regression)][reference workload x CALLS], each
// block timed by wall clock and by thread CPU time. Nothing is gated; the numbers go to footprint/timing-*.json.
public class TimingProbeGameTest implements FabricClientGameTest {
	private static final int CALLS = 20_000;
	private static final int BLOCKS = Integer.getInteger("rigtune.test.timingBlocks", 48);
	private static final int REPS = Integer.getInteger("rigtune.test.timingReps", 3);
	private static final long[] REF = new long[256];
	private static long refSink;
	private static long refState = 0x9E3779B97F4A7C15L;

	@FunctionalInterface
	private interface Hook {
		void call(Minecraft mc) throws Throwable;
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.controller().report() != null, 1200);
		RigTuneController controller = RigTuneClient.controller();
		MethodHandle stutterTick = stutterTick();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("mc", FabricLoader.getInstance().getRawGameVersion());
		out.put("cpu", cpuModel());
		out.put("processors", Runtime.getRuntime().availableProcessors());
		out.put("calls", CALLS);
		out.put("blocks", BLOCKS);
		List<Map<String, Object>> reps = new ArrayList<>();
		for (int rep = 0; rep < REPS; rep++) {
			Map<String, Object> r = new LinkedHashMap<>();
			context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new TitleScreen(), controller)));
			context.waitForScreen(ToolsScreen.class);
			r.put("tickHookNsPerCall", context.computeOnClient(mc -> measure(mc, RigTuneClient::onTick)));
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			context.waitForScreen(TitleScreen.class);
			try (TestSingleplayerContext world = context.worldBuilder().create()) {
				context.runOnClient(mc -> mc.gui.setScreen(null));
				context.waitTicks(100);
				Hook pair = mc -> {
					RigTuneClient.onTick(mc);
					stutterTick.invokeExact(mc);
				};
				r.put("tickHookNsPerCallWorld", context.computeOnClient(mc -> measure(mc, pair)));
				context.runOnClient(mc -> controller.setStutterMonitor(true));
				context.waitFor(mc -> StutterMonitor.session() != null, 100);
				context.waitTicks(100);
				context.runOnClient(mc -> mc.gui.setScreen(null));
				r.put("tickHookNsPerCallOn", context.computeOnClient(mc -> measure(mc, pair)));
			} finally {
				context.runOnClient(mc -> controller.setStutterMonitor(false));
			}
			context.waitForScreen(TitleScreen.class);
			reps.add(r);
			RigTune.LOGGER.info("TimingProbeGameTest: rep {}: {}", rep, r);
			context.waitTicks(40);
		}
		out.put("reps", reps);
		write(out);
	}

	private static Map<String, Object> measure(Minecraft mc, Hook hook) {
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
		Map<String, Object> m = new LinkedHashMap<>();
		try {
			long jit0 = jit.getTotalCompilationTime();
			// The current estimator (FootprintGameTest.timeTicks).
			for (int i = 0; i < 200_000; i++) {
				hook.call(mc);
			}
			long best = Long.MAX_VALUE;
			double[] legacy = new double[5];
			for (int round = 0; round < 5; round++) {
				long start = System.nanoTime();
				for (int i = 0; i < 100_000; i++) {
					hook.call(mc);
				}
				long ns = System.nanoTime() - start;
				legacy[round] = ns / 100_000.0;
				best = Math.min(best, ns);
			}
			m.put("legacyBest5", best / 100_000.0);
			m.put("legacyBlocks", legacy);
			// The cheapest fix: keep best of 5, but start only once a 20k-call block ran with no JIT compilation anywhere in
			// the JVM (cap 5 s).
			long quietStart = System.nanoTime();
			int quietBlocks = 0;
			while (System.nanoTime() - quietStart < 5_000_000_000L) {
				long before = jit.getTotalCompilationTime();
				for (int i = 0; i < CALLS; i++) {
					hook.call(mc);
				}
				quietBlocks++;
				if (jit.getTotalCompilationTime() == before) {
					break;
				}
			}
			m.put("quietWarmupMs", (System.nanoTime() - quietStart) / 1_000_000);
			m.put("quietWarmupBlocks", quietBlocks);
			long quietBest = Long.MAX_VALUE;
			for (int round = 0; round < 5; round++) {
				long start = System.nanoTime();
				for (int i = 0; i < 100_000; i++) {
					hook.call(mc);
				}
				quietBest = Math.min(quietBest, System.nanoTime() - start);
			}
			m.put("quietBest5", quietBest / 100_000.0);
			long jit1 = jit.getTotalCompilationTime();
			// Warm the twin and the reference too.
			for (int i = 0; i < 200_000; i++) {
				hook.call(mc);
				hook.call(mc);
				ref(i);
			}
			double[] hWall = new double[BLOCKS], h2Wall = new double[BLOCKS], rWall = new double[BLOCKS];
			double[] hCpu = new double[BLOCKS], h2Cpu = new double[BLOCKS], rCpu = new double[BLOCKS];
			for (int b = 0; b < BLOCKS; b++) {
				long c0 = mx.getCurrentThreadCpuTime();
				long w0 = System.nanoTime();
				for (int i = 0; i < CALLS; i++) {
					hook.call(mc);
				}
				long w1 = System.nanoTime();
				long c1 = mx.getCurrentThreadCpuTime();
				for (int i = 0; i < CALLS; i++) {
					hook.call(mc);
					hook.call(mc);
				}
				long w2 = System.nanoTime();
				long c2 = mx.getCurrentThreadCpuTime();
				for (int i = 0; i < CALLS; i++) {
					ref(i);
				}
				long w3 = System.nanoTime();
				long c3 = mx.getCurrentThreadCpuTime();
				hWall[b] = (w1 - w0) / (double) CALLS;
				h2Wall[b] = (w2 - w1) / (double) CALLS;
				rWall[b] = (w3 - w2) / (double) CALLS;
				hCpu[b] = (c1 - c0) / (double) CALLS;
				h2Cpu[b] = (c2 - c1) / (double) CALLS;
				rCpu[b] = (c3 - c2) / (double) CALLS;
			}
			m.put("hWall", round(hWall));
			m.put("h2Wall", round(h2Wall));
			m.put("rWall", round(rWall));
			m.put("hCpu", round(hCpu));
			m.put("h2Cpu", round(h2Cpu));
			m.put("rCpu", round(rCpu));
			m.put("jitMsLegacy", jit1 - jit0);
			m.put("jitMsInterleaved", jit.getTotalCompilationTime() - jit1);
			m.put("refSink", refSink);
		} catch (Throwable t) {
			throw new AssertionError("timing failed", t);
		}
		return m;
	}

	// A fixed pure-Java workload (loads, stores, a branch) of roughly the hook's cost, to normalise for the runner's speed.
	private static void ref(int i) {
		long x = refState;
		for (int k = 0; k < 6; k++) {
			x ^= x << 13;
			x ^= x >>> 7;
			x ^= x << 17;
			int slot = (int) (x & 255);
			if ((x & 1) == 0) {
				REF[slot] += x;
			} else {
				refSink += REF[slot];
			}
		}
		refState = x + i;
	}

	private static double[] round(double[] xs) {
		return Arrays.stream(xs).map(x -> Math.round(x * 1000) / 1000.0).toArray();
	}

	private static String cpuModel() {
		try {
			for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
				if (line.startsWith("model name")) {
					return line.substring(line.indexOf(':') + 1).trim();
				}
			}
		} catch (IOException | RuntimeException e) {
			return System.getenv().getOrDefault("PROCESSOR_IDENTIFIER", "unknown");
		}
		return "unknown";
	}

	private static MethodHandle stutterTick() {
		try {
			Method tick = StutterHooks.class.getDeclaredMethod("tick", Minecraft.class);
			tick.setAccessible(true);
			return MethodHandles.lookup().unreflect(tick);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError(e);
		}
	}

	private static void write(Map<String, Object> out) {
		try {
			Path dir = FabricLoader.getInstance().getGameDir().resolve("footprint");
			Files.createDirectories(dir);
			Path file = dir.resolve("timing-" + out.get("mc") + "-" + System.currentTimeMillis() + ".json");
			Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
			RigTune.LOGGER.info("TimingProbeGameTest: wrote {}", file);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
