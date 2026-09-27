package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

// docs/v0.5/PLAN.md contracts items 13i and 16: what every v0.5 skeleton method in a shared game-test class gets (the
// A11yGameTest walks, AwarenessGameTest's and LauncherManagedGameTest's blocks): the test context, a StubController for
// canned screens, the game's own controller, the config dir, and X12's sizes with the resize helper.
record V05TestContext(ClientGameTestContext context, StubController stub, RigTuneController real, Path configDir) {
	// X12: {width, height, GUI scale} at which every new screen must fit, and the extra size for a list that must scroll.
	static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}};
	static final int[] SCROLLING = {854, 480, 3};

	static V05TestContext of(ClientGameTestContext context) {
		return new V05TestContext(context, new StubController(RigTuneClient::hardware), RigTuneClient.controller(),
				FabricLoader.getInstance().getConfigDir());
	}

	// The game's controller when it is RealController (the usual case outside the A11y walks' canned screens).
	RealController realController() {
		if (real instanceof RealController controller) {
			return controller;
		}
		throw new AssertionError("this block needs the real controller, not " + real.getClass().getSimpleName());
	}

	// The window size and GUI scale (0 = auto), as A11yGameTest.resize does.
	void resize(int width, int height, int guiScale) {
		context.getInput().resizeWindow(width, height);
		context.getInput().setCursorPos(1, 1);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		context.waitTicks(3);
	}
}
