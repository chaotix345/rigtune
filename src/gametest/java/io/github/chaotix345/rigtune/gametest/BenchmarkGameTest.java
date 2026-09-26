package io.github.chaotix345.rigtune.gametest;

import com.google.gson.JsonArray;
import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.benchmark.MarkerRestore;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.stutter.StutterHooks;
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.FrameStats;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.SceneVariety;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.benchmark.ShaderAdvice;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.IntPredicate;

// Benchmark v2 (docs/v0.2/SPEC.md AC6.2, AC6.3) and its v0.3 follow-ups (docs/v0.3/SPEC.md 3f, 8). Numbers aren't
// asserted: the harness's tick sync makes 1% lows unrepresentative. What is asserted: the runs complete, everything is
// restored, the pair and history are stored, each step measures the terrain of its render distance, the server is told
// the restored render distance, and the benchmark world's scene and camera are as designed.
public class BenchmarkGameTest implements FabricClientGameTest {
	private static final int WORLD_TIMEOUT_TICKS = 20 * 150;
	private static final int RUN_TIMEOUT_TICKS = 20 * 240;
	private static final int CHUNK_RUN_TIMEOUT_TICKS = 20 * 600;
	private static final BenchmarkController.Config SHORT = new BenchmarkController.Config(3, 1.0, 0.5, 10.0);
	// 3f (AC3.6, E-M3): every step passes (target 1 FPS) and the search stops at 12, so a Tune from the harness's RD 5
	// steps 5, 7, 11, 12; each step may wait 60 s for its terrain on a slow CI runner.
	private static final int CHUNK_RD = 12;
	private static final BenchmarkController.Config CHUNKS = new BenchmarkController.Config(4, 1.0, 0.5, 60.0, 1.0, CHUNK_RD);

	// The chunks missing within rd - 1 of the camera when a render distance step recorded its first frame.
	private record FirstFrame(int rd, int inRange, int missing) {
	}

	// The benchmark world as the server sees it: the scene check, the camera spot and its terrain floor.
	private record WorldCheck(SceneVariety.Report scene, int floor, int cameraY, boolean cameraClear) {
	}

	private record Settings(int rd, int sd, int maxFps, boolean vsync, InactivityFpsLimit inactivity, boolean hudHidden) {
		static Settings of(Minecraft mc) {
			return new Settings(mc.options.renderDistance().get(), mc.options.simulationDistance().get(), mc.options.framerateLimit().get(),
					mc.options.enableVsync().get(), mc.options.inactivityFpsLimit().get(), mc.gui.hud.isHidden());
		}
	}

	private record Recorded(String entryId, String kind, List<JournalChange> changes) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.runOnClient(mc -> BenchmarkController.setDefaultConfig(SHORT));
		String savedScene = context.computeOnClient(mc -> ClientSettings.shared(FabricLoader.getInstance().getConfigDir()).benchmarkScene);
		// v0.4: later classes use the real notice line, so the runs made here don't stay behind (a stale benchmark notice).
		byte[] savedRuns = readIfPresent(BenchmarkStore.file());
		try {
			String[] pair = benchmarkWorldPair(context);
			String chunkTuneId = benchmarkWorldChunks(context);
			benchmarkWorldCancel(context);
			String tuneId = currentWorldTune(context);
			checkHistoryFile(pair, tuneId, chunkTuneId);
			shaderAdviceScreen(context);
		} finally {
			restoreFile(BenchmarkStore.file(), savedRuns);
			context.runOnClient(mc -> {
				BenchmarkController.setDefaultConfig(BenchmarkController.Config.DEFAULT);
				BenchmarkController.setSweepListener(null);
				ClientSettings.shared(FabricLoader.getInstance().getConfigDir()).benchmarkScene = savedScene;
			});
		}
	}

	private static List<StutterReport> stutterSessionsSince(Path configDir, Instant since) {
		return new StutterStore(configDir).sessions().stream().filter(r -> !Instant.parse(r.startedAt()).isBefore(since)).toList();
	}

	private static byte[] readIfPresent(Path file) {
		try {
			return Files.exists(file) ? Files.readAllBytes(file) : null;
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void restoreFile(Path file, byte[] bytes) {
		try {
			if (bytes == null) {
				Files.deleteIfExists(file);
			} else {
				Files.write(file, bytes);
			}
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// AC6.3 and the Measure pair of AC6.2: from the title screen, Measure before and Measure after in the benchmark world.
	private static String[] benchmarkWorldPair(ClientGameTestContext context) {
		setScene(context, BenchmarkRequest.Scene.BENCHMARK_WORLD);
		openMenu(context);
		context.takeScreenshot("bench-menu-title");
		check(!buttonActive(context, "rigtune.benchmark.menu.measure_after"), "Measure after needs a before first");
		Settings settings = context.computeOnClient(Settings::of);

		pressByKey(context, "rigtune.benchmark.menu.measure_before");
		BenchmarkRecord before = runInBenchmarkWorld(context, "bench-world-running", "bench-world-before");
		check(BenchmarkRecord.BEFORE.equals(before.phase()), "first run is the before: " + before);
		check(context.computeOnClient(Settings::of).equals(settings), "settings restored after the world run");
		Path marker = context.computeOnClient(BenchmarkWorld::markerPath);
		check(Files.isRegularFile(marker), "benchmark world marker written");
		FileTime created = modified(marker);
		pressByKey(context, "gui.done");
		context.waitForScreen(TitleScreen.class);

		// review-8 P5A-F3: the Measure after runs with the session monitor on; the benchmark world's settle frames aren't saved
		// as a session of their own, the run's own capture is.
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Instant monitorOn = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		int endedBefore = context.computeOnClient(mc -> StutterHooks.sessionsEnded());
		context.runOnClient(mc -> RigTuneClient.controller().setStutterMonitor(true));
		check(context.computeOnClient(mc -> ClientSettings.shared(configDir).stutterMonitor), "the session monitor is on for the Measure after run");
		BenchmarkRecord after;
		try {
			openMenu(context);
			check(buttonActive(context, "rigtune.benchmark.menu.measure_after"), "Measure after is offered");
			pressByKey(context, "rigtune.benchmark.menu.measure_after");
			after = runInBenchmarkWorld(context, null, "bench-world-after");
		} finally {
			context.runOnClient(mc -> RigTuneClient.controller().setStutterMonitor(false));
		}
		context.waitFor(mc -> stutterSessionsSince(configDir, monitorOn).stream().anyMatch(r -> StutterReport.BENCHMARK.equals(r.source())), 400);
		// The benchmark world's own monitor session ended with the world and was handled (saved or left out).
		context.waitFor(mc -> StutterHooks.sessionsEnded() > endedBefore, 400);
		List<StutterReport> sinceOn = stutterSessionsSince(configDir, monitorOn);
		check(sinceOn.stream().noneMatch(r -> StutterReport.MONITOR.equals(r.source())), "no settle-frames session saved around the benchmark: "
				+ sinceOn.stream().map(r -> r.source() + " " + r.spikes().total() + " spikes in " + r.gameplaySeconds() + " s").toList());
		check(BenchmarkRecord.AFTER.equals(after.phase()) && before.pairId().equals(after.pairId()), "after pairs with before: " + after);
		check(context.computeOnClient(mc -> BenchmarkController.lastOutcome().gain()) != null, "gain computed for the pair");
		check(modified(marker).equals(created), "benchmark world reused, not recreated");
		pressByKey(context, "gui.done");
		context.waitForScreen(TitleScreen.class);
		return new String[]{before.id(), after.id()};
	}

	// docs/v0.3/SPEC.md 3f (AC3.6, AC8.2, AC8.3) in the benchmark world, plus the scene guard and the camera (AC8.5,
	// AC8.6): a Tune from the harness's RD 5 up to 12. At the RD 12 step's first frame every chunk within 11 of the camera
	// must be on the client, and its settle must not have timed out; afterwards the server must have been told the
	// restored render distance.
	private static String benchmarkWorldChunks(ClientGameTestContext context) {
		setScene(context, BenchmarkRequest.Scene.BENCHMARK_WORLD);
		Settings settings = context.computeOnClient(Settings::of);
		check(settings.rd() < CHUNK_RD - 2, "the harness starts at a low render distance: " + settings.rd());
		List<FirstFrame> firstFrames = new CopyOnWriteArrayList<>();
		context.runOnClient(mc -> {
			BenchmarkController.setDefaultConfig(CHUNKS);
			BenchmarkController.setSweepListener(step -> {
				if (step.kind() == Step.Kind.RENDER_DISTANCE) {
					firstFrames.add(firstFrame(Minecraft.getInstance(), step.knobs().renderDistance()));
				}
			});
		});
		try {
			openMenu(context);
			pressByKey(context, "rigtune.benchmark.menu.tune");
			context.waitFor(mc -> BenchmarkWorld.awaitingExit(), CHUNK_RUN_TIMEOUT_TICKS);
			BenchmarkController.Outcome outcome = context.computeOnClient(mc -> BenchmarkController.lastOutcome());
			check(outcome != null && !outcome.cancelled() && outcome.record() != null, "chunk tune finished: " + outcome);
			for (BenchmarkController.Settled s : outcome.settles()) {
				RigTune.LOGGER.info("Benchmark game test: settle {} RD {}: {} of {} chunks within {} present, client holds {}, {} s{}",
						s.step().kind(), s.step().knobs().renderDistance(), s.settle().inRange() - s.settle().missing(), s.settle().inRange(),
						s.settle().radius(), s.loadedChunks(), String.format(Locale.ROOT, "%.1f", s.settle().seconds()),
						s.settle().timedOut() ? " (timed out)" : "");
			}
			RigTune.LOGGER.info("Benchmark game test: first frames {}", firstFrames);
			BenchmarkController.Settled atTwelve = outcome.settles().stream()
					.filter(s -> s.step().kind() == Step.Kind.RENDER_DISTANCE && s.step().knobs().renderDistance() == CHUNK_RD)
					.findFirst().orElseThrow(() -> new AssertionError("no RD " + CHUNK_RD + " step: " + outcome.result()));
			check(!atTwelve.settle().timedOut(), "the RD " + CHUNK_RD + " step settled before the timeout: " + atTwelve);
			RigTune.LOGGER.info("Benchmark game test: RD {} settled in {} s (MC {})", CHUNK_RD,
					String.format(Locale.ROOT, "%.1f", atTwelve.settle().seconds()), HardwareProbe.minecraftVersion());
			FirstFrame frame = firstFrames.stream().filter(f -> f.rd() == CHUNK_RD).findFirst()
					.orElseThrow(() -> new AssertionError("no first frame at RD " + CHUNK_RD + ": " + firstFrames));
			check(frame.inRange() == 377 && frame.missing() == 0, "every chunk within 11 is loaded at the RD 12 step's first frame: " + frame);

			int restoredRd = context.computeOnClient(mc -> mc.options.renderDistance().get());
			check(restoredRd == settings.rd(), "render distance restored: " + restoredRd);
			checkRequestedViewDistance(context, restoredRd, "after the finished Tune");

			WorldCheck world = onServer(context, server -> worldCheck(server));
			RigTune.LOGGER.info("Benchmark game test: scene fingerprint (MC {}): {}", HardwareProbe.minecraftVersion(), world.scene().fingerprint());
			check(world.scene().accepted(), "the benchmark scene passes SceneVariety: " + world.scene().rejection() + " " + world.scene().fingerprint());
			RigTune.LOGGER.info("Benchmark game test: camera y {} = terrain floor {} + {}, camera block and the one above air {}",
					world.cameraY(), world.floor(), BenchmarkWorld.CAMERA_ABOVE_FLOOR, world.cameraClear());
			check(world.cameraY() == world.floor() + BenchmarkWorld.CAMERA_ABOVE_FLOOR, "camera 16 above the terrain floor: " + world);
			check(world.cameraClear(), "the camera block and the one above are air: " + world);

			context.runOnClient(BenchmarkWorld::exitNow);
			context.waitForScreen(BenchmarkResultScreen.class);
			context.waitTicks(3);
			context.takeScreenshot("bench-world-tune-result");
			pressByKey(context, "rigtune.benchmark.keep");
			context.waitForScreen(TitleScreen.class);
			check(context.computeOnClient(Settings::of).equals(settings), "settings restored after the chunk tune");
			return outcome.record().id();
		} finally {
			context.runOnClient(mc -> {
				BenchmarkController.setDefaultConfig(SHORT);
				BenchmarkController.setSweepListener(null);
			});
		}
	}

	// Counted here, independently of SettleCheck: the circle dx^2 + dz^2 <= (rd - 1)^2 around the camera's chunk.
	private static FirstFrame firstFrame(Minecraft mc, int rd) {
		Vec3 camera = mc.player.position();
		int cx = ((int) Math.floor(camera.x)) >> 4;
		int cz = ((int) Math.floor(camera.z)) >> 4;
		int r = rd - 1;
		int inRange = 0;
		int missing = 0;
		for (int dx = -r; dx <= r; dx++) {
			for (int dz = -r; dz <= r; dz++) {
				if (dx * dx + dz * dz <= r * r) {
					inRange++;
					if (!mc.level.getChunkSource().hasChunk(cx + dx, cz + dz)) {
						missing++;
					}
				}
			}
		}
		return new FirstFrame(rd, inRange, missing);
	}

	// Server thread: the terrain noise around the camera (SceneVariety), and the camera spot.
	private static WorldCheck worldCheck(IntegratedServer server) {
		ServerLevel level = server.overworld();
		List<SceneVariety.Sample> samples = new ArrayList<>();
		for (int[] o : SceneVariety.offsets()) {
			int x = BenchmarkWorld.CAMERA_X + o[0];
			int z = BenchmarkWorld.CAMERA_Z + o[1];
			int floor = BenchmarkWorld.terrainFloor(level, x, z);
			// 26.4 (from snapshot 1) renamed getUncachedNoiseBiome to getUncachedBiome (same parameters); ">=26.4-alpha" as in
			// BenchmarkWorld.terrainFloor.
			//? if >=26.4-alpha {
			/*samples.add(new SceneVariety.Sample(o[0], o[1], floor, level.getUncachedBiome(x >> 2, floor >> 2, z >> 2).getRegisteredName()));
			*///?} else
			samples.add(new SceneVariety.Sample(o[0], o[1], floor, level.getUncachedNoiseBiome(x >> 2, floor >> 2, z >> 2).getRegisteredName()));
		}
		SceneVariety.Report scene = SceneVariety.check(samples, level.getChunkSource().getGenerator().getSeaLevel());
		Vec3 camera = BenchmarkWorld.cameraPosition();
		BlockPos spot = BlockPos.containing(camera.x, camera.y, camera.z);
		boolean clear = level.getBlockState(spot).isAir() && level.getBlockState(spot.above()).isAir();
		return new WorldCheck(scene, BenchmarkWorld.terrainFloor(level, BenchmarkWorld.CAMERA_X, BenchmarkWorld.CAMERA_Z), spot.getY(), clear);
	}

	// docs/v0.3/SPEC.md AC8.3: the integrated server's requested view distance (what it sends chunks for) equals the
	// restored render distance. The packet is handled on a server tick, so this polls.
	private static void checkRequestedViewDistance(ClientGameTestContext context, int expected, String when) {
		int requested = awaitRequestedViewDistance(context, rd -> rd == expected, when);
		RigTune.LOGGER.info("Benchmark game test: server's requested view distance {} {} (render distance {})", requested, when, expected);
	}

	// Polls the integrated server's requested view distance of the player until it matches (the packet is handled on a
	// server tick), for up to about 200 ticks; fails otherwise.
	private static int awaitRequestedViewDistance(ClientGameTestContext context, IntPredicate wanted, String when) {
		int requested = -1;
		for (int i = 0; i < 100; i++) {
			if (i > 0) {
				context.waitTicks(1);
			}
			requested = onServer(context, server -> {
				List<ServerPlayer> players = server.getPlayerList().getPlayers();
				return players.isEmpty() ? -1 : players.getFirst().requestedViewDistance();
			});
			if (wanted.test(requested)) {
				return requested;
			}
		}
		throw new AssertionError("Check failed: the server's requested view distance " + when + " is " + requested);
	}

	// Runs a task on the integrated server's thread. The harness keeps the game threads in step with this thread, so it
	// never blocks on the result: it waits in ticks until the task has run.
	private static <T> T onServer(ClientGameTestContext context, Function<IntegratedServer, T> task) {
		CompletableFuture<T> result = new CompletableFuture<>();
		context.runOnClient(mc -> {
			IntegratedServer server = mc.getSingleplayerServer();
			if (server == null) {
				result.completeExceptionally(new AssertionError("no integrated server"));
				return;
			}
			server.execute(() -> {
				try {
					result.complete(task.apply(server));
				} catch (Throwable t) {
					result.completeExceptionally(t);
				}
			});
		});
		context.waitFor(mc -> result.isDone(), 20 * 60);
		return result.join();
	}

	// docs/v0.3/SPEC.md 8a: how the result screen shows the shader advice (the rule itself is ShaderAdviceTest's). A made-up
	// Tune whose shaders take the 1% lows from 200 to 120 against a 170 FPS target; nothing is stored or changed.
	private static void shaderAdviceScreen(ClientGameTestContext context) {
		Knobs knobs = new Knobs(12, 10, false, true);
		FrameStats stats = new FrameStats(1000, 300, 120, 8.3, 12.0);
		SessionResult.Cost shaders = new SessionResult.Cost(120, 300, 200, 900);
		check(ShaderAdvice.costPercent(shaders, 170).isPresent(), "the made-up run qualifies for the advice");
		SessionResult session = new SessionResult(BenchmarkRequest.Mode.TUNE, knobs, knobs, 170,
				new PlannerResult(12, false, 12, List.of(new PlannerResult.Measurement(12, stats, false)), "Step limit reached"),
				List.of(new SessionResult.Measured(new Step(Step.Kind.RENDER_DISTANCE, knobs, BenchmarkController.Config.DEFAULT.timing().full()), stats)),
				null, null, shaders, Map.of(), false);
		BenchmarkController.Outcome outcome = new BenchmarkController.Outcome(new BenchmarkRequest(BenchmarkRequest.Mode.TUNE,
				BenchmarkRequest.Scene.CURRENT, null), session, false, null, null, true, false, List.of());
		context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkResultScreen(mc.gui.screen(), outcome)));
		context.waitForScreen(BenchmarkResultScreen.class);
		context.waitTicks(3);
		context.takeScreenshot("bench-shader-advice");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
	}

	// Esc during a benchmark-world run: everything restored, back on the title screen, nothing stored.
	private static void benchmarkWorldCancel(ClientGameTestContext context) {
		setScene(context, BenchmarkRequest.Scene.BENCHMARK_WORLD);
		openMenu(context);
		Settings settings = context.computeOnClient(Settings::of);
		int runs = context.computeOnClient(mc -> BenchmarkStore.history().runs().size());
		pressByKey(context, "rigtune.benchmark.menu.tune");
		// Esc once the server has been told a step's other render distance, so the restore has something to tell it.
		context.waitFor(mc -> BenchmarkController.progress() != null && mc.options.renderDistance().get() != settings.rd(), WORLD_TIMEOUT_TICKS);
		int stepRd = awaitRequestedViewDistance(context, rd -> rd != settings.rd(), "during a step at another render distance");
		RigTune.LOGGER.info("Benchmark game test: server's requested view distance {} during the step (start {})", stepRd, settings.rd());
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> BenchmarkWorld.awaitingExit(), RUN_TIMEOUT_TICKS);
		BenchmarkController.Outcome outcome = context.computeOnClient(mc -> BenchmarkController.lastOutcome());
		check(outcome != null && outcome.cancelled() && outcome.record() == null, "Esc cancels the world run: " + outcome);
		checkRequestedViewDistance(context, settings.rd(), "after the cancelled Tune");
		context.runOnClient(BenchmarkWorld::exitNow);
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> BenchmarkWorld.state() == BenchmarkWorld.State.IDLE, WORLD_TIMEOUT_TICKS);
		check(context.computeOnClient(Settings::of).equals(settings), "settings restored after the cancelled world run");
		check(context.computeOnClient(mc -> BenchmarkStore.history().runs().size()) == runs, "a cancelled run isn't stored");
		context.waitTicks(10);
		context.takeScreenshot("bench-world-cancelled");
	}

	private static BenchmarkRecord runInBenchmarkWorld(ClientGameTestContext context, String runningShot, String resultShot) {
		context.waitFor(mc -> BenchmarkController.progress() != null || BenchmarkWorld.awaitingExit(), WORLD_TIMEOUT_TICKS);
		if (runningShot != null) {
			context.waitTicks(10);
			context.takeScreenshot(runningShot);
		}
		// The harness can't leave a world from a client tick, so BenchmarkWorld waits for the test thread.
		context.waitFor(mc -> BenchmarkWorld.awaitingExit(), RUN_TIMEOUT_TICKS);
		BenchmarkController.Outcome outcome = context.computeOnClient(mc -> BenchmarkController.lastOutcome());
		check(outcome != null && !outcome.cancelled() && outcome.record() != null, "world run finished: " + outcome);
		check(outcome.request().scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD, "scene " + outcome.request());
		check(outcome.record().world() != null && BenchmarkWorld.LEVEL_ID.equals(outcome.record().world().levelId()), "world recorded");
		check(outcome.record().result() != null && outcome.record().result().repeats() == 2, "two repeats: " + outcome.record());
		context.runOnClient(BenchmarkWorld::exitNow);
		context.waitForScreen(BenchmarkResultScreen.class);
		check(context.computeOnClient(mc -> mc.level == null), "back from the benchmark world");
		context.waitTicks(3);
		context.takeScreenshot(resultShot);
		return outcome.record();
	}

	// The Tune run of AC6.2 in a harness world, and the benchmark "Keep" journaled through ChangeRecorder.
	private static String currentWorldTune(ClientGameTestContext context) {
		setScene(context, BenchmarkRequest.Scene.CURRENT);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			openMenu(context);
			context.takeScreenshot("bench-menu-world");
			check(!buttonActive(context, "rigtune.benchmark.menu.measure_after"), "no before for the current world yet");
			Settings settings = context.computeOnClient(Settings::of);

			pressByKey(context, "rigtune.benchmark.menu.tune");
			context.waitTicks(20);
			check(context.computeOnClient(mc -> BenchmarkController.running() && !mc.options.enableVsync().get()), "uncapped while measuring");
			context.takeScreenshot("bench-tune-running");
			context.waitFor(mc -> !BenchmarkController.running(), RUN_TIMEOUT_TICKS);
			BenchmarkController.Outcome outcome = context.computeOnClient(mc -> BenchmarkController.lastOutcome());
			check(outcome != null && !outcome.cancelled() && outcome.record() != null, "tune finished: " + outcome);
			check(!outcome.session().deadlineHit(), "the short run stays inside the deadline");
			check(!outcome.result().measurements().isEmpty() && outcome.result().measurements().stream().allMatch(m -> m.stats().frames() > 0),
					"render distance measured: " + outcome.result());
			check(outcome.session().measurements().stream().anyMatch(m -> m.step().kind() == Step.Kind.SIMULATION_DISTANCE),
					"simulation distance measured in singleplayer");
			Settings restored = context.computeOnClient(Settings::of);
			check(restored.equals(settings), "settings restored after the tune: " + restored + " vs " + settings);
			check(outcome.result().measurements().stream().allMatch(m -> m.rd() <= settings.rd() + 8), "never above the start + 8 in your own world");
			checkRequestedViewDistance(context, settings.rd(), "after the Tune in the current world");
			check(!Files.exists(MarkerRestore.file()), "no restore marker without DH or Iris");
			BenchmarkSummary summary = context.computeOnClient(mc -> RigTuneClient.controller().latestBenchmark());
			check(summary != null && "TUNE".equals(summary.mode()) && "CURRENT".equals(summary.scene()), "latestBenchmark: " + summary);

			context.waitForScreen(BenchmarkResultScreen.class);
			context.waitTicks(3);
			context.takeScreenshot("bench-tune-result");

			int chosenRd = outcome.session().chosen().renderDistance();
			int chosenSd = outcome.session().chosen().simulationDistance();
			int otherRd = chosenRd > BenchmarkController.MIN_RD ? chosenRd - 1 : chosenRd + 1;
			List<Recorded> recorded = new ArrayList<>();
			ChangeRecorder previous = ChangeRecorder.current();
			ChangeRecorder.install((entryId, kind, changes) -> recorded.add(new Recorded(entryId, kind, List.copyOf(changes))));
			try {
				context.runOnClient(mc -> mc.options.renderDistance().set(otherRd));
				pressFirst(context, "rigtune.benchmark.use", "rigtune.benchmark.use_best", "rigtune.benchmark.use_both");
			} finally {
				ChangeRecorder.install(previous);
			}
			check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == chosenRd, "Use applied the render distance");
			check(context.computeOnClient(mc -> mc.options.simulationDistance().get()) == chosenSd, "Use applied the simulation distance");
			check(recorded.size() == 1 && JournalEntry.BENCHMARK.equals(recorded.getFirst().kind()), "one benchmark journal entry: " + recorded);
			JournalChange rd = recorded.getFirst().changes().stream().filter(c -> "vanilla.renderDistance".equals(c.key())).findFirst().orElse(null);
			check(rd != null && Integer.toString(otherRd).equals(rd.before()) && Integer.toString(chosenRd).equals(rd.after())
					&& JournalChange.APPLIED.equals(rd.status()), "render distance change journaled: " + recorded);
			context.runOnClient(mc -> {
				mc.options.renderDistance().set(settings.rd());
				mc.options.simulationDistance().set(settings.sd());
				mc.gui.setScreen(null);
			});
			context.waitTicks(5);
			return outcome.record().id();
		}
	}

	private static void checkHistoryFile(String[] pair, String tuneId, String chunkTuneId) {
		Path file = BenchmarkStore.file();
		try {
			JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			check(json.get("schemaVersion").getAsInt() == 1, "schemaVersion 1");
			JsonArray runs = json.getAsJsonArray("runs");
			Set<String> ids = new HashSet<>();
			runs.forEach(r -> ids.add(r.getAsJsonObject().get("id").getAsString()));
			check(ids.contains(pair[0]) && ids.contains(pair[1]) && ids.contains(tuneId) && ids.contains(chunkTuneId),
					"benchmarks.json has the four runs: " + ids);
			// docs/v0.3/SPEC.md AC8.8: every new run records its context.
			runs.forEach(r -> {
				JsonObject run = r.getAsJsonObject();
				JsonObject c = run.getAsJsonObject("context");
				check(c != null && c.get("protocol").getAsInt() == 1 && c.get("width").getAsInt() > 0 && c.get("height").getAsInt() > 0
						&& c.has("dhRendering") && c.has("shaders") && c.has("fullscreen"), "context recorded: " + run);
				// docs/v0.4/SPEC.md 7: and the loaded mods' hash (the journal cursor only once history.json has an entry).
				check(c.has("modSetHash") && c.get("modSetHash").getAsString().matches("[0-9a-f]{64}"), "modSetHash recorded: " + run);
			});
			RigTune.LOGGER.info("Benchmark game test: benchmarks.json has {} runs", runs.size());
		} catch (IOException | RuntimeException e) {
			throw new AssertionError("benchmarks.json unreadable: " + file, e);
		}
	}

	private static void setScene(ClientGameTestContext context, BenchmarkRequest.Scene scene) {
		context.runOnClient(mc -> ClientSettings.shared(FabricLoader.getInstance().getConfigDir()).benchmarkScene = scene.name());
	}

	private static void openMenu(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.gui.setScreen(new BenchmarkMenuScreen(mc.gui.screen(), RigTuneClient.controller())));
		context.waitForScreen(BenchmarkMenuScreen.class);
		context.waitTicks(3);
	}

	private static Button button(Minecraft mc, String key) {
		return Screens.getWidgets(mc.gui.screen()).stream()
				.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
				.map(Button.class::cast)
				.findFirst()
				.orElse(null);
	}

	private static boolean buttonActive(ClientGameTestContext context, String key) {
		return context.computeOnClient(mc -> {
			Button button = button(mc, key);
			if (button == null) {
				throw new AssertionError("No button " + key);
			}
			return button.active;
		});
	}

	private static void pressByKey(ClientGameTestContext context, String key) {
		pressFirst(context, key);
	}

	private static void pressFirst(ClientGameTestContext context, String... keys) {
		context.runOnClient(mc -> {
			for (String key : keys) {
				Button button = button(mc, key);
				if (button != null) {
					check(button.active, key + " is active");
					button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
					return;
				}
			}
			throw new AssertionError("No button among " + List.of(keys));
		});
	}

	private static FileTime modified(Path file) {
		try {
			return Files.getLastModifiedTime(file);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
