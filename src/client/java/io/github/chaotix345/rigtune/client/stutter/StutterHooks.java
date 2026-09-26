package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

// The Stutter Doctor's Minecraft side (docs/v0.4/SPEC.md 5): the Fabric events it listens to, registered once by
// RigTuneClient (StutterMonitor.install), and the per-tick work. Every listener returns at once while nothing captures.
// - ClientChunkEvents.CHUNK_LOAD: the chunk-load counter. ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE: world join or
//   dimension change (the next 10 s are world loading, excluded).
// - ServerLifecycleEvents.BEFORE_SAVE/AFTER_SAVE (the integrated server's thread): save windows.
// - END_CLIENT_TICK: the session lifecycle, menus and an unfocused window (excluded), teleports (> 64 blocks in one tick;
//   the next 10 s are "after teleport") and fast movement (> 20 blocks/s), and the chunk-build backlog a few times a second.
public final class StutterHooks {
	static final double TELEPORT_BLOCKS = 64;
	static final double FAST_BLOCKS_PER_TICK = 1.0;

	private static @Nullable StutterService service;
	private static boolean sodium;
	private static int ticks;
	private static boolean hadPlayer;
	private static double lastX;
	private static double lastY;
	private static double lastZ;
	private static boolean fast;
	private static boolean failed;
	private static boolean wasActive;

	private StutterHooks() {
	}

	public static void install(StutterService stutterService) {
		service = stutterService;
		sodium = FabricLoader.getInstance().isModLoaded("sodium");
		ClientTickEvents.END_CLIENT_TICK.register(StutterHooks::tick);
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> StutterMonitor.chunkLoaded());
		ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register((minecraft, level) -> {
			hadPlayer = false;
			StutterMonitor.levelChanged(System.nanoTime());
		});
		ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> StutterMonitor.event(StutterRings.SAVE_BEGIN, System.nanoTime(), flush ? 1 : 0));
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> StutterMonitor.event(StutterRings.SAVE_END, System.nanoTime(), flush ? 1 : 0));
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
			StutterService s = service;
			if (s != null) {
				s.shutdown(minecraft);
			}
		});
	}

	private static void tick(Minecraft minecraft) {
		StutterService s = service;
		if (s == null || failed) {
			return;
		}
		try {
			s.tick(minecraft);
			DevStutter.tick(minecraft, s);
			boolean active = StutterMonitor.active();
			if (active && !wasActive) {
				hadPlayer = false;
				fast = false;
			}
			wasActive = active;
			if (!active) {
				return;
			}
			StutterMonitor.setExcluded(minecraft.gui.screen() != null || !minecraft.isWindowActive());
			movement(minecraft.player);
			if (++ticks % 5 == 0) {
				BuildBacklog.refresh(minecraft, sodium);
			}
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Stutter Doctor: tick failed; capture is off until the monitor is turned on again", e);
			failed = true;
			s.shutdown(minecraft);
		}
	}

	private static void movement(@Nullable LocalPlayer player) {
		if (player == null) {
			hadPlayer = false;
			return;
		}
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		if (hadPlayer) {
			double dx = x - lastX;
			double dy = y - lastY;
			double dz = z - lastZ;
			long now = System.nanoTime();
			if (dx * dx + dy * dy + dz * dz > TELEPORT_BLOCKS * TELEPORT_BLOCKS) {
				StutterMonitor.event(StutterRings.TELEPORT, now, 0);
			} else {
				boolean moving = dx * dx + dz * dz > FAST_BLOCKS_PER_TICK * FAST_BLOCKS_PER_TICK;
				if (moving != fast) {
					fast = moving;
					StutterMonitor.event(moving ? StutterRings.FAST_BEGIN : StutterRings.FAST_END, now, 0);
				}
			}
		}
		hadPlayer = true;
		lastX = x;
		lastY = y;
		lastZ = z;
	}

	// Turning the monitor on again after a failed tick tries once more.
	static void retry() {
		failed = false;
	}

	// BenchmarkController: started before the run changes any setting (a running session ends); capture on for each sweep,
	// off in between; finished when the run ends (keep: it wasn't cancelled).
	public static void benchmarkStarted() {
		StutterService s = service;
		if (s != null) {
			try {
				s.benchmarkStarted(Minecraft.getInstance());
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Stutter Doctor: could not end the session for the benchmark", e);
			}
		}
	}

	public static void benchmarkSweep(boolean recording) {
		StutterService s = service;
		if (s != null) {
			try {
				s.benchmarkSweep(Minecraft.getInstance(), recording);
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Stutter Doctor: benchmark capture failed", e);
			}
		}
	}

	public static void benchmarkFinished(boolean keep) {
		StutterService s = service;
		if (s != null) {
			try {
				s.benchmarkFinished(Minecraft.getInstance(), keep);
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Stutter Doctor: benchmark analysis failed", e);
			}
		}
	}

	// The last finished benchmark's capture summary (BenchmarkResultScreen's line), or null.
	public static @Nullable StutterReport lastBenchmark() {
		StutterService s = service;
		return s == null ? null : s.lastBenchmark();
	}

	// For StutterGameTest (AC5.7).
	public static boolean gcListenerActive() {
		return StutterCapture.GC.active();
	}

	public static boolean samplerRunning() {
		return StutterCapture.SAMPLER.running();
	}

	// For BenchmarkGameTest (review-8 P5A-F3): monitor sessions whose end was handled, saved or not.
	public static int sessionsEnded() {
		StutterService s = service;
		return s == null ? 0 : s.sessionsEnded();
	}
}
