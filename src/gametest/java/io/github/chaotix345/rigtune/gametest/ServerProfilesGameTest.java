package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// docs/v0.5/SPEC.md 7 (C16; AC7.1, AC7.5-AC7.11, AC7.15): per-server profile offers against a dedicated server on a
// free port, three connections. Contracts skeleton (WS-K): registered at its C6 place in fabric.mod.json, returns at
// once under rigtune.smoke, and does nothing yet; WS-P2 fills it in (its main case with RigTune's network off through
// GameTestNet, X1).
public class ServerProfilesGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
	}
}
