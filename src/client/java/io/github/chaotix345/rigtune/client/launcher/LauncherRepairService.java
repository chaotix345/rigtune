package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.client.RealController;

// docs/v0.5/SPEC.md 4d, 4g (P0.4): held pending file groups (HELD_MOD_CHANGES) and the repair findings (LAUNCHER_REPAIR).
// Reached only through RealController.v05() (X4); nothing happens in the constructor. Contracts skeleton (WS-K); WS-L2
// fills it in.
public final class LauncherRepairService {
	private final RealController controller;

	public LauncherRepairService(RealController controller) {
		this.controller = controller;
	}
}
