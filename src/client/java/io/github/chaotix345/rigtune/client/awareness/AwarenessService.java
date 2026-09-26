package io.github.chaotix345.rigtune.client.awareness;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.notice.NoticeCenter;

import java.nio.file.Path;
import java.util.Set;

// Change awareness (docs/v0.4/SPEC.md 9): awareness.json (hardware fingerprint, what's-new baseline, notice dismissals,
// acknowledged regressions). It is the notice slot's Dismissals store (C3: dismissals persist in awareness.json).
// Skeleton from the contracts commit (dismissals kept in memory only); the change-awareness workstream owns it.
public final class AwarenessService implements NoticeCenter.Dismissals {
	private final RealController controller;
	private final Path configDir;
	private final NoticeCenter.Dismissals dismissals = NoticeCenter.inMemory();

	public AwarenessService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	@Override
	public Set<String> dismissed() {
		return dismissals.dismissed();
	}

	@Override
	public void dismiss(String key) {
		dismissals.dismiss(key);
	}
}
