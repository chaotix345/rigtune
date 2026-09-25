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
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
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
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Benchmark v2 (docs/v0.2/SPEC.md AC6.2, AC6.3). Numbers aren't asserted: the harness's tick sync makes 1% lows
// unrepresentative. What is asserted: the runs complete, everything is restored, and the pair and history are stored.
public class BenchmarkGameTest implements FabricClientGameTest {
	private static final int WORLD_TIMEOUT_TICKS = 20 * 150;
	private static final int RUN_TIMEOUT_TICKS = 20 * 240;
	private static final BenchmarkController.Config SHORT = new BenchmarkController.Config(3, 1.0, 0.5, 10.0);

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
		try {
			String[] pair = benchmarkWorldPair(context);
			benchmarkWorldCancel(context);
			String tuneId = currentWorldTune(context);
			checkHistoryFile(pair, tuneId);
		} finally {
			context.runOnClient(mc -> BenchmarkController.setDefaultConfig(BenchmarkController.Config.DEFAULT));
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

		openMenu(context);
		check(buttonActive(context, "rigtune.benchmark.menu.measure_after"), "Measure after is offered");
		pressByKey(context, "rigtune.benchmark.menu.measure_after");
		BenchmarkRecord after = runInBenchmarkWorld(context, null, "bench-world-after");
		check(BenchmarkRecord.AFTER.equals(after.phase()) && before.pairId().equals(after.pairId()), "after pairs with before: " + after);
		check(context.computeOnClient(mc -> BenchmarkController.lastOutcome().gain()) != null, "gain computed for the pair");
		check(modified(marker).equals(created), "benchmark world reused, not recreated");
		pressByKey(context, "gui.done");
		context.waitForScreen(TitleScreen.class);
		return new String[]{before.id(), after.id()};
	}

	// Esc during a benchmark-world run: everything restored, back on the title screen, nothing stored.
	private static void benchmarkWorldCancel(ClientGameTestContext context) {
		setScene(context, BenchmarkRequest.Scene.BENCHMARK_WORLD);
		openMenu(context);
		Settings settings = context.computeOnClient(Settings::of);
		int runs = context.computeOnClient(mc -> BenchmarkStore.history().runs().size());
		pressByKey(context, "rigtune.benchmark.menu.tune");
		context.waitFor(mc -> BenchmarkController.progress() != null, WORLD_TIMEOUT_TICKS);
		context.waitTicks(10);
		context.getInput().pressKey(InputConstants.KEY_ESCAPE);
		context.waitFor(mc -> BenchmarkWorld.awaitingExit(), RUN_TIMEOUT_TICKS);
		BenchmarkController.Outcome outcome = context.computeOnClient(mc -> BenchmarkController.lastOutcome());
		check(outcome != null && outcome.cancelled() && outcome.record() == null, "Esc cancels the world run: " + outcome);
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

	private static void checkHistoryFile(String[] pair, String tuneId) {
		Path file = BenchmarkStore.file();
		try {
			JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			check(json.get("schemaVersion").getAsInt() == 1, "schemaVersion 1");
			JsonArray runs = json.getAsJsonArray("runs");
			Set<String> ids = new HashSet<>();
			runs.forEach(r -> ids.add(r.getAsJsonObject().get("id").getAsString()));
			check(ids.contains(pair[0]) && ids.contains(pair[1]) && ids.contains(tuneId), "benchmarks.json has the three runs: " + ids);
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
					button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
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
