package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.ui.StutterScreen;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Development only (docs/v0.4/SPEC.md 5; the induced-stutter runs of AC5.8), read once and inert unless set:
// - -Drigtune.dev.forceGcEverySec=N: while a capture is on, a daemon thread calls System.gc() every N seconds.
// - -Drigtune.dev.stutterScript=teleport: from the title screen, turn the session monitor on, open the benchmark world,
//   stand still 20 s, `tp @a 200000 200 200000` (never-generated terrain), 30 s, `save-all`, 10 s, open the Stutter
//   Doctor, then leave the world (which saves the session), log stutter.json and quit. Every step is logged with the
//   prefix "Dev stutter:". Run it in a plain client, e.g. JAVA_TOOL_OPTIONS=-Drigtune.dev.stutterScript=teleport.
final class DevStutter {
	static final String FORCE_GC = "rigtune.dev.forceGcEverySec";
	static final String SCRIPT = "rigtune.dev.stutterScript";
	private static final int GC_SECONDS = Integer.getInteger(FORCE_GC, 0);
	private static final @Nullable String MODE = System.getProperty(SCRIPT);
	private static final int TICKS_PER_SECOND = 20;
	private static final int GIVE_UP_TICKS = 20 * 300;

	private enum Stage { WAIT_FOR_TITLE, OPENING, STILL, AFTER_TELEPORT, AFTER_SAVE, REPORT, LEAVING, DONE }

	private static volatile @Nullable Thread gcThread;
	private static Stage stage = Stage.WAIT_FOR_TITLE;
	private static int ticks;
	private static int total;

	private DevStutter() {
	}

	static synchronized void startForcedGc() {
		if (GC_SECONDS <= 0 || gcThread != null) {
			return;
		}
		Thread t = new Thread(() -> {
			try {
				while (gcThread == Thread.currentThread()) {
					Thread.sleep(GC_SECONDS * 1000L);
					if (gcThread == Thread.currentThread()) {
						RigTune.LOGGER.info("Dev stutter: System.gc() ({} = {})", FORCE_GC, GC_SECONDS);
						System.gc();
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}, "RigTune dev GC");
		t.setDaemon(true);
		gcThread = t;
		t.start();
	}

	static synchronized void stopForcedGc() {
		Thread t = gcThread;
		gcThread = null;
		if (t != null) {
			t.interrupt();
		}
	}

	static void tick(Minecraft minecraft, StutterService service) {
		if (!"teleport".equals(MODE) || stage == Stage.DONE) {
			return;
		}
		ticks++;
		if (++total > GIVE_UP_TICKS) {
			finish(minecraft, "FAILED: gave up after 5 minutes");
			return;
		}
		switch (stage) {
			case WAIT_FOR_TITLE -> {
				if (minecraft.gui.screen() instanceof TitleScreen && ticks > 60) {
					minecraft.options.pauseOnLostFocus = false;
					service.setMonitor(true);
					boolean opened = BenchmarkWorld.open(minecraft, minecraft.gui.screen());
					log("monitor on; opening the benchmark world: " + opened);
					next(opened ? Stage.OPENING : Stage.DONE);
				}
			}
			case OPENING -> {
				if (BenchmarkWorld.state() == BenchmarkWorld.State.READY && minecraft.level != null) {
					log("in the benchmark world; standing still for 20 s");
					next(Stage.STILL);
				} else if (BenchmarkWorld.state() == BenchmarkWorld.State.FAILED) {
					finish(minecraft, "FAILED: the benchmark world didn't open");
				}
			}
			case STILL -> {
				if (ticks >= 20 * TICKS_PER_SECOND) {
					command(minecraft, "tp @a 200000 200 200000");
					next(Stage.AFTER_TELEPORT);
				}
			}
			case AFTER_TELEPORT -> {
				if (ticks >= 30 * TICKS_PER_SECOND) {
					command(minecraft, "save-all");
					next(Stage.AFTER_SAVE);
				}
			}
			case AFTER_SAVE -> {
				if (ticks >= 10 * TICKS_PER_SECOND) {
					minecraft.gui.setScreen(new StutterScreen(null, RigTuneClient.controller()));
					log("opened the Stutter Doctor");
					next(Stage.REPORT);
				}
			}
			case REPORT -> {
				StutterView view = service.view();
				if (view.report() != null || ticks > 20 * TICKS_PER_SECOND) {
					log("report:\n" + service.summary());
					minecraft.gui.setScreen(null);
					BenchmarkWorld.leave(minecraft, null);
					next(Stage.LEAVING);
				}
			}
			case LEAVING -> {
				if (minecraft.level == null && ticks > 5 * TICKS_PER_SECOND) {
					Path file = StutterStore.file(FabricLoader.getInstance().getConfigDir());
					try {
						log(file + ":\n" + Files.readString(file, StandardCharsets.UTF_8));
					} catch (IOException e) {
						log("could not read " + file + ": " + e);
					}
					finish(minecraft, "PASSED");
				}
			}
			case DONE -> {
			}
		}
	}

	private static void command(Minecraft minecraft, String command) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			log("no integrated server for " + command);
			return;
		}
		log(command);
		server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
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
		RigTune.LOGGER.info("Dev stutter: {}", message);
	}
}
