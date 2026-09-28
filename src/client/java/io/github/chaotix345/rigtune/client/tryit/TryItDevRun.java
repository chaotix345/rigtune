package io.github.chaotix345.rigtune.client.tryit;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.UndoScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.tryit.TryIt;
import io.github.chaotix345.rigtune.core.tryit.TryItVerdict;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import io.github.chaotix345.rigtune.core.tryit.TryItView.Stage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import com.mojang.blaze3d.platform.InputConstants;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// Development only, and only when the environment variable RIGTUNE_DEV_TRYIT is set: docs/v0.5/SPEC.md AC6.16's real
// run (the code-deciding one, docs/v0.5/verification/try-it/) in a plain client, without the game-test harness (whose tick
// sync makes 1% lows meaningless), then the game quits. Every step is logged with the prefix "Try it dev:".
//   aa            from the title screen: a warm-up Measure, then 5 Measure pairs in the benchmark world with nothing
//                 changed, each judged as Try it would (the floor, the kind).
//   now:<save>    opens that singleplayer save, tries render distance 2 lower where the player stands (the real chain),
//                 reverts it, then measures a manual pair with the same change at the same spot. now:<save>:warm runs
//                 one plain Measure run there first (as a player who has been in the world a while).
//   restart1..3   a Sodium defer-mode try across real restarts: 1 starts it (before run, staged) and quits; 2 measures
//                 after the restart and reverts (staged again) and quits; 3 logs the file and History.
// It starts from the start hook's derive (TryItService.derive, which loads this class only when the variable is set) and
// registers its own tick listener; nothing else of it runs.
final class TryItDevRun {
	static final String ENV = "RIGTUNE_DEV_TRYIT";
	static final @Nullable String MODE = System.getenv(ENV);
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final int PAIRS = 5;
	private static final int GIVE_UP_TICKS = 20 * 60 * 30;

	private static boolean started;

	private final RealController controller;
	private final List<BenchmarkRequest> queue = new ArrayList<>();
	private final List<String> pairs = new ArrayList<>();
	private int ticks;
	private int step;
	private int wait;
	private boolean running;
	private @Nullable String entryId;
	private int rd;

	private TryItDevRun(RealController controller) {
		this.controller = controller;
	}

	// The start hook's worker: the listener goes on the render thread, once.
	static synchronized void startIfAsked(RealController controller) {
		Minecraft minecraft = controller.minecraft();
		if (MODE == null || started || minecraft == null) {
			return;
		}
		started = true;
		TryItDevRun run = new TryItDevRun(controller);
		minecraft.execute(() -> ClientTickEvents.END_CLIENT_TICK.register(run::tick));
		log("mode " + MODE);
	}

	private void tick(Minecraft minecraft) {
		if (step < 0) {
			return;
		}
		if (++ticks > GIVE_UP_TICKS) {
			finish(minecraft, "FAILED: gave up after 30 minutes");
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			if ("aa".equals(MODE)) {
				aa(minecraft);
			} else if (MODE.startsWith("now:")) {
				String[] save = MODE.substring("now:".length()).split(":");
				now(minecraft, save[0], save.length > 1 && "warm".equals(save[1]));
			} else if (MODE.startsWith("restart")) {
				restart(minecraft, MODE);
			} else {
				finish(minecraft, "FAILED: unknown mode");
			}
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Try it dev: failed", e);
			finish(minecraft, "FAILED: " + e);
		}
	}

	// ---- aa

	private void aa(Minecraft minecraft) {
		switch (step) {
			case 0 -> {
				if (!(minecraft.gui.screen() instanceof TitleScreen) || ticks < 60) {
					return;
				}
				minecraft.options.pauseOnLostFocus = false;
				queue.add(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, Scene.BENCHMARK_WORLD, null));
				for (int i = 0; i < PAIRS; i++) {
					String pair = "aa-" + i + "-" + UUID.randomUUID();
					pairs.add(pair);
					queue.add(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, Scene.BENCHMARK_WORLD, pair));
					queue.add(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, Scene.BENCHMARK_WORLD, pair));
				}
				step = 1;
			}
			case 1 -> {
				if (runQueue(minecraft)) {
					step = 2;
				}
			}
			case 2 -> {
				List<BenchmarkRecord> runs = BenchmarkStore.history().runs();
				TryIt judged = TryIt.of("dev", null, "vanilla.biomeBlendRadius", "2", "2", TryIt.Kind.NOW, Scene.BENCHMARK_WORLD, null,
						TryItService.SESSION, null, null, Map.of(), null);
				int within = 0;
				for (String pair : pairs) {
					BenchmarkRecord before = runs.stream().filter(r -> pair.equals(r.pairId()) && BenchmarkRecord.BEFORE.equals(r.phase())).findFirst()
							.orElse(null);
					BenchmarkRecord after = runs.stream().filter(r -> pair.equals(r.pairId()) && BenchmarkRecord.AFTER.equals(r.phase())).findFirst()
							.orElse(null);
					if (before == null || after == null) {
						log("A/A pair " + pair + ": incomplete");
						continue;
					}
					TryItVerdict.Verdict v = TryItVerdict.of(judged, before, after, runs, List.of());
					within += v.kind() == TryItVerdict.Kind.NO_CLEAR_CHANGE ? 1 : 0;
					log(String.format(Locale.ROOT, "A/A pair %s: 1%% low %.1f -> %.1f FPS (cv %s / %s), avg %.1f -> %.1f: low %s, avg %s, floor %.1f%%: %s %s",
							pair, before.result().onePercentLowFps(), after.result().onePercentLowFps(), before.result().cv(), after.result().cv(),
							before.result().avgFps(), after.result().avgFps(), pct(v.lowPercent()), pct(v.avgPercent()), v.floorPercent(), v.kind(),
							v.causes()));
				}
				finish(minecraft, "A/A: " + within + " of " + pairs.size() + " pairs within the floor (MIN_CV " + TryItVerdict.MIN_CV + ")");
			}
			default -> {
			}
		}
	}

	// Runs the queued Measure runs one after another from the title screen (the benchmark world opens and closes for each);
	// true when all are done. A cancelled run is run again.
	private boolean runQueue(Minecraft minecraft) {
		if (running) {
			if (BenchmarkController.running() || BenchmarkWorld.busy()) {
				return false;
			}
			running = false;
			BenchmarkController.Outcome outcome = BenchmarkController.lastOutcome();
			BenchmarkRequest done = queue.getFirst();
			if (outcome == null || outcome.cancelled() || outcome.record() == null) {
				log("a run ended without a record; running it again");
			} else {
				queue.removeFirst();
				BenchmarkRecord r = outcome.record();
				log("run " + r.id() + " " + r.phase() + " " + done.pairId() + ": " + (r.result() == null ? "no result"
						: String.format(Locale.ROOT, "1%% low %.1f, avg %.1f, cv %s", r.result().onePercentLowFps(), r.result().avgFps(), r.result().cv())));
			}
			minecraft.gui.setScreen(new TitleScreen());
			wait = 60;
			return queue.isEmpty();
		}
		if (queue.isEmpty()) {
			return true;
		}
		if (!(minecraft.gui.screen() instanceof TitleScreen) || BenchmarkController.unavailable(minecraft, Scene.BENCHMARK_WORLD) != null) {
			return false;
		}
		String refused = BenchmarkController.tryStart(minecraft, queue.getFirst(), BenchmarkController.defaultConfig());
		if (refused != null) {
			log("refused " + refused + "; retrying");
			wait = 100;
			return false;
		}
		running = true;
		return false;
	}

	// ---- now:<save>

	private void now(Minecraft minecraft, String save, boolean warm) {
		TryItView view = controller.tryIt();
		switch (step) {
			case 0 -> {
				if (!(minecraft.gui.screen() instanceof TitleScreen) || ticks < 60) {
					return;
				}
				minecraft.options.pauseOnLostFocus = false;
				log("opening " + save);
				minecraft.createWorldOpenFlows().openWorld(save, () -> finish(minecraft, "FAILED: opening the save was cancelled"));
				step = 1;
			}
			case 1 -> {
				if (minecraft.player == null || minecraft.level == null || minecraft.gui.screen() != null) {
					return;
				}
				step = warm ? 10 : 2;
				wait = 20 * 20;
				if (warm) {
					log("a warm-up Measure run here first");
					queue.add(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, Scene.CURRENT, null));
				}
			}
			case 10 -> {
				if (runHere(minecraft)) {
					step = 2;
					wait = 20 * 5;
				}
			}
			case 2 -> {
				// The cold-start rule: Start waits until the player has been in the world a minute.
				if (controller.tryItSettling(Scene.CURRENT) != null) {
					return;
				}
				rd = minecraft.options.renderDistance().get();
				Recommendation rec = rec("vanilla.renderDistance", Integer.toString(rd), Integer.toString(rd - 2));
				Component answer = controller.startTryIt(rec, Scene.CURRENT);
				log("NOW try: render distance " + rd + " -> " + (rd - 2) + " here: " + answer.getString());
				step = 3;
			}
			case 3 -> {
				if (view.stage() == Stage.RESULT || view.stage() == Stage.READY) {
					TryIt t = view.tryIt();
					entryId = t.entryId();
					log("NOW try " + view.stage() + ": " + verdict(view));
					minecraft.gui.setScreen(new UndoScreen(null, controller, entryId));
					step = 4;
					wait = 40;
				}
			}
			case 4 -> {
				if (!(minecraft.gui.screen() instanceof UndoScreen undo) || undo.plan() == null) {
					return;
				}
				press(minecraft, "rigtune.undo.confirm");
				wait = 40;
				step = 5;
			}
			case 5 -> {
				if (view.stage() != Stage.REVERTED) {
					return;
				}
				controller.tryItCancel();
				minecraft.gui.setScreen(null);
				log("reverted: render distance " + minecraft.options.renderDistance().get() + "; the manual pair next");
				String pair = "manual-" + UUID.randomUUID();
				pairs.add(pair);
				queue.add(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, Scene.CURRENT, pair));
				wait = 20 * 5;
				step = 6;
			}
			case 6 -> {
				if (runHere(minecraft)) {
					minecraft.options.renderDistance().set(rd - 2);
					minecraft.options.save();
					queue.add(new BenchmarkRequest(BenchmarkRequest.Mode.MEASURE, Scene.CURRENT, pairs.getFirst()));
					wait = 20 * 10;
					step = 7;
				}
			}
			case 7 -> {
				if (runHere(minecraft)) {
					List<BenchmarkRecord> runs = BenchmarkStore.history().runs();
					BenchmarkRecord before = runs.stream().filter(r -> pairs.getFirst().equals(r.pairId()) && BenchmarkRecord.BEFORE.equals(r.phase()))
							.findFirst().orElseThrow();
					BenchmarkRecord after = runs.stream().filter(r -> pairs.getFirst().equals(r.pairId()) && BenchmarkRecord.AFTER.equals(r.phase()))
							.findFirst().orElseThrow();
					BenchmarkMath.Gain gain = io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecords.gain(before, after);
					log(String.format(Locale.ROOT, "manual pair: 1%% low %.1f -> %.1f, avg %.1f -> %.1f: low %s, avg %s (cv %s / %s)",
							before.result().onePercentLowFps(), after.result().onePercentLowFps(), before.result().avgFps(), after.result().avgFps(),
							pct(gain.lowPercent()), pct(gain.avgPercent()), before.result().cv(), after.result().cv()));
					minecraft.options.renderDistance().set(rd);
					minecraft.options.save();
					finish(minecraft, "NOW: done");
				}
			}
			default -> {
			}
		}
	}

	// One queued Measure run where the player stands; true once it's done (the result screen closed).
	private boolean runHere(Minecraft minecraft) {
		if (running) {
			if (BenchmarkController.running()) {
				return false;
			}
			running = false;
			BenchmarkController.Outcome outcome = BenchmarkController.lastOutcome();
			if (outcome == null || outcome.cancelled() || outcome.record() == null) {
				log("the run ended without a record; running it again");
				wait = 40;
				return false;
			}
			queue.removeFirst();
			minecraft.gui.setScreen(null);
			return true;
		}
		String refused = BenchmarkController.tryStart(minecraft, queue.getFirst(), BenchmarkController.defaultConfig());
		if (refused != null) {
			log("refused " + refused + "; retrying");
			wait = 100;
			return false;
		}
		running = true;
		return false;
	}

	// ---- restart1..3

	private void restart(Minecraft minecraft, String mode) {
		TryItView view = controller.tryIt();
		if (step == 0) {
			if (!(minecraft.gui.screen() instanceof TitleScreen) || ticks < 100) {
				return;
			}
			minecraft.options.pauseOnLostFocus = false;
			step = 1;
			return;
		}
		switch (mode) {
			case "restart1" -> {
				if (step == 1) {
					String from = SettingsBridge.read(minecraft).get(DEFER);
					String to = "ALWAYS".equals(from) ? "ONE_FRAME" : "ALWAYS";
					log("RESTART try: " + DEFER + " " + from + " -> " + to + ": " + controller.startTryIt(rec(DEFER, from, to), Scene.BENCHMARK_WORLD)
							.getString());
					step = 2;
				} else if (view.stage() == Stage.AWAITING_RESTART) {
					log("AWAITING_RESTART: staged under " + view.tryIt().entryId() + "; quitting (the helper applies it at the exit)");
					finish(minecraft, "restart1: done");
				} else if (view.stage() == Stage.STOPPED_BEFORE || view.stage() == Stage.NOT_APPLIED) {
					finish(minecraft, "FAILED: " + view.stage());
				}
			}
			case "restart2" -> {
				if (step == 1) {
					log("after the restart: " + view.stage() + ", same session " + view.sameSession() + ", " + DEFER + " = "
							+ SettingsBridge.read(minecraft).get(DEFER));
					if (view.stage() != Stage.READY) {
						finish(minecraft, "FAILED: not READY after the restart: " + view.stage());
						return;
					}
					controller.tryItMeasureNow();
					step = 2;
				} else if (step == 2 && view.stage() == Stage.RESULT) {
					entryId = view.tryIt().entryId();
					log("RESULT: " + verdict(view));
					minecraft.gui.setScreen(new UndoScreen(new TitleScreen(), controller, entryId));
					step = 3;
					wait = 40;
				} else if (step == 3 && minecraft.gui.screen() instanceof UndoScreen undo && undo.plan() != null) {
					press(minecraft, "rigtune.undo.confirm");
					step = 4;
					wait = 60;
				} else if (step == 4) {
					log("after Revert: " + view.stage() + "; pending changes " + controller.hasPendingChanges());
					if (view.stage() == Stage.REVERT_PENDING) {
						controller.tryItCancel();
						finish(minecraft, "restart2: done");
					}
				}
			}
			case "restart3" -> {
				log(DEFER + " = " + SettingsBridge.read(minecraft).get(DEFER) + "; try " + view.stage());
				List<JournalEntry> entries = ClientJournal.get().entries();
				for (JournalEntry e : entries.subList(Math.max(0, entries.size() - 4), entries.size())) {
					log("History: " + e.kind() + " " + e.id() + " undoOf " + e.undoOf() + ": "
							+ e.changes().stream().map(c -> c.key() + " " + c.before() + " -> " + c.after() + " " + c.status()).toList());
				}
				log("recent: " + io.github.chaotix345.rigtune.core.tryit.TryItStore.shared(controller.configDir()).recent().stream()
						.map(c -> c.key() + " " + c.decision()).toList());
				finish(minecraft, "restart3: done");
			}
			default -> finish(minecraft, "FAILED: unknown mode");
		}
	}

	// ---- helpers

	private static Recommendation rec(String key, String from, String to) {
		return new Recommendation("dev:" + key, Category.SETTING, Impact.MEDIUM, key, "Try it dev run", new Action.SetSetting(key, from, to), true);
	}

	private static String verdict(TryItView view) {
		TryItVerdict.Verdict v = view.verdict();
		if (v == null) {
			return "no verdict (" + view.stage() + ")";
		}
		return String.format(Locale.ROOT, "%s: low %s, avg %s, floor %.1f%%, causes %s, caveats %s; before %s, after %s", v.kind(), pct(v.lowPercent()),
				pct(v.avgPercent()), v.floorPercent(), v.causes(), v.caveats(), numbers(view.before()), numbers(view.after()));
	}

	private static String numbers(@Nullable BenchmarkRecord r) {
		return r == null || r.result() == null ? "-" : String.format(Locale.ROOT, "1%% low %.1f avg %.1f cv %s", r.result().onePercentLowFps(),
				r.result().avgFps(), r.result().cv());
	}

	private static String pct(@Nullable Double value) {
		return value == null ? "?" : BenchmarkMath.percent(value);
	}

	private static void press(Minecraft minecraft, String key) {
		Screen screen = minecraft.gui.screen();
		for (var widget : Screens.getWidgets(screen)) {
			if (widget instanceof Button button && button.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key)) {
				button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)));
				return;
			}
		}
		log("no " + key + " button on " + screen);
	}

	private void finish(Minecraft minecraft, String result) {
		step = -1;
		log("RESULT " + result);
		minecraft.stop();
	}

	private static void log(String message) {
		RigTune.LOGGER.info("Try it dev: {}", message);
	}
}
