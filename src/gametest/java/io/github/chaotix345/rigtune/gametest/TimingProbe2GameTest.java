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

// RESEARCH ONLY (research/v05-ci, ws-ci calibration): FootprintGameTest's tick timing (copied verbatim from feat/v05-ci)
// on many runners, early in the JVM (the harsh case): the monitor-on tick work 1x, called twice, and with the monitor's
// own hook doing its work twice (the AC1d.3 regression), each as the ratio to the reference.
public class TimingProbe2GameTest implements FabricClientGameTest {
	private static final int TICK_BLOCKS = 48;
	private static final int TICK_BLOCK_CALLS = 20_000;
	private static final int TICK_WARM_UP_ROUNDS = 300;
	private static final int TICK_WARM_UP_CALLS = 1_000;
	private static final long TICK_JIT_QUIET_MS = 100;
	private static final long TICK_JIT_QUIET_CAP_MS = 10_000;
	private static final long[] REFERENCE = new long[256];
	private static long referenceState = 0x9E3779B97F4A7C15L;
	private static long referenceSink;
	private static final int REPS = Integer.getInteger("rigtune.test.timingReps", 3);

	@FunctionalInterface
	private interface TickWork {
		void run(Minecraft mc) throws Throwable;
	}

	private record TickTiming(double nsPerCall, long allocBytes, double vsReference, double twinVsReference, Map<String, Object> detail) {
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
		List<Map<String, Object>> reps = new ArrayList<>();
		for (int rep = 0; rep < REPS; rep++) {
			Map<String, Object> r = new LinkedHashMap<>();
			context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new TitleScreen(), controller)));
			context.waitForScreen(ToolsScreen.class);
			r.put("title", context.computeOnClient(mc -> timeTick(mc, RigTuneClient::onTick)).detail());
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
			context.waitForScreen(TitleScreen.class);
			try (TestSingleplayerContext world = context.worldBuilder().create()) {
				context.runOnClient(mc -> mc.gui.setScreen(null));
				context.waitTicks(100);
				TickWork pair = mc -> {
					RigTuneClient.onTick(mc);
					stutterTick.invokeExact(mc);
				};
				TickWork doubledMonitor = mc -> {
					RigTuneClient.onTick(mc);
					stutterTick.invokeExact(mc);
					stutterTick.invokeExact(mc);
				};
				r.put("world", context.computeOnClient(mc -> timeTick(mc, pair)).detail());
				context.runOnClient(mc -> controller.setStutterMonitor(true));
				context.waitFor(mc -> StutterMonitor.session() != null, 100);
				context.waitTicks(100);
				context.runOnClient(mc -> mc.gui.setScreen(null));
				r.put("on", context.computeOnClient(mc -> timeTick(mc, pair)).detail());
				r.put("onMonitorDoubled", context.computeOnClient(mc -> timeTick(mc, doubledMonitor)).detail());
			} finally {
				context.runOnClient(mc -> controller.setStutterMonitor(false));
			}
			context.waitForScreen(TitleScreen.class);
			reps.add(r);
			context.waitTicks(40);
		}
		out.put("reps", reps);
		try {
			Path dir = FabricLoader.getInstance().getGameDir().resolve("footprint");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("timing2-" + out.get("mc") + "-" + System.currentTimeMillis() + ".json"),
					new GsonBuilder().setPrettyPrinting().create().toJson(out), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// See TICK_BLOCKS. On the render thread; bytes are the fewest any work block allocated. The three loops are small
	// methods of their own, warmed up in short calls, so each is compiled as itself (not as an on-stack replacement inside
	// a bigger method); then the blocks wait until the JIT has finished nothing for TICK_JIT_QUIET_MS (at most
	// TICK_JIT_QUIET_CAP_MS). The first proof run (36295129832, the classes cut to two, so the timing ran a minute into the
	// JVM with 250-380 ms of compilation during the blocks) had the reference 1.3-1.8x slower than in the full suite.
	private static TickTiming timeTick(Minecraft mc, TickWork work) {
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
		try {
			for (int round = 0; round < TICK_WARM_UP_ROUNDS; round++) {
				once(mc, work, TICK_WARM_UP_CALLS);
				twice(mc, work, TICK_WARM_UP_CALLS);
				referenceSink += reference(TICK_WARM_UP_CALLS);
			}
			long waitStart = System.nanoTime();
			long quietSince = waitStart;
			long compiled = jit.getTotalCompilationTime();
			while (System.nanoTime() - quietSince < TICK_JIT_QUIET_MS * 1_000_000L && System.nanoTime() - waitStart < TICK_JIT_QUIET_CAP_MS * 1_000_000L) {
				once(mc, work, TICK_WARM_UP_CALLS);
				referenceSink += reference(TICK_WARM_UP_CALLS);
				long now = jit.getTotalCompilationTime();
				if (now != compiled) {
					compiled = now;
					quietSince = System.nanoTime();
				}
			}
			long quietWaitMs = (System.nanoTime() - waitStart) / 1_000_000;
			double[] onceNs = new double[TICK_BLOCKS];
			double[] twiceNs = new double[TICK_BLOCKS];
			double[] referenceNs = new double[TICK_BLOCKS];
			double[] cpuRatio = new double[TICK_BLOCKS];
			long bytes = Long.MAX_VALUE;
			long jitBefore = jit.getTotalCompilationTime();
			for (int block = 0; block < TICK_BLOCKS; block++) {
				long allocated = mx.getCurrentThreadAllocatedBytes();
				long c0 = mx.getCurrentThreadCpuTime();
				long t0 = System.nanoTime();
				once(mc, work, TICK_BLOCK_CALLS);
				long t1 = System.nanoTime();
				long c1 = mx.getCurrentThreadCpuTime();
				bytes = Math.min(bytes, mx.getCurrentThreadAllocatedBytes() - allocated);
				long t2 = System.nanoTime();
				twice(mc, work, TICK_BLOCK_CALLS);
				long t3 = System.nanoTime();
				long c3 = mx.getCurrentThreadCpuTime();
				referenceSink += reference(TICK_BLOCK_CALLS);
				long t4 = System.nanoTime();
				long c4 = mx.getCurrentThreadCpuTime();
				onceNs[block] = (t1 - t0) / (double) TICK_BLOCK_CALLS;
				twiceNs[block] = (t3 - t2) / (double) TICK_BLOCK_CALLS;
				referenceNs[block] = (t4 - t3) / (double) TICK_BLOCK_CALLS;
				cpuRatio[block] = c4 > c3 ? (c1 - c0) / (double) (c4 - c3) : Double.NaN;
			}
			double[] ratio = new double[TICK_BLOCKS];
			double[] twinRatio = new double[TICK_BLOCKS];
			for (int block = 0; block < TICK_BLOCKS; block++) {
				ratio[block] = onceNs[block] / referenceNs[block];
				twinRatio[block] = twiceNs[block] / referenceNs[block];
			}
			Map<String, Object> detail = new LinkedHashMap<>();
			detail.put("blockCalls", TICK_BLOCK_CALLS);
			detail.put("medianNs", round2(median(onceNs)));
			detail.put("medianTwiceNs", round2(median(twiceNs)));
			detail.put("medianReferenceNs", round2(median(referenceNs)));
			detail.put("vsReference", round3(median(ratio)));
			detail.put("twinVsReference", round3(median(twinRatio)));
			// Thread CPU time instead of wall (a diagnostic: Windows counts thread CPU in 15.6 ms steps, so it is NaN or
			// meaningless there).
			detail.put("vsReferenceCpu", round3(median(Arrays.stream(cpuRatio).filter(r -> !Double.isNaN(r)).toArray())));
			detail.put("jitQuietWaitMs", quietWaitMs);
			detail.put("jitMsDuringBlocks", jit.getTotalCompilationTime() - jitBefore);
			detail.put("ns", Arrays.stream(onceNs).map(TimingProbe2GameTest::round2).toArray());
			detail.put("twiceNs", Arrays.stream(twiceNs).map(TimingProbe2GameTest::round2).toArray());
			detail.put("referenceNs", Arrays.stream(referenceNs).map(TimingProbe2GameTest::round2).toArray());
			return new TickTiming(round2(median(onceNs)), bytes, round3(median(ratio)), round3(median(twinRatio)), detail);
		} catch (Throwable t) {
			throw new AssertionError("timing RigTune's tick listeners failed", t);
		}
	}

	private static void once(Minecraft mc, TickWork work, int calls) throws Throwable {
		for (int i = 0; i < calls; i++) {
			work.run(mc);
		}
	}

	private static void twice(Minecraft mc, TickWork work, int calls) throws Throwable {
		for (int i = 0; i < calls; i++) {
			work.run(mc);
			work.run(mc);
		}
	}

	// A fixed pure-Java workload (loads, stores, a branch) of roughly the tick work's cost per call, the runner's yardstick.
	private static long reference(int calls) {
		long x = referenceState;
		long sum = 0;
		for (int i = 0; i < calls; i++) {
			for (int k = 0; k < 6; k++) {
				x ^= x << 13;
				x ^= x >>> 7;
				x ^= x << 17;
				int slot = (int) (x & 255);
				if ((x & 1) == 0) {
					REFERENCE[slot] += x;
				} else {
					sum += REFERENCE[slot];
				}
			}
			x += i;
		}
		referenceState = x;
		return sum;
	}

	private static double median(double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int mid = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
	}

	private static double round2(double value) {
		return Math.round(value * 100) / 100.0;
	}

	private static double round3(double value) {
		return Math.round(value * 1000) / 1000.0;
	}

	private static String cpuModel() {
		try {
			for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"), StandardCharsets.UTF_8)) {
				if (line.startsWith("model name")) {
					return line.substring(line.indexOf(':') + 1).trim();
				}
			}
		} catch (IOException | RuntimeException ignored) {
		}
		return System.getProperty("os.arch");
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
}
