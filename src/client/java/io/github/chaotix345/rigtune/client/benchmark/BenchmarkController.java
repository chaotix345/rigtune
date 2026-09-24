package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.core.benchmark.FrameStats;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.RenderDistancePlanner;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;

public final class BenchmarkController {
	public static final int MIN_RD = 4;
	public static final int MAX_RD = 32;
	private static final int READY_TICKS = 10;
	private static final int MAX_TARGET_FPS = 240;
	private static final String RENDER_DISTANCE = "vanilla.renderDistance";
	private static final String MAX_FPS = "vanilla.maxFps";
	private static final String VSYNC = "vanilla.enableVsync";
	private static final String INACTIVITY_LIMIT = "vanilla.inactivityFpsLimit";
	private static final Map<String, String> UNCAPPED = Map.of(
			MAX_FPS, Integer.toString(Options.UNLIMITED_FRAMERATE_CUTOFF),
			VSYNC, "false",
			INACTIVITY_LIMIT, "minimized");

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
	private final Map<String, String> originalSettings;
	private final ClientLevel level;
	private final LocalPlayer player;
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
	private boolean settingsRestored;

	private BenchmarkController(Minecraft minecraft, LocalPlayer player, ClientLevel level, Config config) {
		this.minecraft = minecraft;
		this.config = config;
		this.player = player;
		this.level = level;
		Options options = minecraft.options;
		this.originalRd = options.renderDistance().get();
		Map<String, String> originals = new LinkedHashMap<>();
		originals.put(RENDER_DISTANCE, Integer.toString(originalRd));
		originals.put(MAX_FPS, SettingsBridge.encode(options.framerateLimit()).orElseThrow());
		originals.put(VSYNC, SettingsBridge.encode(options.enableVsync()).orElseThrow());
		originals.put(INACTIVITY_LIMIT, SettingsBridge.encode(options.inactivityFpsLimit()).orElseThrow());
		this.originalSettings = Map.copyOf(originals);
		this.targetFps = Math.min(SettingValues.refreshRateCap(minecraft.getWindow().getRefreshRate()), MAX_TARGET_FPS);
		int maxRd = Math.max(MIN_RD, Math.min(MAX_RD, maxRenderDistance(options, minecraft.hasSingleplayerServer())));
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
		ClientLevel level = minecraft.level;
		if (active != null || player == null || level == null) {
			return false;
		}
		BenchmarkController controller = new BenchmarkController(minecraft, player, level, config);
		OptionalInt first = controller.planner.next();
		if (first.isEmpty()) {
			return false;
		}
		active = controller;
		lastOutcome = null;
		try {
			controller.begin(first.getAsInt());
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Benchmark could not start; restoring settings", e);
			controller.finish(true);
			return false;
		}
		RigTune.LOGGER.info("Benchmark started: target {} FPS (uncapped), start RD {}, max steps {}", controller.targetFps, controller.originalRd, config.maxSteps());
		return true;
	}

	private void begin(int firstRd) {
		applySettings(UNCAPPED, "uncap", false);
		minecraft.gui.setScreen(null);
		minecraft.mouseHandler.releaseMouse();
		if (!hudWasHidden) {
			minecraft.gui.hud.toggle();
		}
		if (player.getAbilities().mayfly && !player.getAbilities().flying) {
			player.getAbilities().flying = true;
		}
		lastYaw = yaw;
		lastPitch = 0;
		beginStep(firstRd);
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
		if (c == null) {
			return;
		}
		try {
			c.onTick();
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Benchmark failed; restoring settings", e);
			c.finish(true);
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
		applySettings(Map.of(RENDER_DISTANCE, Integer.toString(rd)), "render distance", false);
		phase = Phase.SETTLE;
		phaseStart = System.nanoTime();
		readyTicks = 0;
	}

	private void onTick() {
		if (!sameWorld()) {
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
				float sweepPitch = phase == Phase.SWEEP_LEVEL ? 0f : 25f;
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

	private boolean sameWorld() {
		return minecraft.player == player && minecraft.level == level;
	}

	private void applySettings(Map<String, String> values, String what, boolean save) {
		SettingsBridge.applyVanilla(minecraft.options, values, save).values().stream()
				.filter(r -> !r.ok())
				.forEach(r -> RigTune.LOGGER.warn("Benchmark {}: could not set {} to {}: {}", what, r.key(), values.get(r.key()), r.message()));
	}

	private void restoreSettings() {
		if (settingsRestored) {
			return;
		}
		settingsRestored = true;
		applySettings(originalSettings, "restore", true);
	}

	private void finish(boolean cancelled) {
		if (active != this) {
			return;
		}
		active = null;
		restoreSettings();
		if (FrameTimes.recording()) {
			FrameTimes.stop();
		}
		if (minecraft.gui.hud.isHidden() != hudWasHidden) {
			minecraft.gui.hud.toggle();
		}
		if (sameWorld()) {
			player.snapTo(position.x, position.y, position.z, yaw, pitch);
			player.setDeltaMovement(Vec3.ZERO);
			player.getAbilities().flying = wasFlying;
		}
		PlannerResult result = planner.result();
		lastOutcome = new Outcome(result, originalRd, targetFps, cancelled);
		RigTune.LOGGER.info("Benchmark {}: suggested RD {}, target {} FPS met {}, {}", cancelled ? "cancelled" : "finished",
				result.suggestedRd(), targetFps, result.targetMet(), result.reason());
		if (cancelled) {
			if (minecraft.player != null) {
				minecraft.player.sendOverlayMessage(Component.translatable("rigtune.benchmark.cancelled"));
			}
			return;
		}
		minecraft.gui.setScreen(new BenchmarkResultScreen(null, lastOutcome));
	}

	static int maxRenderDistance(Options options, boolean integratedServer) {
		int max = MAX_RD;
		OptionInstance.ValueSet<Integer> values = options.renderDistance().values();
		if (values instanceof OptionInstance.IntRangeBase range) {
			max = Math.min(max, range.maxInclusive());
		}
		if (integratedServer) {
			return max;
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
