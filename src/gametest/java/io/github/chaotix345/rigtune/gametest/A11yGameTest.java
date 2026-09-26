package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

// docs/v0.4/SPEC.md 11 (AC11.1). Registered by the contracts commit with one trivial case (the title screen is reachable) so CI proves the
// registration; the accessibility workstream extends it.
public class A11yGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
	}
}
