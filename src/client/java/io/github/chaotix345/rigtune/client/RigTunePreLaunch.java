package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.client.undo.HistoryStartup;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

public final class RigTunePreLaunch implements PreLaunchEntrypoint {
	private static volatile @Nullable ApplyResult unseenResult;
	private static volatile int leftoverOps;
	private static volatile boolean helperBusy;
	static final Duration HELPER_WAIT = Duration.ofSeconds(5);

	@Override
	public void onPreLaunch() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path lockFile = ApplyLock.defaultPath(configDir);
		ApplyLock lock = null;
		boolean busy = false;
		try {
			lock = ApplyLock.acquire(lockFile, Duration.ZERO);
			if (lock == null) {
				// Fabric has already picked the mod jars by now, so whatever the helper still renames loads next time.
				busy = true;
				RigTune.LOGGER.warn("RigTune's apply helper from the last session is still running; waiting up to {} s", HELPER_WAIT.toSeconds());
				lock = ApplyLock.acquire(lockFile, HELPER_WAIT);
			}
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not check {}", lockFile, e);
		}
		try {
			readState(configDir, busy && lock == null);
			// The journal's legacy import and reconciliation need the lock the helper held (reentrant: review M4). The
			// journal must never stop the game from starting.
			try {
				HistoryStartup.run(configDir, ClientJournal.get(), lock != null);
			} catch (Throwable t) {
				RigTune.LOGGER.warn("Could not update RigTune's change history", t);
			}
		} finally {
			if (lock != null) {
				lock.close();
			}
		}
		if (busy) {
			helperBusy = true;
			RigTune.LOGGER.warn(lock == null
					? "RigTune's apply helper is still running; its changes take effect after the next restart"
					: "RigTune's apply helper finished while the game was starting; mod file changes take effect after the next restart");
		}
	}

	private static void readState(Path configDir, boolean stillRunning) {
		readState(configDir, stillRunning, ClientState.shared(configDir).lastShownApply);
	}

	static void readState(Path configDir, boolean stillRunning, @Nullable String lastShownApply) {
		try {
			Path last = ApplyResult.defaultPath(configDir);
			if (Files.isRegularFile(last)) {
				ApplyResult result = ApplyResult.load(last);
				if (!Objects.equals(result.finishedAt(), lastShownApply)) {
					unseenResult = result;
				}
			}
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not read the last RigTune apply result", e);
		}
		try {
			Path pending = PendingActions.defaultPath(configDir);
			if (!stillRunning && Files.isRegularFile(pending)) {
				leftoverOps = PendingActions.load(pending).ops().size();
				RigTune.LOGGER.warn("{} staged RigTune change(s) were not applied; they will be retried at the next exit", leftoverOps);
			}
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not read pending RigTune changes", e);
		}
	}

	public static @Nullable ApplyResult takeUnseenResult() {
		ApplyResult result = unseenResult;
		unseenResult = null;
		return result;
	}

	public static boolean takeHelperBusy() {
		boolean busy = helperBusy;
		helperBusy = false;
		return busy;
	}

	public static int takeLeftoverOps() {
		int count = leftoverOps;
		leftoverOps = 0;
		return count;
	}
}
