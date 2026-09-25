package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.core.benchmark.RestoreMarker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Files;
import java.nio.file.Path;

// A benchmark-restore.json found at start means the game stopped mid-benchmark: put Distant Horizons and Iris back
// once they are ready (checked each second from the title screen on, for up to 10 minutes).
public final class MarkerRestore {
	private static final int INTERVAL_TICKS = 20;
	private static final int GIVE_UP_TICKS = 20 * 600;

	private static boolean checked;
	private static boolean pending;
	private static int ticks;

	private MarkerRestore() {
	}

	public static Path file() {
		return RestoreMarker.defaultPath(FabricLoader.getInstance().getConfigDir());
	}

	// Only the marker that exists at start is restored this way; later ones belong to a running benchmark.
	static void tick(Minecraft minecraft) {
		if (!checked) {
			checked = true;
			pending = Files.isRegularFile(file());
		}
		if (!pending || !(minecraft.gui.screen() instanceof TitleScreen || minecraft.level != null)) {
			return;
		}
		if (++ticks > GIVE_UP_TICKS) {
			pending = false;
			RigTune.LOGGER.warn("Gave up restoring {} for this session; will retry at the next start", file());
			return;
		}
		if (ticks % INTERVAL_TICKS == 0) {
			settle();
		}
	}

	/** Tries the restore now; true when nothing from an earlier benchmark is left to restore. */
	public static boolean settle() {
		checked = true;
		pending = !RestoreMarker.restorePending(file(), OptionalMods.DH_RENDERING, OptionalMods.IRIS_SHADERS);
		return !pending;
	}
}
