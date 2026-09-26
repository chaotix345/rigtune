package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.ui.BenchmarkHistoryScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.ui.ToolsScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;

// docs/v0.4/SPEC.md 7 (AC7.5). Registered by the contracts commit with one trivial case (RigTune -> Tools -> this screen -> Done) so CI proves
// the registration; WS-B (benchmark history) extends it.
public class BenchmarkHistoryGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (Boolean.getBoolean("rigtune.smoke")) {
			return;
		}
		context.waitForScreen(TitleScreen.class);
		context.runOnClient(mc -> mc.gui.setScreen(new ToolsScreen(new RigTuneScreen(new TitleScreen(), RigTuneClient.controller()),
				RigTuneClient.controller())));
		context.waitForScreen(ToolsScreen.class);
		context.runOnClient(mc -> ((ToolsScreen) mc.gui.screen()).openBenchmarkHistory());
		context.waitForScreen(BenchmarkHistoryScreen.class);
		context.waitTicks(2);
		context.runOnClient(mc -> mc.gui.screen().onClose());
		context.waitForScreen(ToolsScreen.class);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);
		RigTune.LOGGER.info("BenchmarkHistoryGameTest: registered; the contracts skeleton case passed");
	}
}
