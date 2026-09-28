package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.HELD_MOD_CHANGES, docs/v0.5/SPEC.md 4d (P0.4): mod changes from an earlier Apply held in a launcher-
// managed instance. Constructed by the lazy notice list on the first notices() call, never during startup (X4). The state
// lives in LauncherRepairService (read on a worker).
public final class HeldModChangesNoticeSource implements NoticeSource {
	private final RealController controller;

	public HeldModChangesNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return controller.v05().launcherRepair().heldNotice();
	}

	@Override
	public void act(String actionId) {
		controller.v05().launcherRepair().heldAction(actionId);
	}
}
