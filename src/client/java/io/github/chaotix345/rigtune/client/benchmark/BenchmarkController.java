package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.core.benchmark.FrameStats;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.RenderDistancePlanner;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.OptionalInt;

public final class BenchmarkController {
	public static final int MIN_RD = 4;
	public static final int MAX_RD = 32;
	private static final int READY_TICKS = 10;

	public record Config(int maxSteps, double sweepSeconds, double settleSeconds, double timeoutSeconds) {
		public static final Config DEFAULT = new Config(6, 6.0, 2.0, 20.0);
	}

	public record Outcome(PlannerResult result, int originalRd, double targetFps, boolean cancelled) {
	}

	private enum Phase { SETTLE, SWEEP_LEVEL, SWEEP_DOWN }

	private static @Nullable BenchmarkController active;
	private static @Nullable Outcome lastOutcome;

	private final Minecraft minecraft;
	private final Config config;
	private final RenderDistancePlanner planner;
	private final double targetFps;
	private final int originalRd;
	private final boolean hudWasHidden;
	private final Vec3 position;
	private final float yaw;
	private final float pitch;
	private final boolean wasFlying;
	private final int steps;
	private Phase phase = Phase.SETTLE;
	private int rd;
	private int step;
	private long phaseStart;
	private int readyTicks;
	private float lastYaw;
	private float lastPitch;

	private BenchmarkController(Minecraft minecraft, LocalPlayer player, Config config) {
		this.minecraft = minecraft;
		this.config = config;
		Options options = minecraft.options;
		this.originalRd = options.renderDistance().get();
		int refresh = minecraft.getWindow().getRefreshRate();
		this.targetFps = refresh > 0 ? Math.min(refresh, 240) : 60;
		int maxRd = Math.max(MIN_RD, Math.min(MAX_RD, maxRenderDistance(options)));
		this.planner = new RenderDistancePlanner(MIN_RD, maxRd, originalRd, targetFps, config.maxSteps());
		this.steps = config.maxSteps();
		this.hudWasHidden = minecraft.gui.hud.isHidden();
		this.position = player.position();
		this.yaw = player.getYRot();
		this.pitch = player.getXRot();
		this.wasFlying = player.getAbilities().flying;
	}

	public static boolean start(Minecraft minecraft, Config config) {
		LocalPlayer player = minecraft.player;
		if (active != null || player == null || minecraft.level == null) {
			return false;
		}
		BenchmarkController controller = new BenchmarkController(minecraft, player, config);
		OptionalInt first = controller.planner.next();
		if (first.isEmpty()) {
			return false;
		}
		active = controller;
		lastOutcome = null;
		minecraft.gui.setScreen(null);
		minecraft.mouseHandler.releaseMouse();
		if (!controller.hudWasHidden) {
			minecraft.gui.hud.toggle();
		}
		if (player.getAbilities().mayfly && !player.getAbilities().flying) {
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}
		controller.lastYaw = controller.yaw;
		controller.lastPitch = 0;
		controller.beginStep(first.getAsInt());
		RigTune.LOGGER.info("Benchmark started: target {} FPS, start RD {}, max steps {}", controller.targetFps, controller.originalRd, config.maxSteps());
		return true;
	}

	public static boolean running() {
		return active != null;
	}

	public static @Nullable Outcome lastOutcome() {
		return lastOutcome;
	}

	public static @Nullable Component progress() {
		BenchmarkController c = active;
		if (c == null) {
			return null;
		}
		String phase = c.phase == Phase.SETTLE ? "rigtune.benchmark.loading" : "rigtune.benchmark.measuring";
		return Component.translatable("rigtune.benchmark.progress", c.step, c.steps, c.rd, Component.translatable(phase));
	}

	public static void tick(Minecraft minecraft) {
		BenchmarkController c = active;
		if (c != null) {
			c.onTick();
		}
	}

	public static void cancel() {
		BenchmarkController c = active;
		if (c != null) {
			c.finish(true);
		}
	}

	private void beginStep(int nextRd) {
		rd = nextRd;
		step++;
		SettingsBridge.applyVanilla(minecraft.options, Map.of("vanilla.renderDistance", Integer.toString(rd)));
		phase = Phase.SETTLE;
		phaseStart = System.nanoTime();
		readyTicks = 0;
	}

	private void onTick() {
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			finish(true);
			return;
		}
		if (minecraft.gui.screen() != null) {
			finish(true);
			return;
		}
		double elapsed = (System.nanoTime() - phaseStart) / 1e9;
		switch (phase) {
			case SETTLE -> {
				hold(player, yaw, 0);
				readyTicks = sectionsReady() ? readyTicks + 1 : 0;
				boolean settled = elapsed >= config.settleSeconds() && readyTicks >= READY_TICKS;
				if (settled || elapsed >= config.timeoutSeconds()) {
					if (!settled) {
						RigTune.LOGGER.info("Benchmark: RD {} did not finish compiling within {} s", rd, config.timeoutSeconds());
					}
					FrameTimes.start();
					phase = Phase.SWEEP_LEVEL;
					phaseStart = System.nanoTime();
				}
			}
			case SWEEP_LEVEL, SWEEP_DOWN -> {
				double progress = Math.min(1.0, elapsed / config.sweepSeconds());
				float sweepPitch = phase == Phase.SWEEP_LEVEL ? 0f : -25f;
				hold(player, yaw + (float) (360.0 * progress), sweepPitch);
				if (progress >= 1.0) {
					if (phase == Phase.SWEEP_LEVEL) {
						phase = Phase.SWEEP_DOWN;
						phaseStart = System.nanoTime();
						lastYaw = yaw;
					} else {
						FrameStats stats = FrameTimes.stop();
						planner.record(rd, stats);
						RigTune.LOGGER.info("Benchmark RD {}: {} frames, avg {} FPS, 1% low {} FPS", rd, stats.frames(),
								Math.round(stats.avgFps()), Math.round(stats.onePercentLowFps()));
						OptionalInt next = planner.next();
						if (next.isPresent()) {
							beginStep(next.getAsInt());
						} else {
							finish(false);
						}
					}
				}
			}
		}
	}

	// Setting the previous rotation keeps the per-frame camera interpolation smooth between ticks.
	private void hold(LocalPlayer player, float newYaw, float newPitch) {
		player.setDeltaMovement(Vec3.ZERO);
		player.snapTo(position.x, position.y, position.z, newYaw, newPitch);
		player.yRotO = lastYaw;
		player.xRotO = lastPitch;
		player.setYHeadRot(newYaw);
		lastYaw = newYaw;
		lastPitch = newPitch;
	}

	private void finish(boolean cancelled) {
		if (active != this) {
			return;
		}
		active = null;
		if (FrameTimes.recording()) {
			FrameTimes.stop();
		}
		SettingsBridge.applyVanilla(minecraft.options, Map.of("vanilla.renderDistance", Integer.toString(originalRd)));
		if (minecraft.gui.hud.isHidden() != hudWasHidden) {
			minecraft.gui.hud.toggle();
		}
		LocalPlayer player = minecraft.player;
		if (player != null) {
			player.snapTo(position.x, position.y, position.z, yaw, pitch);
			player.setDeltaMovement(Vec3.ZERO);
			if (player.getAbilities().flying != wasFlying) {
				player.getAbilities().flying = wasFlying;
				player.onUpdateAbilities();
			}
		}
		PlannerResult result = planner.result();
		lastOutcome = new Outcome(result, originalRd, targetFps, cancelled);
		RigTune.LOGGER.info("Benchmark {}: best RD {}, target met {}, {}", cancelled ? "cancelled" : "finished",
				result.bestRd(), result.targetMet(), result.reason());
		if (cancelled) {
			if (player != null) {
				player.sendOverlayMessage(Component.translatable("rigtune.benchmark.cancelled"));
			}
			return;
		}
		minecraft.gui.setScreen(new BenchmarkResultScreen(null, lastOutcome));
	}

	static int maxRenderDistance(Options options) {
		int max = MAX_RD;
		OptionInstance.ValueSet<Integer> values = options.renderDistance().values();
		if (values instanceof OptionInstance.IntRangeBase range) {
			max = Math.min(max, range.maxInclusive());
		}
		try {
			Field field = Options.class.getDeclaredField("serverRenderDistance");
			field.setAccessible(true);
			int server = field.getInt(options);
			if (server > 0) {
				max = Math.min(max, server);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			RigTune.LOGGER.debug("Server render distance unavailable", e);
		}
		return max;
	}

	private boolean sectionsReady() {
		if (FabricLoader.getInstance().isModLoaded("sodium")) {
			Boolean sodium = sodiumTerrainComplete();
			if (sodium != null) {
				return sodium;
			}
		}
		return minecraft.levelRenderer.hasRenderedAllSections();
	}

	private static @Nullable Method sodiumInstance;
	private static @Nullable Method sodiumComplete;
	private static boolean sodiumLookupFailed;

	private static @Nullable Boolean sodiumTerrainComplete() {
		if (sodiumLookupFailed) {
			return null;
		}
		try {
			if (sodiumInstance == null) {
				Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
				sodiumInstance = renderer.getMethod("instanceNullable");
				sodiumComplete = renderer.getMethod("isTerrainRenderComplete");
			}
			Object instance = sodiumInstance.invoke(null);
			return instance == null ? null : (Boolean) sodiumComplete.invoke(instance);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			RigTune.LOGGER.warn("Sodium terrain status unavailable, using vanilla", e);
			sodiumLookupFailed = true;
			return null;
		}
	}
}
