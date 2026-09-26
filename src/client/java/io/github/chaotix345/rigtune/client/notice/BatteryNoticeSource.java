package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.BATTERY_OFFER (docs/v0.4/SPEC.md 4): "Switch to Battery" after an AC -> battery edge, "Switch back" after
// the reverse edge while Battery is active; ProfileService decides (BatteryPrompt) and never switches by itself.
public final class BatteryNoticeSource implements NoticeSource {
	private final RealController controller;

	public BatteryNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return controller.profileService().batteryNotice();
	}

	@Override
	public void act(String actionId) {
		controller.profileService().batteryAction(actionId);
	}
}
