package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.BATTERY_OFFER: "Switch to Battery" on an AC -> battery edge (docs/v0.4/SPEC.md 4). Skeleton from the contracts commit (never shows anything); WS-P fills it, reaching its service through the
// controller (e.g. controller.profileService()).
public final class BatteryNoticeSource implements NoticeSource {
	private final RealController controller;

	public BatteryNoticeSource(RealController controller) {
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
