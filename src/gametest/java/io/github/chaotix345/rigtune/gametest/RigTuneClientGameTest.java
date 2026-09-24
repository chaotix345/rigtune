package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.LinkedHashMap;
import java.util.Map;

public class RigTuneClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.hardware() != null, 600);
		context.waitTicks(130);
		context.takeScreenshot("title");

		context.clickScreenButton("menu.options");
		context.clickScreenButton("options.video");
		context.waitTicks(5);
		context.takeScreenshot("video-settings");
		context.clickScreenButton("rigtune.button");
		context.waitForScreen(RigTuneScreen.class);
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));
		context.waitForScreen(TitleScreen.class);

		context.clickScreenButton("rigtune.button");
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		context.takeScreenshot("screen-854x480-auto");

		screenshotAt(context, 1280, 720, 3, "screen-1280x720-scale3");
		screenshotAt(context, 1280, 720, 2, "screen-1280x720-scale2");
		screenshotAt(context, 640, 480, 2, "screen-640x480-scale2");
		screenshotAt(context, 854, 480, 0, "screen-854x480-restored");

		pressByKey(context, "rigtune.screen.apply.count");
		context.waitTicks(2);
		context.takeScreenshot("screen-applied");

		SettingsSnapshot snapshot = context.computeOnClient(SettingsBridge::read);
		check(snapshot.has("vanilla.renderDistance"), "snapshot has renderDistance");
		check(snapshot.has("vanilla.graphicsPreset") && !snapshot.get("vanilla.graphicsPreset").contains("\""), "graphicsPreset is unquoted");

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().pressKey(RigTuneClient.openKey());
			context.waitForScreen(RigTuneScreen.class);
			context.waitTicks(3);
			context.takeScreenshot("screen-world");

			int oldRd = context.computeOnClient(mc -> mc.options.renderDistance().get());
			int newRd = oldRd == 5 ? 6 : 5;
			int oldSim = context.computeOnClient(mc -> mc.options.simulationDistance().get());
			int newSim = oldSim == 7 ? 8 : 7;
			Map<String, String> changes = new LinkedHashMap<>();
			changes.put("vanilla.renderDistance", Integer.toString(newRd));
			changes.put("vanilla.simulationDistance", Integer.toString(newSim));
			changes.put("vanilla.graphicsPreset", "fancy");
			changes.put("vanilla.noSuchOption", "1");
			changes.put("vanilla.maxFps", "not-a-number");
			Map<String, SettingsBridge.Result> results = context.computeOnClient(mc -> SettingsBridge.applyVanilla(changes));
			check(results.get("vanilla.renderDistance").ok(), "renderDistance applied: " + results);
			check(results.get("vanilla.simulationDistance").ok(), "simulationDistance applied: " + results);
			check(results.get("vanilla.graphicsPreset").ok(), "graphicsPreset applied: " + results);
			check(!results.get("vanilla.noSuchOption").ok(), "unknown key reported: " + results);
			check(!results.get("vanilla.maxFps").ok(), "bad value reported: " + results);
			check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == newRd, "renderDistance changed");
			check(context.computeOnClient(mc -> mc.options.simulationDistance().get()) == newSim, "simulationDistance changed");
			check(context.computeOnClient(mc -> SettingsBridge.read(mc).get("vanilla.renderDistance")).equals(Integer.toString(newRd)), "snapshot reflects change");

			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.waitTicks(2);
		}
	}

	private static void screenshotAt(ClientGameTestContext context, int width, int height, int guiScale, String name) {
		context.getInput().resizeWindow(width, height);
		context.runOnClient(mc -> {
			mc.options.guiScale().set(guiScale);
			mc.resizeGui();
		});
		context.waitTicks(3);
		context.takeScreenshot(name);
	}

	private static void pressByKey(ClientGameTestContext context, String key) {
		context.runOnClient(mc -> {
			Button button = Screens.getWidgets(mc.gui.screen()).stream()
					.filter(w -> w instanceof Button && w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key))
					.map(Button.class::cast)
					.findFirst()
					.orElseThrow(() -> new AssertionError("No button " + key));
			check(button.active, key + " is active");
			button.onPress(new MouseButtonEvent(button.getX() + 1, button.getY() + 1, new MouseButtonInfo(0, 0)));
		});
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("Check failed: " + message);
		}
	}
}
