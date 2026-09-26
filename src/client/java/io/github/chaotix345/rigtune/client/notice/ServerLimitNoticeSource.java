package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.SERVER_LIMIT: "The server limits view distance to N chunks" (docs/v0.4/SPEC.md 8). Skeleton from the contracts commit (never shows anything); the server-aware workstream fills it, reaching its service through the
// controller (e.g. controller.serverLimitsTracker()).
public final class ServerLimitNoticeSource implements NoticeSource {
	private final RealController controller;

	public ServerLimitNoticeSource(RealController controller) {
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
