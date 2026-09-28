package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.SERVER_PROFILE, docs/v0.5/SPEC.md 7 (C16): "You set %s for this server. Switch to it?" with Switch and
// Don't offer here. Constructed by the lazy notice list on the first notices() call, never during startup (X4);
// ServerProfileService decides, and reads nothing while no offer is pending.
public final class ServerProfileNoticeSource implements NoticeSource {
	private final RealController controller;

	public ServerProfileNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return controller.v05().serverProfiles().notice();
	}

	@Override
	public void act(String actionId) {
		controller.v05().serverProfiles().act(actionId);
	}
}
