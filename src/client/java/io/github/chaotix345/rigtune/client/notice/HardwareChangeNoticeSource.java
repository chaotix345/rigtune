package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.HARDWARE_CHANGED: GPU, driver or hardware changed since last time (docs/v0.4/SPEC.md 9). Skeleton from the contracts commit (never shows anything); the change-awareness workstream fills it, reaching its service through the
// controller (e.g. controller.awarenessService()).
public final class HardwareChangeNoticeSource implements NoticeSource {
	private final RealController controller;

	public HardwareChangeNoticeSource(RealController controller) {
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
