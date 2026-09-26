package io.github.chaotix345.rigtune.client.awareness;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.notice.NoticeCenter;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;

import java.nio.file.Path;
import java.util.Set;

// Change awareness (docs/v0.4/SPEC.md 9): the hardware fingerprint, the what's-new baseline and the notice dismissals,
// all in awareness.json through the one process-wide AwarenessStore. It is the notice slot's Dismissals store (C3).
// Skeleton from the contracts commit (only the dismissals); the change-awareness workstream owns it.
public final class AwarenessService implements NoticeCenter.Dismissals {
	private final RealController controller;
	private final Path configDir;
	private final AwarenessStore store;

	public AwarenessService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
		this.store = AwarenessStore.shared(configDir);
	}

	@Override
	public Set<String> dismissed() {
		return store.dismissed();
	}

	@Override
	public void dismiss(String key) {
		store.dismiss(key);
	}
}
