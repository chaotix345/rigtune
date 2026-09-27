package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// docs/v0.5/SPEC.md 6 (C09; AC6.1-AC6.4, AC6.6-AC6.9, AC6.13-AC6.15): Measured Try It, blocks 1-6. Contracts skeleton
// (WS-K): registered at its C6 place in fabric.mod.json, returns at once under rigtune.smoke, and does nothing yet;
// WS-T fills it in (its main case with RigTune's network off through GameTestNet, X1).
public class TryItGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
	}
}
