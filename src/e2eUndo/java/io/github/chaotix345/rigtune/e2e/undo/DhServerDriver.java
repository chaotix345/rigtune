package io.github.chaotix345.rigtune.e2e.undo;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local only (docs/v0.5/SPEC.md AC3f.4; tools/e2e/dhnote/dh_server_note.py): Distant Horizons on a server that limits view
 * distance. Joins -Drigtune.e2e.dhServer (host:port) once per phase of -Drigtune.e2e.dhPhases (name:seconds, comma-separated),
 * render distance 16, creative, noon and clear weather, flying at Y=HEIGHT above spawn so the view isn't blocked. With the HUD
 * hidden it screenshots straight down every 30 s; at the phase's end one shot with the HUD and the debug overlay, then straight
 * down and toward the horizon in four directions; then /stop (the script's ops.json makes this user an operator). The script
 * restarts the server for
 * the next phase, and the driver joins again (retrying while the server starts). Inert without -Drigtune.e2e.dhServer.
 * Results go to -Drigtune.e2e.out as driver-dh.json; screenshots to the instance's screenshots folder.
 */
public final class DhServerDriver implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("RigTune E2E DH");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();
	private static final int SECOND = 20;
	private static final int JOIN_TIMEOUT = 300 * SECOND;
	private static final int RETRY = 5 * SECOND;
	private static final int SHOT_EVERY = 30 * SECOND;
	private static final int TURN_SETTLE = 3 * SECOND;
	private static final int STOP_TIMEOUT = 90 * SECOND;
	private static final int HEIGHT = 330;
	private static final float DOWN = 90f;
	private static final float HORIZON = 25f;
	// The phase's end: {label, yaw, pitch, 1 = HUD and debug overlay shown}.
	private static final Object[][] VIEWS = {{"f3", 0f, HORIZON, 1}, {"down", 0f, DOWN, 0}, {"yaw000", 0f, HORIZON, 0},
			{"yaw090", 90f, HORIZON, 0}, {"yaw180", 180f, HORIZON, 0}, {"yaw270", 270f, HORIZON, 0}};

	private enum Step {
		WAIT_TITLE, CONNECT, WAIT_WORLD, IN_WORLD, TURN, WAIT_GONE, DONE
	}

	private final String server = System.getProperty("rigtune.e2e.dhServer");
	private final List<String[]> phases = new ArrayList<>();
	private final Map<String, Object> result = new LinkedHashMap<>();
	private final List<String> events = new ArrayList<>();
	private final List<Map<String, Object>> joins = new ArrayList<>();
	private Path out;
	private Step step = Step.WAIT_TITLE;
	private int phase;
	private int ticks;
	private int stepTicks;
	private int phaseTicks;
	private int turn;
	// Ticks to wait before the next connection attempt (a retry while the server starts).
	private int connectDelay;

	@Override
	public void onInitializeClient() {
		if (server == null) {
			return;
		}
		for (String part : System.getProperty("rigtune.e2e.dhPhases", "").split(",")) {
			if (!part.isBlank()) {
				phases.add(part.trim().split(":", 2));
			}
		}
		out = Path.of(System.getProperty("rigtune.e2e.out", "e2e-out")).toAbsolutePath();
		result.put("server", server);
		result.put("ok", false);
		result.put("error", null);
		result.put("events", events);
		result.put("joins", joins);
		ModContainer dh = FabricLoader.getInstance().getModContainer("distanthorizons").orElse(null);
		result.put("distantHorizons", dh == null ? null : dh.getMetadata().getVersion().getFriendlyString());
		event("driver loaded, server " + server + ", phases " + phases.stream().map(p -> p[0] + ":" + p[1]).toList());
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
	}

	private void tick(Minecraft minecraft) {
		if (step == Step.DONE) {
			return;
		}
		ticks++;
		stepTicks++;
		phaseTicks++;
		try {
			switch (step) {
				case WAIT_TITLE -> {
					if (minecraft.gui.screen() instanceof TitleScreen && minecraft.gui.overlay() == null) {
						minecraft.options.renderDistance().set(16);
						event("title screen; render distance 16");
						connect(1);
					}
				}
				case CONNECT -> {
					if (stepTicks < connectDelay) {
						return;
					}
					ConnectScreen.startConnecting(minecraft.gui.screen(), minecraft, ServerAddress.parseString(server),
							new ServerData("RigTune DH test", server, ServerData.Type.OTHER), false, null);
					event("phase " + name() + ": connecting");
					next(Step.WAIT_WORLD);
				}
				case WAIT_WORLD -> {
					// On any screen (a connect screen that hangs too).
					if (phaseTicks > JOIN_TIMEOUT) {
						fail(minecraft, "phase " + name() + ": no join within " + JOIN_TIMEOUT / SECOND + " s");
						return;
					}
					if (minecraft.level != null && minecraft.player != null && minecraft.gui.screen() == null) {
						Map<String, Object> join = new LinkedHashMap<>();
						join.put("phase", name());
						join.put("afterSeconds", phaseTicks / SECOND);
						// The server's view distance as the client applies it (render distance 16 is the cap).
						join.put("viewDistance", minecraft.options.getEffectiveRenderDistance());
						joins.add(join);
						for (String command : List.of("gamemode creative", "time set noon", "weather clear", "tp @s ~ " + HEIGHT + " ~")) {
							minecraft.player.connection.sendCommand(command);
						}
						event("phase " + name() + ": in the world at " + minecraft.player.blockPosition().toShortString()
								+ ", server view distance as seen: " + minecraft.options.getEffectiveRenderDistance());
						next(Step.IN_WORLD);
					} else if (minecraft.gui.screen() instanceof DisconnectedScreen || minecraft.gui.screen() instanceof TitleScreen) {
						minecraft.gui.setScreen(new TitleScreen());
						connectDelay = RETRY;
						next(Step.CONNECT);
					}
				}
				case IN_WORLD -> {
					look(minecraft, 0f, DOWN, false);
					if (stepTicks % SHOT_EVERY == 0) {
						shot(minecraft, "dh-" + name() + "-" + stepTicks / SECOND + "s-down.png");
					}
					if (stepTicks == 10 * SECOND) {
						event("phase " + name() + ": at " + minecraft.player.blockPosition().toShortString());
					}
					if (stepTicks >= seconds() * SECOND) {
						turn = 0;
						next(Step.TURN);
					}
				}
				case TURN -> {
					Object[] view = VIEWS[turn];
					look(minecraft, (float) view[1], (float) view[2], (int) view[3] == 1);
					if (stepTicks == TURN_SETTLE) {
						shot(minecraft, String.format(Locale.ROOT, "dh-%s-end-%s.png", name(), view[0]));
						turn++;
						stepTicks = 0;
						if (turn == VIEWS.length) {
							look(minecraft, 0f, DOWN, false);
							minecraft.player.connection.sendCommand("stop");
							event("phase " + name() + ": /stop");
							next(Step.WAIT_GONE);
						}
					}
				}
				case WAIT_GONE -> {
					if (minecraft.level == null) {
						event("phase " + name() + ": disconnected (" + (minecraft.gui.screen() == null ? "no screen"
								: minecraft.gui.screen().getClass().getSimpleName()) + ")");
						phase++;
						if (phase == phases.size()) {
							finish(minecraft);
							return;
						}
						minecraft.gui.setScreen(new TitleScreen());
						connect(RETRY);
					} else if (stepTicks > STOP_TIMEOUT) {
						fail(minecraft, "phase " + name() + ": still connected " + STOP_TIMEOUT / SECOND + " s after /stop");
					}
				}
				default -> {
				}
			}
		} catch (Throwable t) {
			LOGGER.error("E2E DH driver failed", t);
			fail(minecraft, t.toString());
		}
	}

	// A new phase's first connection attempt, after delay ticks.
	private void connect(int delay) {
		connectDelay = delay;
		phaseTicks = 0;
		next(Step.CONNECT);
	}

	private String name() {
		return phases.get(phase)[0];
	}

	private int seconds() {
		return Integer.parseInt(phases.get(phase)[1]);
	}

	// Flying (creative), facing yaw (0 = south) at pitch (90 = straight down); the HUD and the debug overlay only when shown.
	private static void look(Minecraft minecraft, float yaw, float pitch, boolean shown) {
		if (minecraft.player == null) {
			return;
		}
		if (minecraft.player.getAbilities().mayfly && !minecraft.player.getAbilities().flying) {
			minecraft.player.getAbilities().flying = true;
			minecraft.player.onUpdateAbilities();
		}
		// The game menu a lost window focus opens (pauseOnLostFocus is off in the script's options.txt too).
		if (minecraft.gui.screen() instanceof PauseScreen) {
			minecraft.gui.setScreen(null);
		}
		minecraft.player.setYRot(yaw);
		minecraft.player.setXRot(pitch);
		if (minecraft.gui.hud.isHidden() == shown) {
			minecraft.gui.hud.toggle();
		}
		minecraft.debugEntries.setOverlayVisible(shown);
	}

	private void shot(Minecraft minecraft, String file) {
		Screenshot.grab(minecraft.gameDirectory, file, minecraft.gameRenderer.mainRenderTarget(), 1,
				message -> event("screenshot " + file + ": " + message.getString()));
	}

	private void next(Step nextStep) {
		step = nextStep;
		stepTicks = 0;
	}

	private void event(String message) {
		String line = String.format(Locale.ROOT, "t+%.1fs %s", ticks / (double) SECOND, message);
		events.add(line);
		LOGGER.info("[E2E DH] {}", line);
	}

	private void fail(Minecraft minecraft, String error) {
		result.put("error", error);
		event("FAILED: " + error);
		finish(minecraft);
	}

	private void finish(Minecraft minecraft) {
		if (result.get("error") == null) {
			result.put("ok", true);
		}
		step = Step.DONE;
		event("quitting");
		try {
			Files.createDirectories(out);
			Files.writeString(out.resolve("driver-dh.json"), GSON.toJson(result), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.error("Could not write the E2E DH result", e);
		}
		if (minecraft.level != null) {
			minecraft.disconnectWithSavingScreen();
		}
		minecraft.stop();
	}
}
