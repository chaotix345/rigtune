package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// docs/v0.5/SPEC.md 5 (C20; AC5.5-AC5.7, AC5.10-AC5.12): the Stutter Doctor's one-click fixes on the real main list and
// StutterScreen. Contracts skeleton (WS-K): registered at its C6 place in fabric.mod.json, returns at once under
// rigtune.smoke, and does nothing yet; WS-S2 fills it in (its main case with RigTune's network off through GameTestNet,
// X1).
public class StutterFixGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
	}
}
