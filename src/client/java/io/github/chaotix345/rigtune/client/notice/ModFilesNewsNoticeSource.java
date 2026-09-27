package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.MOD_FILES_NEWS, docs/v0.5/SPEC.md 4b (P0.4): what changed for players who used 0.4. Constructed by the
// lazy notice list on the first notices() call, never during startup (X4). Contracts skeleton (WS-K): no notice, until
// WS-L1 fills it in (read state computed elsewhere; no file I/O here while nothing is pending).
public final class ModFilesNewsNoticeSource implements NoticeSource {
	private final RealController controller;

	public ModFilesNewsNoticeSource(RealController controller) {
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
