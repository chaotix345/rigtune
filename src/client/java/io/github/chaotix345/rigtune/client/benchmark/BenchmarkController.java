package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecords;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRun;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkSession;
import io.github.chaotix345.rigtune.core.benchmark.FrameStats;
import io.github.chaotix345.rigtune.core.benchmark.KnobGuard;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.Protocol;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import io.github.chaotix345.rigtune.core.benchmark.Timing;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

// Runs a benchmark session from client ticks (docs/v0.2/SPEC.md item 6): for each core Step it sets the knobs, waits
// for the chunk sections to compile, sweeps the camera for the warm-up, then records frame times over the protocol's
// sweeps. While it runs the frame rate is uncapped, the GUI hidden and the player held in place; test values are in
// memory only and options.txt is written once, with the original values, at the end. Every way a run ends (finish,
// Esc or any screen, leaving the world, an exception, the client stopping) restores everything.
public final class BenchmarkController {
	public static final int MIN_RD = 4;
	public static final int MAX_RD = 32;
	private static final int MIN_SD = 5;
	private static final int READY_TICKS = 10;
	private static final int MAX_TARGET_FPS = 240;
	private static final String MAX_FPS = "vanilla.maxFps";
	private static final String VSYNC = "vanilla.enableVsync";
	private static final String INACTIVITY_LIMIT = "vanilla.inactivityFpsLimit";
	private static final Map<String, String> UNCAPPED = Map.of(
			MAX_FPS, Integer.toString(Options.UNLIMITED_FRAMERATE_CUTOFF),
			VSYNC, "false",
			INACTIVITY_LIMIT, "minimized");
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId(8000L);

	// maxSteps: render distance steps. The rest of the timing is derived: the defaults are docs/v0.2/SPEC.md's, and
	// shorter sweeps (game tests) shorten the warm-up and the quick protocol with them.
	public record Config(int maxSteps, double sweepSeconds, double settleSeconds, double timeoutSeconds) {
		public static final Config DEFAULT = new Config(6, 8.0, 2.0, 20.0);

		public Timing timing() {
			Timing d = Timing.DEFAULT;
			return new Timing(maxSteps, sweepSeconds, settleSeconds, timeoutSeconds, Math.min(d.warmupSeconds(), sweepSeconds / 2),
					Math.min(d.quickSeconds(), sweepSeconds), Math.min(d.quickSettleTimeoutSeconds(), timeoutSeconds), d.repeats(),
					d.deadlineSeconds());
		}
	}

	// record: the stored run (null when cancelled); before: the "before" of a Measure pair when this is its "after".
	public record Outcome(BenchmarkRequest request, SessionResult session, boolean cancelled, @Nullable BenchmarkRecord record,
			@Nullable BenchmarkRecord before) {
		public PlannerResult result() {
			return session.renderDistance();
		}

		public int originalRd() {
			return session.original().renderDistance();
		}

		public double targetFps() {
			return session.targetFps();
		}

		public BenchmarkMath.@Nullable Gain gain() {
			return record == null || before == null ? null : BenchmarkRecords.gain(before, record);
		}
	}

	private enum Phase { SETTLE, WARMUP, SWEEP }

	private record Pending(BenchmarkRequest request, Config config) {
	}

	private static @Nullable BenchmarkController active;
	private static @Nullable Outcome lastOutcome;
	private static @Nullable Pending pendingWorld;
	private static Config defaultConfig = Config.DEFAULT;

	private final Minecraft minecraft;
	private final BenchmarkRequest request;
	private final BenchmarkRun run;
	private final double targetFps;
	private final Map<String, String> originalSettings;
	private final ClientLevel level;
	private final LocalPlayer player;
	private final boolean hudWasHidden;
	private final Vec3 position;
	private final float yaw;
	private final float pitch;
	private final boolean wasFlying;
	private Step step;
	private Phase phase = Phase.SETTLE;
	private int sweep;
	private long phaseStart;
	private int readyTicks;
	private int stepCount;
	private float lastYaw;
	private float lastPitch;

	private BenchmarkController(Minecraft minecraft, LocalPlayer player, ClientLevel level, BenchmarkRequest request, Config config) {
		this.minecraft = minecraft;
		this.request = request;
		this.player = player;
		this.level = level;
		Options options = minecraft.options;
		Knobs original = new Knobs(options.renderDistance().get(), options.simulationDistance().get(), OptionalMods.dhRendering(),
				OptionalMods.shadersInUse());
		Map<String, String> originals = new LinkedHashMap<>();
		originals.put(ClientKnobs.RENDER_DISTANCE, Integer.toString(original.renderDistance()));
		originals.put(ClientKnobs.SIMULATION_DISTANCE, Integer.toString(original.simulationDistance()));
		originals.put(MAX_FPS, SettingsBridge.encode(options.framerateLimit()).orElseThrow());
		originals.put(VSYNC, SettingsBridge.encode(options.enableVsync()).orElseThrow());
		originals.put(INACTIVITY_LIMIT, SettingsBridge.encode(options.inactivityFpsLimit()).orElseThrow());
		this.originalSettings = originals;
		this.targetFps = Math.min(SettingValues.refreshRateCap(HardwareProbe.refreshRate(minecraft.getWindow())), MAX_TARGET_FPS);
		Timing timing = config.timing();
		long now = System.nanoTime();
		BenchmarkSession session = request.mode() == BenchmarkRequest.Mode.MEASURE
				? BenchmarkSession.measure(original, targetFps, timing, now)
				: BenchmarkSession.tune(original, new BenchmarkSession.TuneLimits(MIN_RD,
						Math.max(MIN_RD, Math.min(MAX_RD, maxRenderDistance(options, minecraft.hasSingleplayerServer()))), targetFps,
						minecraft.hasSingleplayerServer(), minSimulationDistance(options)), timing, now);
		this.run = new BenchmarkRun(session, new KnobGuard(original, new ClientKnobs(minecraft, original, MarkerRestore.file())), System::nanoTime);
		this.hudWasHidden = minecraft.gui.hud.isHidden();
		// In the benchmark world the camera goes exactly to the fixed spot, not just within a block of it.
		Vec3 camera = request.scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD ? BenchmarkWorld.cameraPosition() : null;
		this.position = camera != null ? camera : player.position();
		this.yaw = player.getYRot();
		this.pitch = player.getXRot();
		this.wasFlying = player.getAbilities().flying;
	}

	/** The v0.1 entry point: tune in the current world. */
	public static boolean start(Minecraft minecraft, Config config) {
		return tryStart(minecraft, BenchmarkRequest.DEFAULT, config) == null;
	}

	public static boolean start(Minecraft minecraft, BenchmarkRequest request, Config config) {
		return tryStart(minecraft, request, config) == null;
	}

	/** Starts the benchmark; returns null, or the language key of why it can't start. */
	public static @Nullable String tryStart(Minecraft minecraft, BenchmarkRequest request, Config config) {
		String refusal = unavailable(minecraft, request.scene());
		if (refusal != null) {
			return refusal;
		}
		// A marker left by a crashed run must be restored first, or its changed values would be taken as the originals.
		if (!MarkerRestore.settle()) {
			return "rigtune.benchmark.refused.restoring";
		}
		lastOutcome = null;
		if (request.scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD) {
			if (!BenchmarkWorld.open(minecraft, minecraft.gui.screen())) {
				return "rigtune.benchmark.refused.world_failed";
			}
			pendingWorld = new Pending(request, config);
			return null;
		}
		return begin(minecraft, request, config);
	}

	/** Why a benchmark in this scene can't start right now (a language key), or null. No side effects. */
	public static @Nullable String unavailable(Minecraft minecraft, BenchmarkRequest.Scene scene) {
		if (active != null || pendingWorld != null || BenchmarkWorld.busy()) {
			return "rigtune.benchmark.refused.running";
		}
		if (scene == BenchmarkRequest.Scene.BENCHMARK_WORLD) {
			if (!BenchmarkWorld.supported()) {
				return "rigtune.benchmark.refused.world_unsupported";
			}
			return minecraft.level != null ? "rigtune.benchmark.refused.leave_world" : null;
		}
		return minecraft.player == null || minecraft.level == null ? "rigtune.status.benchmark_unavailable" : null;
	}

	public static Config defaultConfig() {
		return defaultConfig;
	}

	/** Game tests use shorter runs. */
	public static void setDefaultConfig(Config config) {
		defaultConfig = config;
	}

	private static @Nullable String begin(Minecraft minecraft, BenchmarkRequest request, Config config) {
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) {
			return "rigtune.status.benchmark_unavailable";
		}
		BenchmarkController controller = new BenchmarkController(minecraft, player, level, request, config);
		active = controller;
		try {
			controller.prepare();
			RigTune.LOGGER.info("Benchmark started: {} in {}, target {} FPS (uncapped), start {}", request.mode(), request.scene(),
					controller.targetFps, controller.run.original());
			controller.nextStep();
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Benchmark could not start; restoring settings", e);
			controller.run.fail(e);
			controller.finish();
			return "rigtune.benchmark.refused.failed";
		}
		return null;
	}

	private void prepare() {
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
	}

	public static boolean running() {
		return active != null || pendingWorld != null;
	}

	public static @Nullable Outcome lastOutcome() {
		return lastOutcome;
	}

	public static @Nullable Component progress() {
		BenchmarkController c = active;
		if (c == null || c.step == null) {
			return null;
		}
		String phase = switch (c.phase) {
			case SETTLE -> "rigtune.benchmark.loading";
			case WARMUP -> "rigtune.benchmark.warming_up";
			case SWEEP -> "rigtune.benchmark.measuring";
		};
		return Component.translatable("rigtune.benchmark.progress", c.stepCount, c.describe(c.step), Component.translatable(phase));
	}

	private Component describe(Step s) {
		return switch (s.kind()) {
			case RENDER_DISTANCE -> Component.translatable("rigtune.benchmark.step.render_distance", s.knobs().renderDistance());
			case SIMULATION_DISTANCE -> Component.translatable("rigtune.benchmark.step.simulation_distance", s.knobs().simulationDistance());
			case REPEAT -> Component.translatable(request.mode() == BenchmarkRequest.Mode.MEASURE
					? "rigtune.benchmark.step.measure" : "rigtune.benchmark.step.repeat");
			case BASELINE -> Component.translatable("rigtune.benchmark.step.baseline");
			case DH_OFF -> Component.translatable("rigtune.benchmark.step.dh_off");
			case SHADERS_OFF -> Component.translatable("rigtune.benchmark.step.shaders_off");
		};
	}

	public static void tick(Minecraft minecraft) {
		MarkerRestore.tick(minecraft);
		BenchmarkWorld.tick(minecraft);
		Pending pending = pendingWorld;
		if (pending != null && active == null) {
			if (BenchmarkWorld.state() == BenchmarkWorld.State.READY) {
				pendingWorld = null;
				String refusal = begin(minecraft, pending.request(), pending.config());
				if (refusal != null) {
					RigTune.LOGGER.warn("Benchmark world: could not start the benchmark ({})", refusal);
					BenchmarkWorld.leave(minecraft, null);
				}
			} else if (!BenchmarkWorld.busy()) {
				pendingWorld = null;
				SystemToast.add(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.benchmark.world.failed"),
						Component.translatable("rigtune.benchmark.world.failed.body"));
			}
		}
		BenchmarkController c = active;
		if (c == null) {
			return;
		}
		try {
			c.onTick();
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Benchmark failed; restoring settings", e);
			c.run.fail(e);
			c.finish();
		}
	}

	public static void cancel() {
		pendingWorld = null;
		BenchmarkController c = active;
		if (c != null) {
			c.run.cancel();
			c.finish();
		}
	}

	private void nextStep() {
		Optional<Step> next = run.advance();
		if (next.isEmpty()) {
			finish();
			return;
		}
		step = next.get();
		stepCount++;
		enter(Phase.SETTLE);
		readyTicks = 0;
		sweep = 0;
	}

	private void enter(Phase next) {
		phase = next;
		phaseStart = System.nanoTime();
	}

	private void onTick() {
		if (!sameWorld() || minecraft.gui.screen() != null) {
			run.cancel();
			finish();
			return;
		}
		Protocol protocol = step.protocol();
		double elapsed = (System.nanoTime() - phaseStart) / 1e9;
		switch (phase) {
			case SETTLE -> {
				hold(yaw, 0);
				readyTicks = sectionsReady() ? readyTicks + 1 : 0;
				boolean settled = elapsed >= protocol.settleMinSeconds() && readyTicks >= READY_TICKS;
				if (settled || elapsed >= protocol.settleTimeoutSeconds()) {
					if (!settled) {
						RigTune.LOGGER.info("Benchmark: {} did not finish compiling within {} s", step.knobs(), protocol.settleTimeoutSeconds());
					}
					enter(Phase.WARMUP);
				}
			}
			case WARMUP -> {
				// Turn at the first sweep's speed so that the sweep starts exactly at the starting yaw.
				Protocol.Sweep first = protocol.sweeps().getFirst();
				double warmup = protocol.warmupSeconds();
				double progress = warmup <= 0 ? 1 : Math.min(1.0, elapsed / warmup);
				hold(yaw - (float) (360.0 * (1 - progress) * warmup / first.seconds()), first.pitch());
				if (elapsed >= warmup) {
					FrameTimes.start();
					sweep = 0;
					enter(Phase.SWEEP);
				}
			}
			case SWEEP -> {
				Protocol.Sweep current = protocol.sweeps().get(sweep);
				double progress = Math.min(1.0, elapsed / current.seconds());
				hold(yaw + (float) (360.0 * progress), current.pitch());
				if (progress >= 1.0) {
					if (sweep + 1 < protocol.sweeps().size()) {
						sweep++;
						lastYaw = yaw;
						enter(Phase.SWEEP);
					} else {
						FrameStats stats = FrameTimes.stop();
						RigTune.LOGGER.info("Benchmark {} {}: {} frames, avg {} FPS, 1% low {} FPS (frame limit {}, {})", step.kind(), step.knobs(),
								stats.frames(), Math.round(stats.avgFps()), Math.round(stats.onePercentLowFps()),
								minecraft.getFramerateLimitTracker().getFramerateLimit(), minecraft.getFramerateLimitTracker().getThrottleReason());
						run.record(stats);
						nextStep();
					}
				}
			}
		}
	}

	// Setting the previous rotation keeps the per-frame camera interpolation smooth between ticks.
	private void hold(float newYaw, float newPitch) {
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

	private void finish() {
		if (active != this) {
			return;
		}
		active = null;
		if (!run.finished()) {
			run.cancel();
		}
		// The knobs are back in memory (BenchmarkRun); this also writes options.txt once, with the original values.
		applySettings(originalSettings, "restore", true);
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
		if (!run.restoreOk()) {
			RigTune.LOGGER.error("Benchmark: could not restore every setting; see the errors above");
			MarkerRestore.retryLater();
		}
		if (run.error() != null) {
			RigTune.LOGGER.error("Benchmark failed", run.error());
		}
		Outcome outcome = null;
		try {
			outcome = outcome();
			lastOutcome = outcome;
			SessionResult result = outcome.session();
			RigTune.LOGGER.info("Benchmark {}: chosen {}, target {} FPS met {}, result {}, deadline hit {}",
					outcome.cancelled() ? "cancelled" : "finished", result.chosen(), targetFps, result.targetMet(), result.result(), result.deadlineHit());
		} finally {
			if (request.scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD && minecraft.isRunning()) {
				Outcome shown = outcome;
				BenchmarkWorld.leave(minecraft, shown == null ? null : () -> show(minecraft, shown, minecraft.gui.screen()));
			}
		}
		if (request.scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD) {
			return;
		}
		if (outcome.cancelled()) {
			if (minecraft.player != null) {
				minecraft.player.sendOverlayMessage(Component.translatable("rigtune.benchmark.cancelled"));
			}
			return;
		}
		show(minecraft, outcome, null);
	}

	private static void show(Minecraft minecraft, Outcome outcome, @Nullable Screen parent) {
		if (outcome.cancelled()) {
			SystemToast.add(minecraft.gui.toastManager(), TOAST_ID, Component.translatable("rigtune.benchmark.cancelled.title"),
					Component.translatable("rigtune.benchmark.cancelled.body"));
			return;
		}
		minecraft.gui.setScreen(new BenchmarkResultScreen(parent, outcome));
	}

	private Outcome outcome() {
		SessionResult result = run.result();
		if (run.cancelled()) {
			return new Outcome(request, result, true, null, null);
		}
		BenchmarkHistory history = BenchmarkStore.history();
		String phase = BenchmarkRecords.phase(request, history);
		BenchmarkRecord before = BenchmarkRecord.AFTER.equals(phase) && request.pairId() != null ? history.before(request.pairId()).orElse(null) : null;
		String createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
		String id = createdAt + "-" + HexFormat.of().toHexDigits((short) ThreadLocalRandom.current().nextInt());
		BenchmarkRecord.World world = request.scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD
				? new BenchmarkRecord.World(BenchmarkWorld.LEVEL_ID, BenchmarkWorld.SEED) : null;
		BenchmarkRecord record = BenchmarkRecords.of(result, request, phase, id, createdAt, rigtuneVersion(), HardwareProbe.minecraftVersion(), world);
		BenchmarkStore.add(record);
		return new Outcome(request, result, false, record, before);
	}

	private static String rigtuneVersion() {
		return FabricLoader.getInstance().getModContainer(RigTune.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	private static int minSimulationDistance(Options options) {
		return options.simulationDistance().values() instanceof OptionInstance.IntRangeBase range ? Math.max(MIN_SD, range.minInclusive()) : MIN_SD;
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
