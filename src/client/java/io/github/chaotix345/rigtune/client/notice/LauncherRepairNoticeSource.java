package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.LAUNCHER_REPAIR, docs/v0.5/SPEC.md 4g (P0.4): help the launcher catch up with an older RigTune's mod
// changes. Constructed by the lazy notice list on the first notices() call, never during startup (X4). Contracts
// skeleton (WS-K): no notice, until WS-L2 fills it in (read state computed elsewhere; no file I/O here while nothing is
// pending).
public final class LauncherRepairNoticeSource implements NoticeSource {
	private final RealController controller;

	public LauncherRepairNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return null;
	}

	@Override
	public void act(String actionId) {
	}
}
