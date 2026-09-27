package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// docs/v0.5/SPEC.md 3d (AC3d.*): a LAN guest of a dedicated server found through vanilla's LanServerPinger (no
// publishServer), and the Realms block. Contracts skeleton (WS-K): registered at its C6 place in fabric.mod.json,
// returns at once under rigtune.smoke, and does nothing yet; WS-E fills it in (its main case with RigTune's network off
// through GameTestNet, X1).
public class LanGuestGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
	}
}
