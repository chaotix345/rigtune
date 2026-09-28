package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.launcher.ModFilesService;
import io.github.chaotix345.rigtune.client.ui.RigTuneSettingsScreen;
import io.github.chaotix345.rigtune.core.notice.Notice;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

// NoticePriority.MOD_FILES_NEWS, docs/v0.5/SPEC.md 4b (P0.4): what changed for players who used RigTune before (FirstRun
// RETURNING at load, review-11 FEAT-1: an Apply makes a new player RETURNING, not a 0.4 upgrader), under LAUNCHER: "RigTune now leaves this instance's mod files to <launcher>", once per instance (dismissed
// by its key in awareness.json); Settings… opens the settings on the Mod files row. Constructed by the lazy notice
// list on the first notices() call, never during startup (X4); reads memory only (ModFilesService.news).
public final class ModFilesNewsNoticeSource implements NoticeSource {
	private final RealController controller;

	public ModFilesNewsNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return controller.v05().modFiles().news(controller.v05().firstRun().loadedStatus());
	}

	@Override
	public void act(String actionId) {
		Minecraft minecraft = controller.minecraft();
		if (ModFilesService.NEWS_SETTINGS.equals(actionId) && minecraft != null) {
			minecraft.gui.setScreen(new RigTuneSettingsScreen(minecraft.gui.screen(), controller).showingModFiles());
		}
	}
}
