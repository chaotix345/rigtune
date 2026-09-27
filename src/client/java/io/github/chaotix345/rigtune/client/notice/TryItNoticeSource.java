package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.TRY_IT, docs/v0.5/SPEC.md 6 (C09): the open try's next step. Constructed by the lazy notice list on
// the first notices() call, never during startup (X4). Contracts skeleton (WS-K): no notice, until WS-T fills it in
// (read state computed elsewhere; no file I/O here while nothing is pending).
public final class TryItNoticeSource implements NoticeSource {
	private final RealController controller;

	public TryItNoticeSource(RealController controller) {
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
