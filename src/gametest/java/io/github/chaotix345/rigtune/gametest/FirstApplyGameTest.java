package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// docs/v0.5/SPEC.md 8 (C02; AC8.1, AC8.5-AC8.9, AC8.16, AC8.18): the first-time Apply trust flow on a fresh run dir. It
// is the first entrypoint; it restores the fresh state it needs through a test seam if it isn't first. Contracts
// skeleton (WS-K): registered at its C6 place in fabric.mod.json, returns at once under rigtune.smoke, and does nothing
// yet; WS-F fills it in (its main case with RigTune's network off through GameTestNet, X1).
public class FirstApplyGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
	}
}
