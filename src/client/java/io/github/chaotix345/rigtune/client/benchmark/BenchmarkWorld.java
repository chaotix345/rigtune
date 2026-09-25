package io.github.chaotix345.rigtune.client.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.core.apply.AtomicFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

// The dedicated benchmark scene (docs/v0.2/SPEC.md item 6): a creative singleplayer save with a fixed seed, opened
// through the production WorldOpenFlows API, with time, weather and mob spawning frozen and the camera at a fixed spot.
// It is recreated when missing or made by another Minecraft version, since the terrain for a seed can change between
// versions.
public final class BenchmarkWorld {
	public static final String LEVEL_ID = "rigtune-benchmark";
	public static final long SEED = 8675309L;
	public static final int CAMERA_X = 0;
	public static final int CAMERA_Z = 192;
	private static final int CAMERA_ABOVE_SURFACE = 10;
	private static final String MARKER = "rigtune-benchmark.json";
	private static final int TIMEOUT_TICKS = 20 * 90;
	private static final Gson GSON = new Gson();
	// In a client game test, leaving a world from inside a client tick deadlocks the harness's tick phaser (the render
	// thread waits in IntegratedServer.halt for the server thread, which waits in the phaser for the render thread).
	// There the test leaves from its own thread with exitNow(), the way the harness closes its worlds.
	private static final boolean HARNESS = System.getProperty("fabric.client.gametest") != null;

	public enum State { IDLE, OPENING, SETTING_UP, READY, AWAITING_EXIT, LEAVING, FAILED }

	private record Marker(String mcVersion, long seed) {
	}

	private static State state = State.IDLE;
	private static boolean created;
	private static int ticks;
	private static volatile @Nullable Vec3 target;
	private static @Nullable Runnable afterExit;

	private BenchmarkWorld() {
	}

	public static boolean supported() {
		return true;
	}

	public static State state() {
		return state;
	}

	public static @Nullable Vec3 cameraPosition() {
		return target;
	}

	public static boolean busy() {
		return state == State.OPENING || state == State.SETTING_UP || state == State.READY || state == State.AWAITING_EXIT
				|| state == State.LEAVING;
	}

	static boolean needsRecreate(@Nullable String recordedMcVersion, String runningMcVersion) {
		return recordedMcVersion == null || !recordedMcVersion.equals(runningMcVersion);
	}

	/** Opens the benchmark world from a screen with no world loaded, creating or recreating it first when needed. */
	public static boolean open(Minecraft minecraft, @Nullable Screen parent) {
		if (minecraft.level != null || busy()) {
			return false;
		}
		LevelStorageSource source = minecraft.getLevelSource();
		String mcVersion = HardwareProbe.minecraftVersion();
		target = null;
		ticks = 0;
		afterExit = null;
		try {
			if (source.levelExists(LEVEL_ID) && !needsRecreate(recordedVersion(source), mcVersion)) {
				created = false;
				state = State.OPENING;
				RigTune.LOGGER.info("Benchmark world: opening the existing {}", LEVEL_ID);
				minecraft.createWorldOpenFlows().openWorld(LEVEL_ID, () -> {
					RigTune.LOGGER.warn("Benchmark world: opening {} was cancelled", LEVEL_ID);
					state = State.FAILED;
					minecraft.gui.setScreen(parent);
				});
				return true;
			}
			if (source.levelExists(LEVEL_ID)) {
				RigTune.LOGGER.info("Benchmark world: recreating {} for Minecraft {}", LEVEL_ID, mcVersion);
				try (LevelStorageSource.LevelStorageAccess access = source.createAccess(LEVEL_ID)) {
					access.deleteLevel();
				}
			}
			created = true;
			state = State.OPENING;
			LevelSettings settings = new LevelSettings("RigTune Benchmark", GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
			minecraft.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, new WorldOptions(SEED, true, false),
					WorldPresets::createNormalWorldDimensions, parent);
			return true;
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.error("Benchmark world: could not open {}", LEVEL_ID, e);
			state = State.FAILED;
			return false;
		}
	}

	public static void tick(Minecraft minecraft) {
		switch (state) {
			case OPENING -> {
				if (++ticks > TIMEOUT_TICKS) {
					fail(minecraft, "the world did not load");
					return;
				}
				IntegratedServer server = minecraft.getSingleplayerServer();
				if (minecraft.level == null || minecraft.player == null || server == null || !server.isReady()) {
					return;
				}
				if (created) {
					writeMarker(minecraft.getLevelSource());
				}
				state = State.SETTING_UP;
				ticks = 0;
				server.execute(() -> setUp(server));
			}
			case SETTING_UP -> {
				if (++ticks > TIMEOUT_TICKS) {
					fail(minecraft, "the camera position was not reached");
					return;
				}
				Vec3 at = target;
				LocalPlayer player = minecraft.player;
				if (at == null || player == null) {
					return;
				}
				// Creative flight, client side only (as the benchmark does), so the player stays at the camera position.
				player.getAbilities().flying = true;
				if (minecraft.gui.screen() == null && player.position().distanceTo(at) < 1.0) {
					state = State.READY;
					minecraft.gui.toastManager().clear();
					RigTune.LOGGER.info("Benchmark world: ready at {}", at);
				}
			}
			case READY -> {
				if (minecraft.level == null) {
					state = State.IDLE;
				}
			}
			case LEAVING -> {
				if (minecraft.level != null || minecraft.getSingleplayerServer() != null) {
					return;
				}
				// A disconnect deferred by the game-test harness ends on its "Saving world" screen.
				if (minecraft.gui.screen() == null || minecraft.gui.screen() instanceof GenericMessageScreen) {
					minecraft.gui.setScreen(new TitleScreen());
				}
				state = State.IDLE;
				Runnable then = afterExit;
				afterExit = null;
				if (then != null) {
					then.run();
				}
			}
			default -> {
			}
		}
	}

	/** Leaves the world (saving it), then runs afterExit on the title screen. */
	public static void leave(Minecraft minecraft, @Nullable Runnable then) {
		afterExit = then;
		if (HARNESS) {
			state = State.AWAITING_EXIT;
		} else {
			exitNow(minecraft);
		}
	}

	public static boolean awaitingExit() {
		return state == State.AWAITING_EXIT;
	}

	// Save and Quit, as on the pause screen. afterExit runs on a later tick, once the world is gone.
	public static void exitNow(Minecraft minecraft) {
		state = State.LEAVING;
		if (minecraft.level != null) {
			minecraft.disconnectFromWorld(Component.translatable("menu.savingLevel"));
		}
	}

	private static void fail(Minecraft minecraft, String why) {
		RigTune.LOGGER.error("Benchmark world: {}", why);
		if (minecraft.level != null) {
			leave(minecraft, null);
		} else {
			state = State.FAILED;
		}
	}

	// Server thread.
	private static void setUp(IntegratedServer server) {
		try {
			GameRules rules = server.getGameRules();
			rules.set(GameRules.ADVANCE_TIME, false, server);
			rules.set(GameRules.ADVANCE_WEATHER, false, server);
			rules.set(GameRules.SPAWN_MOBS, false, server);
			rules.set(GameRules.SPAWN_MONSTERS, false, server);
			rules.set(GameRules.SPAWN_PHANTOMS, false, server);
			rules.set(GameRules.RANDOM_TICK_SPEED, 0, server);
			CommandSourceStack source = server.createCommandSourceStack().withSuppressedOutput();
			server.getCommands().performPrefixedCommand(source, "time set noon");
			server.getCommands().performPrefixedCommand(source, "weather clear");
			ServerLevel overworld = server.overworld();
			logTerrain(overworld);
			Vec3 at = new Vec3(CAMERA_X + 0.5, surface(overworld, CAMERA_X, CAMERA_Z) + CAMERA_ABOVE_SURFACE, CAMERA_Z + 0.5);
			server.getCommands().performPrefixedCommand(source,
					String.format(Locale.ROOT, "tp @a %.1f %.1f %.1f 0 0", at.x, at.y, at.z));
			target = at;
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Benchmark world: set-up failed", e);
		}
	}

	// getHeight reads the heightmap of whatever chunk is loaded, so generate the chunk first.
	private static int surface(ServerLevel level, int x, int z) {
		level.getChunk(x >> 4, z >> 4);
		return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
	}

	private static void logTerrain(ServerLevel level) {
		StringBuilder heights = new StringBuilder();
		for (int dz = -64; dz <= 64; dz += 64) {
			for (int dx = -64; dx <= 64; dx += 64) {
				heights.append(surface(level, CAMERA_X + dx, CAMERA_Z + dz)).append(' ');
			}
			heights.append("| ");
		}
		RigTune.LOGGER.info("Benchmark world: surface heights around ({}, {}) every 64 blocks, rows north to south: {}", CAMERA_X, CAMERA_Z, heights);
	}

	private static @Nullable String recordedVersion(LevelStorageSource source) {
		Path marker = source.getLevelPath(LEVEL_ID).resolve(MARKER);
		if (!Files.isRegularFile(marker)) {
			return null;
		}
		try {
			Marker m = GSON.fromJson(Files.readString(marker, StandardCharsets.UTF_8), Marker.class);
			return m == null || m.seed() != SEED ? null : m.mcVersion();
		} catch (IOException | JsonParseException e) {
			RigTune.LOGGER.warn("Benchmark world: unreadable {}", marker, e);
			return null;
		}
	}

	private static void writeMarker(LevelStorageSource source) {
		Path marker = source.getLevelPath(LEVEL_ID).resolve(MARKER);
		try {
			AtomicFiles.writeString(marker, GSON.toJson(new Marker(HardwareProbe.minecraftVersion(), SEED)));
		} catch (IOException e) {
			RigTune.LOGGER.warn("Benchmark world: could not write {}", marker, e);
		}
	}

	public static Path markerPath(Minecraft minecraft) {
		return minecraft.getLevelSource().getLevelPath(LEVEL_ID).resolve(MARKER);
	}
}
