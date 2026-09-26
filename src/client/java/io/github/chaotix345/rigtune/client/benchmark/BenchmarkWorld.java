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
import net.minecraft.core.BlockPos;
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
// It is recreated when missing or made by another Minecraft version: the terrain noise for this seed is the same on 26.2
// and 26.3, but features such as trees are placed differently (docs/research/v0.3/benchmark.md §2.5). The decisions are WorldFlow's (unit-tested): nothing here touches, or leaves, a world that isn't provably
// this save (singleplayer, save folder rigtune-benchmark, level name RigTune Benchmark).
public final class BenchmarkWorld {
	public static final String LEVEL_ID = "rigtune-benchmark";
	public static final String LEVEL_NAME = "RigTune Benchmark";
	public static final long SEED = 8675309L;
	public static final int CAMERA_X = 0;
	public static final int CAMERA_Z = 192;
	public static final int CAMERA_ABOVE_FLOOR = 16;
	private static final int CAMERA_ABOVE_SURFACE = 10;
	private static final String MARKER = "rigtune-benchmark.json";
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

	private static void setState(State next, String why) {
		if (next == state) {
			return;
		}
		String line = "Benchmark world: " + state + " -> " + next + " (" + why + ")";
		if (DevAutorun.enabled()) {
			RigTune.LOGGER.info(line);
		} else {
			RigTune.LOGGER.debug(line);
		}
		state = next;
		ticks = 0;
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

	// docs/v0.3/SPEC.md 8c: the camera is 16 above the terrain floor from the noise (117 + 16 = 133 on 26.2 and 26.3),
	// so the spot no longer depends on a tree growing on the camera column (one did on 26.3 only). When the camera block
	// or the one above isn't air (a very tall tree on a future version), it goes 10 above the highest motion-blocking
	// block instead (the v0.2 rule), never lower.
	static int cameraY(int floor, boolean clear, int surface) {
		int y = floor + CAMERA_ABOVE_FLOOR;
		return clear ? y : Math.max(y, surface + CAMERA_ABOVE_SURFACE);
	}

	static boolean isBenchmarkSave(@Nullable String folderName, @Nullable String levelName) {
		return LEVEL_ID.equals(folderName) && LEVEL_NAME.equals(levelName);
	}

	/** True when the loaded world is the singleplayer benchmark save (by its save folder, the level id, and level name). */
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
		afterExit = null;
		openedFrom = parent;
		try {
			Path folder = source.getLevelPath(LEVEL_ID);
			boolean hasMarker = Files.isRegularFile(folder.resolve(MARKER));
			FolderAction action = folderAction(source.levelExists(LEVEL_ID), hasMarker, hasMarker ? recordedVersion(folder) : null, mcVersion);
			switch (action) {
				case OPEN -> {
					created = false;
					setState(State.OPENING, "opening the existing save");
					minecraft.createWorldOpenFlows().openWorld(LEVEL_ID, () -> {
						setState(State.FAILED, "opening was cancelled");
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
			setState(State.OPENING, "creating the save");
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
			setState(State.FAILED, "could not open: " + e);
			return false;
		}
	}

	private static WorldFlow.Observation observe(Minecraft minecraft) {
		IntegratedServer server = minecraft.getSingleplayerServer();
		boolean worldLoaded = minecraft.level != null;
		boolean singleplayerReady = worldLoaded && server != null && server.isReady() && minecraft.player != null;
		boolean benchmarkSave = singleplayerReady && inBenchmarkWorld(minecraft);
		boolean backOnMenu = !worldLoaded && server == null && minecraft.gui.screen() == openedFrom;
		Vec3 at = target;
		LocalPlayer player = minecraft.player;
		boolean atCamera = at != null && player != null && minecraft.gui.screen() == null && player.position().distanceTo(at) < 1.0;
		return new WorldFlow.Observation(worldLoaded, worldLoaded && server == null, server != null, singleplayerReady, benchmarkSave,
				backOnMenu, atCamera);
	}

	public static void tick(Minecraft minecraft) {
		if (state == State.IDLE) {
			return;
		}
		ticks++;
		WorldFlow.Observation seen = observe(minecraft);
		WorldFlow.Step step = WorldFlow.next(state, ticks, seen);
		setState(step.state(), step.action() + " " + seen);
		switch (step.action()) {
			case SET_UP -> {
				if (created) {
					writeMarker(minecraft.getLevelSource().getLevelPath(LEVEL_ID));
				}
				IntegratedServer server = minecraft.getSingleplayerServer();
				server.execute(() -> setUp(server));
			}
			case READY -> {
				minecraft.gui.toastManager().clear();
				RigTune.LOGGER.info("Benchmark world: ready at {}", target);
			}
			case LEAVE -> leave(minecraft, null);
			case FINISH_EXIT -> {
				// A disconnect deferred by the game-test harness ends on its "Saving world" screen.
				if (minecraft.gui.screen() == null || minecraft.gui.screen() instanceof GenericMessageScreen) {
					minecraft.gui.setScreen(new TitleScreen());
				}
				Runnable then = afterExit;
				afterExit = null;
				if (then != null) {
					then.run();
				}
			}
			case NONE -> {
			}
		}
		// Creative flight, client side only (as the benchmark does), so the player stays at the camera position.
		if (state == State.SETTING_UP && seen.benchmarkSave() && minecraft.player != null) {
			minecraft.player.getAbilities().flying = true;
		}
	}

	/** Leaves the benchmark world (saving it), then runs `then` on the title screen. Any other world is left alone. */
	public static void leave(Minecraft minecraft, @Nullable Runnable then) {
		if (!inBenchmarkWorld(minecraft)) {
			setState(State.IDLE, "not in the benchmark world; nothing to leave");
			if (then != null && minecraft.level == null) {
				then.run();
			}
			return;
		}
		afterExit = then;
		if (HARNESS) {
			setState(State.AWAITING_EXIT, "the game test leaves from its thread");
		} else {
			exitNow(minecraft);
		}
	}

	public static boolean awaitingExit() {
		return state == State.AWAITING_EXIT;
	}

	// Save and Quit, as on the pause screen, and only for the benchmark world. afterExit runs on a later tick, once the
	// world is gone.
	public static void exitNow(Minecraft minecraft) {
		if (!inBenchmarkWorld(minecraft)) {
			setState(State.IDLE, "not in the benchmark world; nothing to leave");
			return;
		}
		setState(State.LEAVING, "save and quit");
		minecraft.disconnectFromWorld(Component.translatable("menu.savingLevel"));
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
			// The blocks and heightmap below are those of whatever is generated, and trees from the neighbouring chunks are
			// only placed once those are generated too, so generate the 3x3 chunks around the camera first.
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					overworld.getChunk((CAMERA_X >> 4) + dx, (CAMERA_Z >> 4) + dz);
				}
			}
			int floor = terrainFloor(overworld, CAMERA_X, CAMERA_Z);
			BlockPos spot = new BlockPos(CAMERA_X, floor + CAMERA_ABOVE_FLOOR, CAMERA_Z);
			boolean clear = overworld.getBlockState(spot).isAir() && overworld.getBlockState(spot.above()).isAir();
			int surface = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, CAMERA_X, CAMERA_Z);
			int y = cameraY(floor, clear, surface);
			if (!clear) {
				RigTune.LOGGER.warn("Benchmark world: the camera spot {} isn't clear; using y {} instead", spot, y);
			}
			Vec3 at = new Vec3(CAMERA_X + 0.5, y, CAMERA_Z + 0.5);
			server.getCommands().performPrefixedCommand(source, String.format(Locale.ROOT, "tp @a %.1f %.1f %.1f 0 0", at.x, at.y, at.z));
			target = at;
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Benchmark world: set-up failed", e);
		}
	}

	/** The terrain height at a column from the world's noise (no trees, no chunk needed). Server thread. */
	public static int terrainFloor(ServerLevel level, int x, int z) {
		// 26.4 (from snapshot 1) renamed ChunkGenerator.getBaseHeight to getFirstFreeHeight (same parameters). ">=26.4-alpha",
		// not ">=26.4-snapshot-1": Stonecutter sorts 26.4-pre-N and 26.4-rc-N below 26.4-snapshot-1 (text order).
		//? if >=26.4-alpha {
		/*return level.getChunkSource().getGenerator().getFirstFreeHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
		*///?} else
		return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
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
