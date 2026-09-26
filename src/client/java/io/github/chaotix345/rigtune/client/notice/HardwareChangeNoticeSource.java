package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.HARDWARE_CHANGED: GPU, driver or hardware changed since last time, with Re-scan and Re-benchmark
// (docs/v0.4/SPEC.md 9). The state lives in AwarenessService.
public final class HardwareChangeNoticeSource implements NoticeSource {
	private final RealController controller;

	public HardwareChangeNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return controller.awarenessService().hardwareNotice();
	}

	@Override
	public void act(String actionId) {
		controller.awarenessService().hardwareAction(actionId);
	}
}
