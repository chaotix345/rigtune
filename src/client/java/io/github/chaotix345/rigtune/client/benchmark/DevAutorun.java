package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.jspecify.annotations.Nullable;

// Development only, and only when -Drigtune.dev.autorun is set (./gradlew :<mc>:runBenchmarkAutorun): drives the
// benchmark-world flow in a plain client, without the game-test harness, then quits. "benchmark-world" runs a short
// Tune and leaves through the real in-tick exit; "benchmark-world-cancel" presses pause (Esc) mid-run. Every step is
// logged with the prefix "Dev autorun:".
final class DevAutorun {
	private static final @Nullable String MODE = System.getProperty("rigtune.dev.autorun");
	private static final BenchmarkController.Config SHORT = new BenchmarkController.Config(3, 2.0, 1.0, 20.0);
	private static final int GIVE_UP_TICKS = 20 * 600;

	private enum Stage { WAIT_FOR_TITLE, RUNNING, WAIT_AFTER, DONE }

	private static Stage stage = Stage.WAIT_FOR_TITLE;
	private static int ticks;
	private static int total;
	private static boolean paused;

	private DevAutorun() {
	}

	static boolean enabled() {
		return MODE != null;
	}

	static void tick(Minecraft minecraft) {
		if (MODE == null || stage == Stage.DONE) {
			return;
		}
		ticks++;
		if (++total > GIVE_UP_TICKS) {
			finish(minecraft, "FAILED: gave up after 10 minutes");
			return;
		}
		switch (stage) {
			case WAIT_FOR_TITLE -> {
				if (!(minecraft.gui.screen() instanceof TitleScreen) || ticks < 60) {
					return;
				}
				// The window may not have focus in an automated run; losing it must not pause (and so cancel) the run.
				minecraft.options.pauseOnLostFocus = false;
				String refusal = BenchmarkController.tryStart(minecraft,
						new BenchmarkRequest(BenchmarkRequest.Mode.TUNE, BenchmarkRequest.Scene.BENCHMARK_WORLD, null), SHORT);
				log("mode " + MODE + ": start from the title screen -> " + (refusal == null ? "started" : "refused " + refusal));
				if (refusal != null) {
					finish(minecraft, "FAILED: refused");
					return;
				}
				next(Stage.RUNNING);
			}
			case RUNNING -> {
				if ("benchmark-world-cancel".equals(MODE) && !paused && BenchmarkController.progress() != null && ticks > 40) {
					paused = true;
					log("pressing pause (Esc) mid-run, progress: " + BenchmarkController.progress().getString());
					minecraft.pauseGame(false);
				}
				if (!BenchmarkController.running() && !BenchmarkWorld.busy() && minecraft.level == null) {
					BenchmarkController.Outcome outcome = BenchmarkController.lastOutcome();
					log("back from the benchmark world: world state " + BenchmarkWorld.state() + ", screen "
							+ (minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName())
							+ ", outcome " + (outcome == null ? "none" : (outcome.cancelled() ? "cancelled" : "finished")
							+ ", record " + (outcome.record() == null ? "none" : outcome.record().id())));
					next(Stage.WAIT_AFTER);
				}
			}
			case WAIT_AFTER -> {
				if (ticks >= 60) {
					finish(minecraft, "screen now " + (minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName())
							+ "; PASSED");
				}
			}
			case DONE -> {
			}
		}
	}

	private static void next(Stage next) {
		stage = next;
		ticks = 0;
	}

	private static void finish(Minecraft minecraft, String result) {
		log(result + "; stopping the client");
		stage = Stage.DONE;
		minecraft.stop();
	}

	private static void log(String message) {
		RigTune.LOGGER.info("Dev autorun: {}", message);
	}
}
