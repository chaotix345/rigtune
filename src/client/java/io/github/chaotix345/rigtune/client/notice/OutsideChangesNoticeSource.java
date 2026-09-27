package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.awareness.OutsideChanges;
import io.github.chaotix345.rigtune.core.notice.Notice;
import org.jspecify.annotations.Nullable;

// NoticePriority.SETTINGS_CHANGED_OUTSIDE, docs/v0.5/SPEC.md 4h: settings RigTune applied were changed outside the game
// since the last clean exit (the Modrinth App's game-settings sync, a hand edit), with "Apply RigTune's values again" and
// Keep. Constructed by the lazy notice list on the first notices() call, never during startup (X4). The state is
// OutsideChanges' (the start comparison, on a worker); no file I/O here.
public final class OutsideChangesNoticeSource implements NoticeSource {
	private final RealController controller;

	public OutsideChangesNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		return OutsideChanges.notice(controller);
	}

	@Override
	public void act(String actionId) {
		OutsideChanges.act(controller, actionId);
	}
}
