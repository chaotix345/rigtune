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
import net.minecraft.world.level.storage.LevelResource;
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
// It is recreated when missing or made by another Minecraft version, since the terrain for a seed differs between
// versions. Nothing here touches a world unless it is provably this save (its folder and level name).
public final class BenchmarkWorld {
	public static final String LEVEL_ID = "rigtune-benchmark";
	public static final String LEVEL_NAME = "RigTune Benchmark";
	public static final long SEED = 8675309L;
	public static final int CAMERA_X = 0;
	public static final int CAMERA_Z = 192;
	private static final int CAMERA_ABOVE_SURFACE = 10;
	private static final String MARKER = "rigtune-benchmark.json";
	private static final int TIMEOUT_TICKS = 20 * 90;
	private static final int FAILED_OPEN_GRACE_TICKS = 20;
	private static final Gson GSON = new Gson();
	// In a client game test, leaving a world from inside a client tick deadlocks the harness's tick phaser (the render
	// thread waits in IntegratedServer.halt for the server thread, which waits in the phaser for the render thread).
	// There the test leaves from its own thread with exitNow(), the way the harness closes its worlds.
	private static final boolean HARNESS = System.getProperty("fabric.client.gametest") != null;

	public enum State { IDLE, OPENING, SETTING_UP, READY, AWAITING_EXIT, LEAVING, FAILED }

	// What to do with the saves folder before opening.
	enum FolderAction { OPEN, CREATE, RECREATE, MOVE_ASIDE_AND_CREATE }

	private record Marker(String mcVersion, long seed) {
	}

	private static State state = State.IDLE;
	private static boolean created;
	private static int ticks;
	private static @Nullable Screen openedFrom;
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

	/** Where the camera is held; null until the world is set up. */
	public static @Nullable Vec3 cameraPosition() {
		return target;
	}

	public static boolean busy() {
		return state == State.OPENING || state == State.SETTING_UP || state == State.READY || state == State.AWAITING_EXIT
				|| state == State.LEAVING;
	}

	// A save RigTune made (its marker file is inside) is replaced when it is for another MC version or seed; a folder
	// with that name but without the marker may be the player's, so it is kept under another name.
	static FolderAction folderAction(boolean exists, boolean hasMarker, @Nullable String recordedMcVersion, String runningMcVersion) {
		if (!exists) {
			return FolderAction.CREATE;
		}
		if (!hasMarker) {
			return FolderAction.MOVE_ASIDE_AND_CREATE;
		}
		return recordedMcVersion != null && recordedMcVersion.equals(runningMcVersion) ? FolderAction.OPEN : FolderAction.RECREATE;
	}

	static boolean isBenchmarkSave(@Nullable String folderName, @Nullable String levelName) {
		return LEVEL_ID.equals(folderName) && LEVEL_NAME.equals(levelName);
	}

	/** True when the loaded singleplayer world is the benchmark save. */
	public static boolean inBenchmarkWorld(Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.level == null) {
			return false;
		}
		Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
		return isBenchmarkSave(folder == null ? null : folder.toString(), server.getWorldData().getLevelName());
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
		openedFrom = parent;
		try {
			Path folder = source.getLevelPath(LEVEL_ID);
			boolean hasMarker = Files.isRegularFile(folder.resolve(MARKER));
			FolderAction action = folderAction(source.levelExists(LEVEL_ID), hasMarker, hasMarker ? recordedVersion(folder) : null, mcVersion);
			switch (action) {
				case OPEN -> {
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
				case RECREATE -> {
					RigTune.LOGGER.info("Benchmark world: recreating {} for Minecraft {}", LEVEL_ID, mcVersion);
					try (LevelStorageSource.LevelStorageAccess access = source.createAccess(LEVEL_ID)) {
						access.deleteLevel();
					}
				}
				case MOVE_ASIDE_AND_CREATE -> {
					Path aside = folder.resolveSibling(LEVEL_ID + "-old-" + System.currentTimeMillis());
					RigTune.LOGGER.warn("Benchmark world: {} has no RigTune marker; moving it to {}", folder, aside.getFileName());
					Files.move(folder, aside);
				}
				case CREATE -> {
				}
			}
			created = true;
			state = State.OPENING;
			LevelSettings settings = new LevelSettings(LEVEL_NAME, GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
			minecraft.createWorldOpenFlows().createFreshLevel(LEVEL_ID, settings, new WorldOptions(SEED, true, false),
					WorldPresets::createNormalWorldDimensions, parent);
			// Mark the folder as RigTune's at once, so a creation that fails halfway is replaced, not moved aside, next time.
			if (Files.isDirectory(folder)) {
				writeMarker(folder);
			}
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
				ticks++;
				IntegratedServer server = minecraft.getSingleplayerServer();
				if (minecraft.level == null || server == null || !server.isReady()) {
					// WorldOpenFlows can give up without a callback: it goes back to the screen it was opened from.
					boolean backOnMenu = minecraft.level == null && server == null && minecraft.gui.screen() == openedFrom;
					if (ticks > TIMEOUT_TICKS || ticks > FAILED_OPEN_GRACE_TICKS && backOnMenu) {
						RigTune.LOGGER.error("Benchmark world: {} did not load", LEVEL_ID);
						state = State.FAILED;
					}
					return;
				}
				if (!inBenchmarkWorld(minecraft)) {
					RigTune.LOGGER.warn("Benchmark world: another world was loaded; leaving it alone");
					state = State.FAILED;
					return;
				}
				if (minecraft.player == null) {
					return;
				}
				if (created) {
					writeMarker(minecraft.getLevelSource().getLevelPath(LEVEL_ID));
				}
				state = State.SETTING_UP;
				ticks = 0;
				server.execute(() -> setUp(server));
			}
			case SETTING_UP -> {
				if (!inBenchmarkWorld(minecraft)) {
					state = State.FAILED;
					return;
				}
				if (++ticks > TIMEOUT_TICKS) {
					RigTune.LOGGER.error("Benchmark world: the camera position was not reached");
					leave(minecraft, null);
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
				if (!inBenchmarkWorld(minecraft)) {
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

	/** Leaves the benchmark world (saving it), then runs `then` on the title screen. Any other world is left alone. */
	public static void leave(Minecraft minecraft, @Nullable Runnable then) {
		if (!inBenchmarkWorld(minecraft)) {
			state = State.IDLE;
			if (then != null && minecraft.level == null) {
				then.run();
			}
			return;
		}
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
			// getHeight reads the heightmap of whatever chunk is loaded, so generate the chunk first.
			overworld.getChunk(CAMERA_X >> 4, CAMERA_Z >> 4);
			int surface = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, CAMERA_X, CAMERA_Z);
			Vec3 at = new Vec3(CAMERA_X + 0.5, surface + CAMERA_ABOVE_SURFACE, CAMERA_Z + 0.5);
			server.getCommands().performPrefixedCommand(source, String.format(Locale.ROOT, "tp @a %.1f %.1f %.1f 0 0", at.x, at.y, at.z));
			target = at;
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Benchmark world: set-up failed", e);
		}
	}

	private static @Nullable String recordedVersion(Path folder) {
		Path marker = folder.resolve(MARKER);
		try {
			Marker m = GSON.fromJson(Files.readString(marker, StandardCharsets.UTF_8), Marker.class);
			return m == null || m.seed() != SEED ? null : m.mcVersion();
		} catch (IOException | JsonParseException e) {
			RigTune.LOGGER.warn("Benchmark world: unreadable {}", marker, e);
			return null;
		}
	}

	private static void writeMarker(Path folder) {
		Path marker = folder.resolve(MARKER);
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
