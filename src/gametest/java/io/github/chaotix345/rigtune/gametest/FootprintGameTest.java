package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

// docs/v0.4/SPEC.md 10 and 13 (AC10.4, AC13.2). Registered by the contracts commit with one trivial case (the title screen is reachable) so CI proves the
// registration; the footprint workstream extends it.
public class FootprintGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		RigTune.LOGGER.info("FootprintGameTest: registered; the contracts skeleton case passed");
	}
}
