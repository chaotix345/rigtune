package io.github.chaotix345.rigtune.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.tryit.TryItService;
import io.github.chaotix345.rigtune.client.ui.BenchmarkMenuScreen;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.client.ui.PreviewScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.TryItScreen;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import io.github.chaotix345.rigtune.core.tryit.TryIt;
import io.github.chaotix345.rigtune.core.tryit.TryItStore;
import io.github.chaotix345.rigtune.core.tryit.TryItText;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

// docs/v0.5/SPEC.md 6 (C09; AC6.1-AC6.4, AC6.6-AC6.9, AC6.12-AC6.15): Measured Try It in a running game, network off
// (X1). Numbers and live verdict kinds are never asserted (the harness's tick sync makes 1% lows meaningless): the verdict
// screenshots come from seeded views. Blocks: 1 NOW here (first the cold-start rule's refusal right after joining, then
// the seam at 0 for the rest; no result screen, the pair, the entry, Revert through Undo this), 6 Esc before and during the after, 2 NOW in the benchmark world (the apply at the title between two opens of the
// same world; Keep writes only tryit.json), 3 RESTART with Sodium (staged, the file untouched, the notice, the menu's
// filter, Cancel try), 4 RESTART after a seeded restart (READY, the toast due, Measure now, Revert stages the reverse op),
// 7 the Preview button's refusals, 5 the verdict screens at X12's sizes, and the idle tick's cost (AC6.12).
public class TryItGameTest implements FabricClientGameTest {
	private static final int WORLD_TIMEOUT_TICKS = 20 * 150;
	private static final int RUN_TIMEOUT_TICKS = 20 * 300;
	private static final BenchmarkController.Config SHORT = new BenchmarkController.Config(3, 1.0, 0.5, 10.0);
	private static final String RD = "vanilla.renderDistance";
	private static final String PARTICLES = "vanilla.particles";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final String QUADS = "sodium.performance.quad_splitting_mode";
	private static final int TICK_BLOCKS = 48;
	private static final int TICK_BLOCK_CALLS = 20_000;

	private static volatile boolean watching;
	private static volatile boolean resultScreenSeen;
	private static boolean listening;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		RigTuneController real = RigTuneClient.controller();
		Path configDir = configDir();
		Path gameDir = FabricLoader.getInstance().getGameDir();
		Map<Path, byte[]> saved = save(List.of(BenchmarkStore.file(), Journal.file(configDir), TryItStore.file(configDir),
				PendingActions.defaultPath(configDir), configDir.resolve("sodium-options.json")));
		String savedScene = context.computeOnClient(mc -> ClientSettings.shared(configDir).benchmarkScene);
		context.runOnClient(mc -> BenchmarkController.setDefaultConfig(SHORT));
		boolean network = GameTestNet.set(context, real, false);
		watchResultScreens();
		try {
			nowHereAndInterruptions(context, real, gameDir);
			nowInTheBenchmarkWorld(context, real, gameDir, configDir);
			if (FabricLoader.getInstance().isModLoaded("sodium") && Files.isRegularFile(configDir.resolve("sodium-options.json"))) {
				restartTry(context, real, configDir);
				seededRestart(context, (RealController) real, configDir);
			} else {
				RigTune.LOGGER.warn("TryItGameTest: Sodium isn't loaded here; the restart blocks (3, 4) are left out");
			}
			refusals(context, real);
			verdictScreens(context, real);
			idleTick(context);
		} finally {
			TryItService.settleSeconds(TryItService.SETTLE_SECONDS);
			watching = false;
			context.runOnClient(mc -> {
				BenchmarkController.setDefaultConfig(BenchmarkController.Config.DEFAULT);
				ClientSettings.shared(configDir).benchmarkScene = savedScene;
			});
			GameTestNet.set(context, real, network);
			restore(saved);
			((RealController) real).v05().tryIt().derive();
			context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		}
	}

	// ---- Block 1 (AC6.2, AC6.7) and block 6 (AC6.8) in a world.

	private static void nowHereAndInterruptions(ClientGameTestContext context, RigTuneController real, Path gameDir) {
		try (TestSingleplayerContext world = GameTestWorlds.create(context)) {
			world.getConnection().waitForChunksRender();
			int rd = context.computeOnClient(mc -> mc.options.renderDistance().get());
			String particles = context.computeOnClient(mc -> SettingsBridge.read(mc).get(PARTICLES));
			String otherParticles = "0".equals(particles) ? "1" : "0";
			Map<String, String> vanillaBefore = context.computeOnClient(TryItGameTest::vanilla);
			context.runOnClient(mc -> mc.options.save());
			byte[] optionsBefore = read(gameDir.resolve("options.txt"));

			int tried = rd > 2 ? rd - 1 : rd + 1;
			settling(context, real, set(RD, Integer.toString(rd), Integer.toString(tried)));

			// Block 1: render distance one lower, measured here; the player presses nothing until the verdict.
			resultScreenSeen = false;
			startFromPreview(context, real, set(RD, Integer.toString(rd), Integer.toString(tried)));
			TryItView result = awaitStage(context, RUN_TIMEOUT_TICKS, Stage.RESULT);
			TryIt t = result.tryIt();
			check(!resultScreenSeen, "no BenchmarkResultScreen opened for a Try it run");
			List<BenchmarkRecord> pair = pair(t);
			check(pair.size() == 2 && BenchmarkRecord.BEFORE.equals(pair.get(0).phase()) && BenchmarkRecord.AFTER.equals(pair.get(1).phase())
					&& t.pairId().startsWith(TryIt.PAIR_PREFIX), "one tryit- pair, before then after: " + pair);
			JournalChange change = change(t);
			check(change != null && JournalChange.APPLIED.equals(change.status()), "the change journaled APPLIED under the try's entry: " + change);
			check(result.verdict() != null && rowStartsWith(context, "Better:", "Worse:", "No clear change:", "No verdict:"), "a verdict line is shown");
			check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == tried, "render distance is the tried value");
			screenshots(context, "tryit-now-here-result");

			// Revert: History's Undo this on the entry.
			pressByKey(context, "rigtune.tryit.action.revert");
			confirmUndo(context, t.entryId());
			awaitStage(context, 400, Stage.REVERTED);
			context.takeScreenshot("tryit-now-here-reverted");
			pressByKey(context, "gui.done");
			awaitDecision(context, TryIt.Decision.REVERTED, "closed as reverted: " + TryItStore.shared(configDir()).recent());
			Map<String, String> vanillaAfter = context.computeOnClient(TryItGameTest::vanilla);
			check(vanillaAfter.equals(vanillaBefore), "every allowed vanilla setting is back: " + vanillaAfter + " vs " + vanillaBefore);
			context.runOnClient(mc -> mc.options.save());
			byte[] optionsAfter = read(gameDir.resolve("options.txt"));
			List<String> linesBefore = new String(optionsBefore, StandardCharsets.UTF_8).lines().toList();
			List<String> linesAfter = new String(optionsAfter, StandardCharsets.UTF_8).lines().toList();
			RigTune.LOGGER.info("TryItGameTest: options.txt byte-identical after the NOW revert: {} (AC6.7's fallback is value equality, checked); "
					+ "lines only before: {}; only after: {}", Arrays.equals(optionsBefore, optionsAfter),
					linesBefore.stream().filter(l -> !linesAfter.contains(l)).limit(10).toList(), linesAfter.stream().filter(l -> !linesBefore.contains(l)).limit(10).toList());

			// Block 6: Esc during the before: nothing journaled.
			startFromPreview(context, real, set(PARTICLES, particles, otherParticles));
			context.waitFor(mc -> BenchmarkController.running(), WORLD_TIMEOUT_TICKS);
			context.waitTicks(10);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			TryItView stopped = awaitStage(context, RUN_TIMEOUT_TICKS, Stage.STOPPED_BEFORE);
			check(change(stopped.tryIt()) == null, "nothing journaled when the before stops");
			context.takeScreenshot("tryit-stopped-before");
			pressByKey(context, "gui.done");
			awaitDecision(context, TryIt.Decision.CANCELLED, "closed as cancelled");

			// Block 6: Esc during the after: READY with Measure again, Keep, Revert.
			startFromPreview(context, real, set(PARTICLES, particles, otherParticles));
			context.waitFor(mc -> real.tryIt().stage() == Stage.MEASURING_AFTER && BenchmarkController.running(), RUN_TIMEOUT_TICKS);
			context.waitTicks(10);
			context.getInput().pressKey(InputConstants.KEY_ESCAPE);
			TryItView ready = awaitStage(context, RUN_TIMEOUT_TICKS, Stage.READY);
			check(ready.sameSession(), "the same session");
			List<String> footer = footerKeys(context);
			check(footer.equals(List.of("rigtune.tryit.action.measure_again", "rigtune.tryit.action.keep", "rigtune.tryit.action.revert")),
					"stopped after: " + footer);
			context.takeScreenshot("tryit-stopped-after");
			pressByKey(context, "rigtune.tryit.action.keep");
			context.waitFor(mc -> real.tryIt().tryIt() == null, 200);
			awaitDecision(context, TryIt.Decision.KEPT, "closed as kept");
			context.runOnClient(mc -> {
				SettingsBridge.applyVanilla(mc.options, Map.of(PARTICLES, particles), true);
				mc.gui.setScreen(null);
			});
			context.waitTicks(5);
		}
		context.waitForScreen(TitleScreen.class);
	}

	// ---- Block 2 (AC6.3, AC6.6): from the title screen, in the benchmark world.

	private static void nowInTheBenchmarkWorld(ClientGameTestContext context, RigTuneController real, Path gameDir, Path configDir) {
		int rd = context.computeOnClient(mc -> mc.options.renderDistance().get());
		resultScreenSeen = false;
		startFromPreview(context, real, set(RD, Integer.toString(rd), Integer.toString(rd + 1)));
		leaveWhenDone(context);
		Path marker = context.computeOnClient(BenchmarkWorld::markerPath);
		FileTime created = modified(marker);
		context.waitFor(mc -> real.tryIt().stage() == Stage.MEASURING_AFTER, 400);
		check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == rd + 1, "applied between the runs");
		leaveWhenDone(context);
		TryItView result = awaitStage(context, RUN_TIMEOUT_TICKS, Stage.RESULT);
		check(!resultScreenSeen, "no BenchmarkResultScreen in the benchmark world either");
		List<BenchmarkRecord> pair = pair(result.tryIt());
		check(pair.size() == 2 && Boolean.FALSE.equals(pair.get(1).context().worldFresh()), "the after reused the world: " + pair);
		check(modified(marker).equals(created), "the benchmark world was reused (its marker untouched)");
		JournalEntry entry = entry(result.tryIt());
		// createdAt is to the second: the apply is no earlier than the before's second and no later than the after's.
		check(entry != null && !Instant.parse(entry.at()).isBefore(Instant.parse(pair.get(0).createdAt()))
				&& Instant.parse(entry.at()).isBefore(Instant.parse(pair.get(1).createdAt()).plusSeconds(1)), "the apply is between the runs: " + entry + " / " + pair);
		// A first run that created the world is left out of the trend: no verdict then (WS-B's M4 rule).
		boolean excluded = BenchmarkTrend.excluded(pair.get(0)) || BenchmarkTrend.excluded(pair.get(1));
		check(!excluded || result.verdict().causes().stream().anyMatch(c -> c instanceof TryItVerdict.Cause.Excluded),
				"a run left out of the trend is named as a cause: " + result.verdict());
		context.takeScreenshot("tryit-now-world-result");

		Map<String, String> before = snapshot(gameDir, configDir);
		pressByKey(context, "rigtune.tryit.action.keep");
		context.waitFor(mc -> real.tryIt().tryIt() == null, 200);
		context.waitTicks(10);
		Map<String, String> after = snapshot(gameDir, configDir);
		List<String> changed = new ArrayList<>();
		after.forEach((k, v) -> {
			if (!v.equals(before.get(k))) {
				changed.add(k);
			}
		});
		before.keySet().stream().filter(k -> !after.containsKey(k)).forEach(changed::add);
		check(changed.equals(List.of("config/rigtune/tryit.json")), "Keep changed only tryit.json: " + changed);
		awaitDecision(context, TryIt.Decision.KEPT, "closed as kept");
		context.runOnClient(mc -> {
			mc.options.renderDistance().set(rd);
			mc.options.save();
			mc.gui.setScreen(new TitleScreen());
		});
		context.waitForScreen(TitleScreen.class);
	}

	// ---- Block 3 (AC6.4, AC6.7, AC6.9) and block 7's open-try refusal: a Sodium setting, staged for the restart.

	private static void restartTry(ClientGameTestContext context, RigTuneController real, Path configDir) {
		check(!context.computeOnClient(mc -> real.hasPendingChanges()), "nothing staged before the restart try");
		Path sodium = configDir.resolve("sodium-options.json");
		byte[] sodiumBefore = read(sodium);
		String from = context.computeOnClient(mc -> SettingsBridge.read(mc).get(DEFER));
		String to = "ALWAYS".equals(from) ? "ONE_FRAME" : "ALWAYS";
		startFromPreview(context, real, set(DEFER, from, to));
		leaveWhenDone(context);
		TryItView waiting = awaitStage(context, 600, Stage.AWAITING_RESTART);
		TryIt t = waiting.tryIt();
		List<PendingActions.Op> ops = pendingOps(configDir);
		check(ops.size() == 1 && ops.getFirst().type() == PendingActions.Type.PATCH_JSON, "one PATCH_JSON op staged: " + ops);
		JournalChange change = change(t);
		check(change != null && JournalChange.STAGED.equals(change.status()), "journaled STAGED: " + change);
		check(Arrays.equals(sodiumBefore, read(sodium)), "sodium-options.json untouched until the exit");
		check(context.computeOnClient(mc -> real.notices().stream().anyMatch(n -> n.priority() == NoticePriority.TRY_IT)), "the TRY_IT notice is up");
		context.takeScreenshot("tryit-restart-waiting");

		// The Benchmark menu's Measure after never continues a Try it pair (AC6.9).
		context.runOnClient(mc -> {
			ClientSettings.shared(configDir()).benchmarkScene = BenchmarkRequest.Scene.BENCHMARK_WORLD.name();
			mc.gui.setScreen(new BenchmarkMenuScreen(new TitleScreen(), real));
		});
		context.waitForScreen(BenchmarkMenuScreen.class);
		Button measureAfter = context.computeOnClient(mc -> button(mc.gui.screen(), "rigtune.benchmark.menu.measure_after"));
		check(measureAfter != null && !measureAfter.active, "the menu's Measure after is inactive: only a Try it pair is open");
		context.takeScreenshot("tryit-restart-menu");

		// Block 7: another try can't start while this one is open.
		int rd = context.computeOnClient(mc -> mc.options.renderDistance().get());
		String refused = refusal(context, real, set(RD, Integer.toString(rd), Integer.toString(rd + 1)));
		check(refused != null && refused.startsWith("Finish or cancel your other Try it first"), "a try is open: " + refused);

		// Cancel try: Undo this discards the staged change.
		context.runOnClient(mc -> mc.gui.setScreen(new TryItScreen(new TitleScreen(), real, null)));
		awaitStage(context, 100, Stage.AWAITING_RESTART);
		pressByKey(context, "rigtune.tryit.action.cancel");
		confirmUndo(context, t.entryId());
		awaitStage(context, 400, Stage.CANCELLED);
		check(!Files.exists(PendingActions.defaultPath(configDir)), "pending.json is gone again");
		check(JournalChange.DISCARDED.equals(change(t).status()), "the change is DISCARDED");
		check(Arrays.equals(sodiumBefore, read(sodium)), "sodium-options.json never changed");
		pressByKey(context, "gui.done");
		awaitDecision(context, TryIt.Decision.CANCELLED, "closed as cancelled");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
	}

	// ---- Block 4 (AC6.4, AC6.7, AC6.15): after a restart, seeded as the helper would have left it.

	private static void seededRestart(ClientGameTestContext context, RealController real, Path configDir) {
		Path sodium = configDir.resolve("sodium-options.json");
		String from = context.computeOnClient(mc -> SettingsBridge.read(mc).get(DEFER));
		String to = "ALWAYS".equals(from) ? "ONE_FRAME" : "ALWAYS";
		Map<String, String> snapshot = context.computeOnClient(mc -> SettingsBridge.read(mc).values());
		TryIt t = TryIt.of(ChangeRecorder.newEntryId(), "setting:" + DEFER, DEFER, from, to, TryIt.Kind.RESTART, BenchmarkRequest.Scene.BENCHMARK_WORLD,
				Instant.now().minusSeconds(600).toString(), "the session before the restart", real.modVersion(), "?", snapshot, null);
		check(TryItStore.shared(configDir).open(t), "seeded tryit.json");
		try {
			BenchmarkHistory.load(BenchmarkStore.file()).with(seededBefore(t)).save(BenchmarkStore.file());
			JournalChange applied = JournalChange.setting(DEFER, from, to, JournalChange.APPLIED, "op-" + t.id());
			check(ClientJournal.get().update(entries -> HistoryUpdates.append(entries, new JournalEntry(t.entryId(), Instant.now().minusSeconds(500).toString(),
					JournalEntry.APPLY, real.modVersion(), "?", null, List.of(applied)))), "seeded history.json");
			JsonObject options = JsonParser.parseString(Files.readString(sodium, StandardCharsets.UTF_8)).getAsJsonObject();
			options.getAsJsonObject("performance").addProperty("chunk_build_defer_mode", to);
			Files.writeString(sodium, options.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		// The derive runs on the service's ordered chain (review M4).
		real.v05().tryIt().derive();
		context.waitFor(mc -> real.tryIt().tryIt() != null, 200);
		TryItView ready = real.tryIt();
		check(ready.stage() == Stage.READY && !ready.sameSession() && TryItText.toastBody(ready.stage()) != null,
				"READY after the restart, the toast due: " + ready.stage());
		check(ready.actions().equals(List.of(TryItView.Action.MEASURE_NOW, TryItView.Action.CANCEL_TRY, TryItView.Action.LATER)), "READY's footer");
		check(context.computeOnClient(mc -> real.notices().stream().anyMatch(n -> n.priority() == NoticePriority.TRY_IT
				&& n.actions().stream().anyMatch(a -> "measure".equals(a.id())))), "the notice offers Measure now");

		resultScreenSeen = false;
		context.runOnClient(mc -> mc.gui.setScreen(new TryItScreen(new TitleScreen(), real, null)));
		awaitStage(context, 100, Stage.READY);
		context.takeScreenshot("tryit-restart-ready");
		pressByKey(context, "rigtune.tryit.action.measure_now");
		leaveWhenDone(context);
		TryItView result = awaitStage(context, RUN_TIMEOUT_TICKS, Stage.RESULT);
		check(!resultScreenSeen, "no result screen for the seeded try's after run");
		List<BenchmarkRecord> pair = pair(t);
		check(pair.size() == 2 && BenchmarkRecord.AFTER.equals(pair.get(1).phase()), "the after pairs with the seeded before: " + pair);
		TryIt stored = TryItStore.shared(configDir).current();
		check(stored != null && stored.settingsAfter() != null && pair.get(1).id().equals(stored.afterRunId()),
				"the after snapshot is stored and the after run acknowledged: " + stored);
		check(real.trendService().acknowledged(pair.get(1).id()), "the after run's regression notice is acknowledged (AC6.15)");
		check(result.verdict() != null && result.verdict().caveats().contains(TryItVerdict.Caveat.SESSIONS), "different sessions are named");
		context.takeScreenshot("tryit-restart-result");

		pressByKey(context, "rigtune.tryit.action.revert");
		confirmUndo(context, t.entryId());
		awaitStage(context, 400, Stage.REVERT_PENDING);
		List<PendingActions.Op> ops = pendingOps(configDir);
		check(ops.size() == 1 && ops.getFirst().type() == PendingActions.Type.PATCH_JSON && ops.getFirst().patches().containsValue(from),
				"the reverse PATCH_JSON is staged: " + ops);
		context.takeScreenshot("tryit-restart-revert-pending");
		pressByKey(context, "gui.done");
		awaitDecision(context, TryIt.Decision.REVERTED, "closed as reverted");
		context.runOnClient(mc -> {
			real.discardPending();
			mc.gui.setScreen(new TitleScreen());
		});
		context.waitForScreen(TitleScreen.class);
	}

	private static BenchmarkRecord seededBefore(TryIt t) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(5, 5, null, null, null));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(5, 5, null, null, null));
		String at = Instant.now().minusSeconds(550).toString();
		return new BenchmarkRecord(at + "-seed", at, "0.5.0", "?", "MEASURE", "BENCHMARK_WORLD", BenchmarkRecord.BEFORE, t.pairId(), 60, true, knobs,
				new BenchmarkRecord.Result(120, 80, 12.5, 2, 0.02), Map.of(), Map.of(), new BenchmarkRecord.World(BenchmarkWorld.LEVEL_ID, BenchmarkWorld.SEED),
				false, new BenchmarkRecord.Context(false, false, null, 854, 480, false, BenchmarkRecord.Context.PROTOCOL).withWorldFresh(false));
	}

	// ---- Block 7 (AC6.1): the Preview button's refusals, each with its tooltip.

	private static void refusals(ClientGameTestContext context, RigTuneController real) {
		check("Only settings can be tried; mod changes can't.".equals(refusal(context, real, new Recommendation("tryit-test:disable", Category.REMOVE_MOD,
				Impact.LOW, "Disable a mod", "", new Action.DisableMod("tryit-probe", Path.of("mods", "tryit-probe.jar")), true))), "a mod change");
		String maxFps = refusal(context, real, set("vanilla.maxFps", "260", "120"));
		check(maxFps != null && maxFps.startsWith("Benchmarks always run with the frame rate uncapped"), "max FPS: " + maxFps);
		if (FabricLoader.getInstance().isModLoaded("sodium")) {
			String from = context.computeOnClient(mc -> SettingsBridge.read(mc).get(DEFER));
			String to = "ALWAYS".equals(from) ? "ONE_FRAME" : "ALWAYS";
			String quads = context.computeOnClient(mc -> SettingsBridge.read(mc).get(QUADS));
			context.runOnClient(mc -> real.apply(List.of(set(QUADS, quads, "OFF".equals(quads) ? "SAFE" : "OFF"))));
			try {
				check(context.computeOnClient(mc -> real.hasPendingChanges()), "something is staged");
				String pending = refusal(context, real, set(DEFER, from, to));
				check(pending != null && pending.startsWith("Other changes are waiting for a restart"), "a Sodium key while changes wait: " + pending);
			} finally {
				context.runOnClient(mc -> real.discardPending());
			}
		}
		context.runOnClient(mc -> mc.gui.setScreen(new PreviewScreen(new TitleScreen(), real, List.of(set(RD, "5", "6"), set(PARTICLES, "0", "1")))));
		context.waitForScreen(PreviewScreen.class);
		context.waitTicks(2);
		String two = context.computeOnClient(mc -> {
			PreviewScreen preview = (PreviewScreen) mc.gui.screen();
			return preview.tryItButton() != null && !preview.tryItButton().active ? preview.tryItTooltip().getString() : null;
		});
		check("Tick exactly one suggestion to try it.".equals(two), "two ticked items: " + two);
		context.takeScreenshot("tryit-preview-two-ticked");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
	}

	// The Try it button's tooltip on Preview for one ticked item, or null when it's active.
	private static @Nullable String refusal(ClientGameTestContext context, RigTuneController real, Recommendation rec) {
		context.runOnClient(mc -> mc.gui.setScreen(new PreviewScreen(new TitleScreen(), real, List.of(rec))));
		context.waitForScreen(PreviewScreen.class);
		context.waitTicks(2);
		return context.computeOnClient(mc -> {
			PreviewScreen preview = (PreviewScreen) mc.gui.screen();
			check(preview.tryItButton() != null, "Preview has the Try it button");
			return preview.tryItButton().active ? null : preview.tryItTooltip().getString();
		});
	}

	// ---- Block 5 (AC6.13, AC6.5's line): seeded verdicts at X12's sizes, no overlap, every line wrapped (none cut).

	private static void verdictScreens(ClientGameTestContext context, RigTuneController real) {
		V05TestContext v05 = V05TestContext.of(context);
		TryIt t = TryIt.of("e-shot", "setting:" + RD, RD, "12", "10", TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT, "2026-09-20T10:00:00Z", "shot",
				"0.5.0", "26.2", Map.of(), null);
		BenchmarkRecord before = shotRun("b", 84.2, 142.0, BenchmarkRecord.BEFORE, t.pairId());
		BenchmarkRecord after = shotRun("a", 94.8, 151.3, BenchmarkRecord.AFTER, t.pairId());
		List<TryItVerdict.Caveat> caveats = List.of(TryItVerdict.Caveat.NOISY, TryItVerdict.Caveat.SCENE);
		Map<String, TryItVerdict.Verdict> verdicts = new LinkedHashMap<>();
		verdicts.put("better", new TryItVerdict.Verdict(TryItVerdict.Kind.BETTER, 12.6, 6.6, 6.2, List.of(), caveats));
		verdicts.put("worse", new TryItVerdict.Verdict(TryItVerdict.Kind.WORSE, -9.1, -4.0, 5.0, List.of(), caveats));
		verdicts.put("no-clear-change", new TryItVerdict.Verdict(TryItVerdict.Kind.NO_CLEAR_CHANGE, 1.9, 0.4, 5.0, List.of(), caveats));
		verdicts.put("not-comparable", new TryItVerdict.Verdict(TryItVerdict.Kind.NOT_COMPARABLE, 12.6, 6.6, 5.0,
				List.of(new TryItVerdict.Cause.Condition(BenchmarkTrend.Difference.RESOLUTION), new TryItVerdict.Cause.Entry("e-later", JournalEntry.APPLY)),
				caveats));
		Screen found = context.computeOnClient(mc -> mc.gui.screen());
		try {
			for (Map.Entry<String, TryItVerdict.Verdict> v : verdicts.entrySet()) {
				Canned canned = new Canned(real, new TryItView(Stage.RESULT, t, before, after, v.getValue(), JournalChange.APPLIED, null, true));
				for (int[] size : V05TestContext.SIZES) {
					v05.resize(size[0], size[1], size[2]);
					context.runOnClient(mc -> mc.gui.setScreen(new TryItScreen(new TitleScreen(), canned, null)));
					context.waitForScreen(TryItScreen.class);
					context.waitTicks(3);
					layout(context, "tryit " + v.getKey() + " " + size[0] + "x" + size[1]);
					context.takeScreenshot("tryit-verdict-" + v.getKey() + "-" + size[0] + "x" + size[1] + "-scale" + size[2]);
				}
			}
			// X12: at 1280x720 scale 3 the lines fit or scroll inside the list.
			v05.resize(1280, 720, 3);
			Canned canned = new Canned(real, new TryItView(Stage.RESULT, t, before, after, verdicts.get("not-comparable"), JournalChange.APPLIED, null, true));
			context.runOnClient(mc -> mc.gui.setScreen(new TryItScreen(new TitleScreen(), canned, null)));
			context.waitForScreen(TryItScreen.class);
			context.waitTicks(3);
			layout(context, "tryit 1280x720 scale 3");
			context.takeScreenshot("tryit-verdict-not-comparable-1280x720-scale3");
		} finally {
			v05.resize(854, 480, 2);
			context.runOnClient(mc -> mc.gui.setScreen(found));
		}
	}

	private static BenchmarkRecord shotRun(String id, double low, double avg, String phase, String pairId) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(10, 10, null, null, null));
		return new BenchmarkRecord(id, "2026-09-20T10:01:00Z", "0.5.0", "26.2", "MEASURE", "CURRENT", phase, pairId, 60, true, knobs,
				new BenchmarkRecord.Result(avg, low, 1000 / low, 2, 0.03), Map.of(), Map.of(), null, false, null);
	}

	// Every widget inside the screen, the footer's buttons apart, the list above them.
	private static void layout(ClientGameTestContext context, String name) {
		context.runOnClient(mc -> {
			Screen screen = mc.gui.screen();
			List<AbstractWidget> widgets = Screens.getWidgets(screen).stream().filter(w -> w instanceof AbstractWidget).map(AbstractWidget.class::cast).toList();
			for (AbstractWidget w : widgets) {
				check(w.getX() >= 0 && w.getY() >= 0 && w.getX() + w.getWidth() <= screen.width && w.getY() + w.getHeight() <= screen.height,
						name + ": " + w.getMessage().getString() + " inside the screen");
			}
			List<Button> footer = ((TryItScreen) screen).footer();
			check(!footer.isEmpty() && footer.size() <= 3, name + ": 1 to 3 buttons");
			for (int i = 1; i < footer.size(); i++) {
				check(footer.get(i).getX() >= footer.get(i - 1).getX() + footer.get(i - 1).getWidth(), name + ": the buttons don't overlap");
			}
			AbstractWidget list = widgets.stream().filter(w -> w instanceof AbstractSelectionList<?>).findFirst().orElseThrow();
			check(list.getY() + list.getHeight() <= footer.getFirst().getY(), name + ": the list ends above the buttons");
		});
	}

	// A controller that answers a canned Try it view and forwards the rest (labels, refusals) to the game's.
	static final class Canned extends ForwardingController {
		private final TryItView view;

		Canned(RigTuneController delegate, TryItView view) {
			super(delegate);
			this.view = view;
		}

		@Override
		public TryItView tryIt() {
			return view;
		}

		@Override
		public void tryItMeasureNow() {
		}

		@Override
		public Component tryItKeep() {
			return Component.translatable("rigtune.status.nothing");
		}

		@Override
		public void tryItCancel() {
		}
	}

	// ---- AC6.12: the idle listener call costs nothing: 0 bytes summed over the timed blocks, less an empty loop's, after a
	// warm-up and a wait for the JIT to go quiet (as FootprintGameTest's tick keys and StutterGameTest's settings check).

	private static void idleTick(ClientGameTestContext context) {
		check(context.computeOnClient(mc -> TryItService.idle()), "no try is between or inside its runs (review L13)");
		long[] measured = context.computeOnClient(TryItGameTest::idleTickCost);
		double nsPerCall = (double) measured[2] / (TICK_BLOCKS * (long) TICK_BLOCK_CALLS);
		RigTune.LOGGER.info("TryItGameTest: the idle Try it tick: {} ns per call, {} bytes over {} x {} calls (an empty loop: {} bytes)",
				String.format(Locale.ROOT, "%.2f", nsPerCall), measured[0], TICK_BLOCKS, TICK_BLOCK_CALLS, measured[1]);
		check(measured[0] - measured[1] <= 0, "the idle tick allocates nothing: " + measured[0] + " bytes (an empty loop " + measured[1] + ")");
	}

	private static long[] idleTickCost(Minecraft mc) {
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		java.lang.management.CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
		for (int round = 0; round < 50; round++) {
			ticks(mc, 2_000);
			control(2_000);
		}
		long waitStart = System.nanoTime();
		long quietSince = waitStart;
		long compiled = jit.getTotalCompilationTime();
		while (System.nanoTime() - quietSince < 200_000_000L && System.nanoTime() - waitStart < 3_000_000_000L) {
			ticks(mc, 2_000);
			control(2_000);
			long now = jit.getTotalCompilationTime();
			if (now != compiled) {
				compiled = now;
				quietSince = System.nanoTime();
			}
		}
		long bytes = 0;
		long controlBytes = 0;
		long nanos = 0;
		for (int block = 0; block < TICK_BLOCKS; block++) {
			long before = threads.getCurrentThreadAllocatedBytes();
			long start = System.nanoTime();
			ticks(mc, TICK_BLOCK_CALLS);
			nanos += System.nanoTime() - start;
			long after = threads.getCurrentThreadAllocatedBytes();
			control(TICK_BLOCK_CALLS);
			long controlAfter = threads.getCurrentThreadAllocatedBytes();
			bytes += after - before;
			controlBytes += controlAfter - after;
		}
		return new long[]{bytes, controlBytes, nanos};
	}

	private static void ticks(Minecraft mc, int calls) {
		for (int i = 0; i < calls; i++) {
			TryItService.tick(mc);
		}
	}

	private static void control(int calls) {
		for (int i = 0; i < calls; i++) {
			Thread.onSpinWait();
		}
	}

	// ---- helpers

	private static Recommendation set(String key, String from, String to) {
		check(SettingKeys.changeable(key), key);
		return new Recommendation("tryit-test:" + key, Category.SETTING, Impact.MEDIUM, key, "Try it game test", new Action.SetSetting(key, from, to), true);
	}

	// Preview -> Try it (measured) -> the intro (its default scene: here in a world, else the benchmark world) -> Start.
	// The cold-start rule, right after joining: Start in the player's own world is inactive under a status line that counts
	// down, and a Start anyway opens no try. The settle time is counted from now (a slow runner may have spent a while
	// joining); then the seam goes to 0 for the rest of the test and Start comes back within a second.
	private static void settling(ClientGameTestContext context, RigTuneController real, Recommendation rec) {
		int ticks = context.computeOnClient(mc -> mc.player.tickCount);
		TryItService.settleSeconds(ticks / 20 + TryItService.SETTLE_SECONDS);
		context.runOnClient(mc -> mc.gui.setScreen(new PreviewScreen(mc.gui.screen(), real, List.of(rec))));
		context.waitForScreen(PreviewScreen.class);
		context.waitTicks(2);
		pressByKey(context, "rigtune.tryit.button");
		context.waitForScreen(TryItScreen.class);
		context.waitTicks(2);
		check(rowStartsWith(context, "Try It measures better once the world has settled. Play for about a minute first ("),
				"the status line: " + context.computeOnClient(mc -> ((TryItScreen) mc.gui.screen()).rowText()));
		check(!context.computeOnClient(mc -> ((TryItScreen) mc.gui.screen()).footer().getFirst().active), "Start is inactive");
		Component answer = context.computeOnClient(mc -> real.startTryIt(rec, BenchmarkRequest.Scene.CURRENT));
		check(answer.getString().startsWith("Try It measures better"), "a Start anyway is refused: " + answer.getString());
		context.waitTicks(5);
		check(real.tryIt().tryIt() == null && TryItStore.shared(configDir()).current() == null, "no try opened");
		context.takeScreenshot("tryit-intro-settling");
		TryItService.settleSeconds(0);
		context.waitFor(mc -> mc.gui.screen() instanceof TryItScreen screen && screen.footer().getFirst().active, 60);
		check(!rowStartsWith(context, "Try It measures better"), "the status line goes once settled");
		context.runOnClient(mc -> mc.gui.setScreen(null));
	}

	private static void startFromPreview(ClientGameTestContext context, RigTuneController real, Recommendation rec) {
		context.runOnClient(mc -> mc.gui.setScreen(new PreviewScreen(mc.gui.screen(), real, List.of(rec))));
		context.waitForScreen(PreviewScreen.class);
		context.waitTicks(2);
		pressByKey(context, "rigtune.tryit.button");
		context.waitForScreen(TryItScreen.class);
		context.waitTicks(2);
		check(context.computeOnClient(mc -> ((TryItScreen) mc.gui.screen()).rowText().stream().anyMatch(l -> l.startsWith("RigTune measures your game now"))),
				"the intro is shown");
		context.takeScreenshot("tryit-intro-" + rec.id().substring(rec.id().lastIndexOf('.') + 1));
		pressByKey(context, "rigtune.tryit.action.start");
	}

	// A benchmark-world run: the harness leaves the world from the test thread.
	private static void leaveWhenDone(ClientGameTestContext context) {
		context.waitFor(mc -> BenchmarkWorld.awaitingExit(), WORLD_TIMEOUT_TICKS + RUN_TIMEOUT_TICKS);
		GameTestWorlds.leave(context, BenchmarkWorld::exitNow);
		context.waitFor(mc -> mc.level == null && BenchmarkWorld.state() == BenchmarkWorld.State.IDLE, WORLD_TIMEOUT_TICKS);
	}

	private static TryItView awaitStage(ClientGameTestContext context, int ticks, Stage... stages) {
		List<Stage> wanted = List.of(stages);
		context.waitFor(mc -> mc.gui.screen() instanceof TryItScreen screen && wanted.contains(screen.shown().stage()) && !screen.deriving(), ticks);
		context.waitTicks(2);
		return context.computeOnClient(mc -> ((TryItScreen) mc.gui.screen()).shown());
	}

	private static void confirmUndo(ClientGameTestContext context, String entryId) {
		context.waitForScreen(UndoScreen.class);
		context.waitFor(mc -> mc.gui.screen() instanceof UndoScreen undo && undo.plan() != null, 200);
		check(entryId.equals(context.computeOnClient(mc -> ((UndoScreen) mc.gui.screen()).entryId())), "Undo this for the try's entry");
		pressByKey(context, "rigtune.undo.confirm");
		context.waitTicks(5);
		pressByKey(context, "gui.done");
		context.waitForScreen(TryItScreen.class);
	}

	private static boolean rowStartsWith(ClientGameTestContext context, String... prefixes) {
		return context.computeOnClient(mc -> ((TryItScreen) mc.gui.screen()).rowText().stream()
				.anyMatch(line -> Arrays.stream(prefixes).anyMatch(line::startsWith)));
	}

	private static List<String> footerKeys(ClientGameTestContext context) {
		return context.computeOnClient(mc -> ((TryItScreen) mc.gui.screen()).footer().stream()
				.map(b -> b.getMessage().getContents() instanceof TranslatableContents tc ? tc.getKey() : b.getMessage().getString()).toList());
	}

	// At X12's sizes; then the window and GUI scale as they were (options.txt keeps its guiScale line).
	private static void screenshots(ClientGameTestContext context, String name) {
		V05TestContext v05 = V05TestContext.of(context);
		int scale = context.computeOnClient(mc -> mc.options.guiScale().get());
		for (int[] size : V05TestContext.SIZES) {
			v05.resize(size[0], size[1], size[2]);
			context.waitTicks(3);
			layout(context, name + " " + size[0] + "x" + size[1]);
			context.takeScreenshot(name + "-" + size[0] + "x" + size[1] + "-scale" + size[2]);
		}
		v05.resize(854, 480, scale);
		context.waitTicks(2);
	}

	private static List<BenchmarkRecord> pair(TryIt t) {
		return BenchmarkStore.history().runs().stream().filter(r -> t.pairId().equals(r.pairId())).toList();
	}

	private static @Nullable JournalEntry entry(@Nullable TryIt t) {
		return t == null ? null : ClientJournal.get().entries().stream().filter(e -> t.entryId().equals(e.id())).findFirst().orElse(null);
	}

	private static @Nullable JournalChange change(@Nullable TryIt t) {
		JournalEntry e = entry(t);
		return e == null ? null : e.changes().stream().filter(c -> t.key().equals(c.key())).findFirst().orElse(null);
	}

	// Review L12: the close is written on the chain; wait for it.
	private static void awaitDecision(ClientGameTestContext context, TryIt.Decision wanted, String what) {
		context.waitFor(mc -> decision() == wanted, 200);
		check(decision() == wanted, what);
	}

	private static TryIt.@Nullable Decision decision() {
		List<TryIt.Closed> recent = TryItStore.shared(configDir()).recent();
		return recent.isEmpty() ? null : recent.getFirst().decision();
	}

	private static List<PendingActions.Op> pendingOps(Path configDir) {
		Path file = PendingActions.defaultPath(configDir);
		try {
			return Files.isRegularFile(file) ? PendingActions.load(file).ops() : List.of();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	// The vanilla settings RigTune may change, as the game has them now.
	private static Map<String, String> vanilla(Minecraft mc) {
		Map<String, String> out = new TreeMap<>();
		SettingsBridge.read(mc).values().forEach((k, v) -> {
			if (SettingKeys.VANILLA_ALLOWED.contains(k)) {
				out.put(k, v);
			}
		});
		return out;
	}

	// options.txt and config/ by SHA-256.
	private static Map<String, String> snapshot(Path gameDir, Path configDir) {
		Map<String, String> out = new TreeMap<>();
		List<Path> files = new ArrayList<>();
		files.add(gameDir.resolve("options.txt"));
		try (Stream<Path> walk = Files.walk(configDir)) {
			walk.filter(Files::isRegularFile).forEach(files::add);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		for (Path file : files) {
			try {
				out.put(gameDir.relativize(file).toString().replace('\\', '/'),
						HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
			} catch (IOException | NoSuchAlgorithmException e) {
				throw new AssertionError(e);
			}
		}
		return out;
	}

	private static void watchResultScreens() {
		watching = true;
		if (!listening) {
			listening = true;
			ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
				if (watching && screen instanceof BenchmarkResultScreen) {
					resultScreenSeen = true;
				}
			});
		}
	}

	private static @Nullable Button button(Screen screen, String key) {
		return Screens.getWidgets(screen).stream()
				.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
				.map(Button.class::cast).findFirst().orElse(null);
	}

	private static void pressByKey(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = button(mc.gui.screen(), key);
			check(button != null, "No button " + key + " on " + mc.gui.screen());
			check(button.active, key + " is active");
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
		});
		context.waitTicks(2);
	}

	private static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	private static Map<Path, byte[]> save(List<Path> files) {
		Map<Path, byte[]> out = new LinkedHashMap<>();
		for (Path file : files) {
			out.put(file, Files.isRegularFile(file) ? read(file) : null);
		}
		return out;
	}

	private static void restore(Map<Path, byte[]> saved) {
		saved.forEach((file, bytes) -> {
			try {
				if (bytes == null) {
					Files.deleteIfExists(file);
				} else {
					Files.write(file, bytes);
				}
			} catch (IOException e) {
				RigTune.LOGGER.warn("TryItGameTest: could not restore {}", file, e);
			}
		});
	}

	private static byte[] read(Path file) {
		try {
			return Files.readAllBytes(file);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
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
