package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

// PROOF (ws-ci, scratch branch; SPEC AC1e.3): a game test that never returns. build.yml must dump every thread at 13 min
// (this frame named) and fail the leg by 15.
public class HangProbeGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		sleepsForever();
	}

	private static void sleepsForever() {
		while (true) {
			try {
				Thread.sleep(60_000);
			} catch (InterruptedException ignored) {
			}
		}
	}
}
