package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class RigTunePreLaunch implements PreLaunchEntrypoint {
	private static volatile @Nullable ApplyResult unseenResult;
	private static volatile int leftoverOps;

	@Override
	public void onPreLaunch() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		try {
			Path last = ApplyResult.defaultPath(configDir);
			if (Files.isRegularFile(last)) {
				ApplyResult result = ApplyResult.load(last);
				if (!Objects.equals(result.finishedAt(), ClientState.shared(configDir).lastShownApply)) {
					unseenResult = result;
				}
			}
		} catch (Exception e) {
			RigTune.LOGGER.warn("Could not read the last RigTune apply result", e);
		}
		try {
			Path pending = PendingActions.defaultPath(configDir);
			if (Files.isRegularFile(pending)) {
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

	public static int takeLeftoverOps() {
		int count = leftoverOps;
		leftoverOps = 0;
		return count;
	}
}
