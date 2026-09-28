package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.StutterScreen;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import io.github.chaotix345.rigtune.core.stutter.FixStore;
import io.github.chaotix345.rigtune.core.stutter.FixTracker;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

// Development only (docs/v0.5/SPEC.md 5, AC5.14: the stutter fixes' calibration run on a real PC), inert unless
// -Drigtune.dev.stutterScript=fixcalibrate is set. Each launch plays one step, decided by stutter-fixes.json:
// - no Sodium fix tracked ("before"): the session monitor on, RigTune's benchmark world, a teleport into never-generated
//   terrain every 20 s for PLAY_SECONDS, then the Stutter Doctor: the analysis, the evidence and the offer are logged, and
//   the Sodium fix is applied if it's offered (it's staged: the helper patches Sodium's file at the exit); quit.
// - the fix staged or measuring ("after"): the same play with fresh terrain, leave the world (the session ends and is
//   compared), log the record (the verdict, φ, p, both sides), then Undo this on its entry; quit. It plays
//   AFTER_EXTRA_SECONDS longer, so its gameplay never ends below FixTracker.afterTarget(before); while the record still
//   measures (too little gameplay, e.g. the window lost focus), it quits without the Undo and the next launch plays on.
// - otherwise ("check"): log the record and Sodium's Chunk Updates value; quit.
// Every step is logged with the prefix "Dev fix calibration:". Run it in a plain client with Sodium's Chunk Updates set
// to Immediate (ZERO_FRAMES), e.g. JAVA_TOOL_OPTIONS=-Drigtune.dev.stutterScript=fixcalibrate.
final class DevFixCalibration {
	static final String MODE = "fixcalibrate";
	static final boolean ON = MODE.equals(System.getProperty(DevStutter.SCRIPT));
	private static final String SODIUM = "stutter-sodium-defer";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final int TICKS_PER_SECOND = 20;
	private static final int PLAY_SECONDS = 390;
	private static final int AFTER_EXTRA_SECONDS = 60;
	private static final int TELEPORT_EVERY_SECONDS = 20;

	private enum Step { BEFORE, AFTER, CHECK }

	private enum Stage { WAIT_FOR_TITLE, READING, OPENING, PLAYING, REPORT, LEAVING, TRACKING, UNDOING, QUITTING, DONE }

	private static Stage stage = Stage.WAIT_FOR_TITLE;
	private static Step step = Step.CHECK;
	private static int ticks;
	private static int teleports;
	private static long base;
	private static FixTracker.@Nullable Record fix;
	private static @Nullable CompletableFuture<List<FixTracker.Record>> reading;
	private static @Nullable CompletableFuture<@Nullable UndoPlan> planning;

	private DevFixCalibration() {
	}

	private static int playSeconds() {
		return step == Step.AFTER ? PLAY_SECONDS + AFTER_EXTRA_SECONDS : PLAY_SECONDS;
	}

	static void tick(Minecraft minecraft, StutterService service) {
		if (!ON || stage == Stage.DONE) {
			return;
		}
		ticks++;
		RigTuneController controller = RigTuneClient.controller();
		switch (stage) {
			case WAIT_FOR_TITLE -> {
				if (minecraft.gui.screen() instanceof TitleScreen && ticks > 60 && controller.report() != null) {
					reading = CompletableFuture.supplyAsync(() -> FixStore.shared(FabricLoader.getInstance().getConfigDir()).records(), Probes.EXECUTOR);
					next(Stage.READING);
				}
			}
			case READING -> {
				if (reading != null && reading.isDone()) {
					fix = reading.join().stream().filter(r -> SODIUM.equals(r.adviceId())).reduce((a, b) -> b).orElse(null);
					step = fix == null ? Step.BEFORE : fix.state().tracking() ? Step.AFTER : Step.CHECK;
					log("step " + step + "; Sodium's Chunk Updates " + SettingsBridge.read(minecraft).get(DEFER) + "; record " + fix);
					if (step == Step.CHECK) {
						quit(minecraft, "check done");
						return;
					}
					minecraft.options.pauseOnLostFocus = false;
					service.setMonitor(true);
					base = ThreadLocalRandom.current().nextLong(20, 180) * 10_000;
					boolean opened = BenchmarkWorld.open(minecraft, minecraft.gui.screen());
					log("monitor on; opening the benchmark world: " + opened);
					next(opened ? Stage.OPENING : Stage.DONE);
				}
			}
			case OPENING -> {
				if (BenchmarkWorld.state() == BenchmarkWorld.State.READY && minecraft.level != null) {
					log("in the benchmark world; " + playSeconds() + " s of play, a teleport into new terrain every " + TELEPORT_EVERY_SECONDS + " s");
					next(Stage.PLAYING);
				} else if (BenchmarkWorld.state() == BenchmarkWorld.State.FAILED) {
					quit(minecraft, "FAILED: the benchmark world didn't open");
				}
			}
			case PLAYING -> {
				if (ticks % (TELEPORT_EVERY_SECONDS * TICKS_PER_SECOND) == 0) {
					long offset = base + 3_000L * teleports++;
					command(minecraft, "tp @a " + offset + " 200 " + offset);
				}
				if (ticks >= playSeconds() * TICKS_PER_SECOND) {
					if (step == Step.BEFORE) {
						minecraft.gui.setScreen(new StutterScreen(null, controller));
						log("opened the Stutter Doctor");
						next(Stage.REPORT);
					} else {
						leave(minecraft);
					}
				}
			}
			case REPORT -> {
				StutterView view = service.view();
				if (view.live() && view.report() != null && ticks > 5 * TICKS_PER_SECOND || ticks > 60 * TICKS_PER_SECOND) {
					log("report:\n" + service.summary());
					FixOffer offer = view.fixes().get(SODIUM);
					log("advice " + view.advice().stream().map(a -> a.id()).toList() + "; fixes " + view.fixes());
					if (offer instanceof FixOffer.Offer o) {
						log("applying the offered fix: " + controller.applyStutterFix(o).getString());
					} else {
						log("no Sodium fix offered: " + offer);
					}
					leave(minecraft);
				}
			}
			case LEAVING -> {
				if (minecraft.level == null && ticks > 5 * TICKS_PER_SECOND) {
					if (step == Step.AFTER) {
						next(Stage.TRACKING);
					} else {
						quit(minecraft, "before step done");
					}
				}
			}
			case TRACKING -> {
				// The ended session's tracking runs on the io chain: read the record until it moved on (at most 30 s).
				if (reading == null || reading.isDone()) {
					FixTracker.Record now = reading == null ? null : reading.join().stream().filter(r -> fix != null && fix.entryId().equals(r.entryId()))
							.findFirst().orElse(null);
					if (now != null && (now.state() != fix.state() || now.skipped() != fix.skipped() || !java.util.Objects.equals(now.after(), fix.after()))
							|| ticks > 30 * TICKS_PER_SECOND) {
						log("record after the session: " + now);
						if (now != null && now.verdict() != null) {
							log(String.format(java.util.Locale.ROOT, "verdict %s: before %.2f hitches/min (%.0f ms lost/min), after %.2f (%.0f); phi %.3f, pLess %.6g, pMore %.6g",
									now.verdict().kind(), now.verdict().beforePerMinute(), now.verdict().lostBeforePerMinute(), now.verdict().afterPerMinute(),
									now.verdict().lostAfterPerMinute(), now.verdict().phi(), now.verdict().pLess(), now.verdict().pMore()));
						}
						if (now != null && now.state() == FixTracker.State.MEASURING) {
							// Not enough gameplay yet (e.g. the window lost focus): the next launch plays on with the fix in place.
							quit(minecraft, "after step: still measuring (" + (now.after() == null ? 0 : Math.round(now.after().gameplaySeconds())) + " of "
									+ Math.round(FixTracker.afterTarget(now.before())) + " s); the next launch plays on");
							return;
						}
						String entryId = fix.entryId();
						planning = CompletableFuture.supplyAsync(() -> controller.undoPlanFor(entryId), Probes.EXECUTOR);
						next(Stage.UNDOING);
					} else if (ticks % TICKS_PER_SECOND == 0) {
						reading = CompletableFuture.supplyAsync(() -> FixStore.shared(FabricLoader.getInstance().getConfigDir()).records(), Probes.EXECUTOR);
					}
				}
			}
			case UNDOING -> {
				if (planning != null && planning.isDone()) {
					UndoPlan plan = planning.join();
					log("undo plan: " + plan);
					if (plan != null && plan.problem() == null) {
						log("undo: " + controller.undo(plan).getString());
					}
					quit(minecraft, "after step done");
				}
			}
			case QUITTING, DONE -> {
			}
		}
	}

	private static void leave(Minecraft minecraft) {
		minecraft.gui.setScreen(null);
		BenchmarkWorld.leave(minecraft, null);
		log("leaving the world");
		next(Stage.LEAVING);
	}

	private static void command(Minecraft minecraft, String command) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server != null) {
			server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
		}
	}

	private static void next(Stage next) {
		stage = next;
		ticks = 0;
	}

	private static void quit(Minecraft minecraft, String result) {
		log(result + "; stopping the client");
		stage = Stage.DONE;
		minecraft.stop();
	}

	private static void log(String message) {
		RigTune.LOGGER.info("Dev fix calibration: {}", message);
	}
}
