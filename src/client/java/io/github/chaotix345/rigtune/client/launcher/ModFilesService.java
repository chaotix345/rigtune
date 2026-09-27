package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;

// docs/v0.5/SPEC.md 4a-4b, 4e (P0.4): the instance's mod-files policy and the MOD_FILES_NEWS notice. Reached only through
// RealController.v05() (X4); nothing happens in the constructor. Contracts skeleton (WS-K): RIGTUNE, 0.4's behaviour,
// until WS-L1 fills it in (the policy cache from LauncherProbe's evidence, the news notice).
public final class ModFilesService {
	private final RealController controller;

	public ModFilesService(RealController controller) {
		this.controller = controller;
	}

	// Render thread, no I/O (screens and RealController.modFiles()).
	public ModFilesPolicy policy() {
		return ModFilesPolicy.RIGTUNE;
	}
}
