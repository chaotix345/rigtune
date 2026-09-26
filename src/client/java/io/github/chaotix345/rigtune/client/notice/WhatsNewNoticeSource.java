package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.WHATS_NEW: new recommendations since the last look (docs/v0.4/SPEC.md 9); it stays until dismissed. The
// state lives in AwarenessService.
public final class WhatsNewNoticeSource implements NoticeSource {
	private final RealController controller;

	public WhatsNewNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return controller.awarenessService().whatsNewNotice();
	}

	@Override
	public void act(String actionId) {
	}
}
