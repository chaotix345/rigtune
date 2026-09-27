package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.FIRST_RUN, docs/v0.5/SPEC.md 8 (C02): the first-run guide. Constructed by the lazy notice list on the
// first notices() call, never during startup (X4). Contracts skeleton (WS-K): no notice, until WS-F fills it in (read
// state computed elsewhere; no file I/O here while nothing is pending).
public final class FirstRunNoticeSource implements NoticeSource {
	private final RealController controller;

	public FirstRunNoticeSource(RealController controller) {
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
