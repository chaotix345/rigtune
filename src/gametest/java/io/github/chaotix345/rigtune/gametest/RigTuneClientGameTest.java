package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.client.ClientState;
import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.RigTunePreLaunch;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.client.ui.BenchmarkResultScreen;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RigTuneClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		context.waitForScreen(TitleScreen.class);
		context.waitFor(mc -> RigTuneClient.hardware() != null && RigTuneClient.controller().report() != null, 1200);
		context.waitTicks(30);
		context.takeScreenshot("title-toasts");
		context.waitTicks(150);
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
		context.takeScreenshot("real-854x480");
		screenshotAt(context, 1280, 720, 3, "real-1280x720-scale3");
		screenshotAt(context, 1280, 720, 2, "real-1280x720-scale2");
		context.getInput().setCursorPos(640, 360);
		context.getInput().scroll(-200);
		context.waitTicks(3);
		context.takeScreenshot("real-1280x720-scale2-end");
		screenshotAt(context, 854, 480, 0, "real-854x480-restored");
		context.runOnClient(mc -> mc.gui.setScreen(new TitleScreen()));

		RigTuneController real = RigTuneClient.controller();
		StubController stub = new StubController(RigTuneClient::hardware);
		context.runOnClient(mc -> {
			RigTuneClient.setController(stub);
			RigTuneClient.open(mc.gui.screen());
		});
		context.waitForScreen(RigTuneScreen.class);
		context.waitTicks(3);
		context.takeScreenshot("stub-854x480");
		screenshotAt(context, 1280, 720, 3, "stub-1280x720-scale3");
		screenshotAt(context, 1280, 720, 2, "stub-1280x720-scale2");
		screenshotAt(context, 640, 480, 2, "stub-640x480-scale2");
		screenshotAt(context, 854, 480, 0, "stub-854x480-restored");
		pressByKey(context, "rigtune.screen.apply.count");
		context.waitTicks(2);
		context.takeScreenshot("stub-applied");
		context.runOnClient(mc -> {
			RigTuneClient.setController(real);
			mc.gui.setScreen(new TitleScreen());
		});

		SettingsSnapshot snapshot = context.computeOnClient(SettingsBridge::read);
		check(snapshot.has("vanilla.renderDistance"), "snapshot has renderDistance");
		check(snapshot.has("vanilla.graphicsPreset") && !snapshot.get("vanilla.graphicsPreset").contains("\""), "graphicsPreset is unquoted");

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getConnection().waitForChunksRender();
			context.getInput().pressKey(RigTuneClient.openKey());
			context.waitForScreen(RigTuneScreen.class);
			context.waitTicks(3);
			context.takeScreenshot("real-world");

			checkSettingsBridge(context);
			checkControllerApply(context);

			context.runOnClient(mc -> mc.gui.setScreen(null));
			context.waitTicks(5);
			int rdBefore = context.computeOnClient(mc -> mc.options.renderDistance().get());
			boolean hudBefore = context.computeOnClient(mc -> mc.gui.hud.isHidden());
			float yawBefore = context.computeOnClient(mc -> mc.player.getYRot());
			check(context.computeOnClient(mc -> BenchmarkController.start(mc, new BenchmarkController.Config(2, 1.5, 1.0, 20.0))), "benchmark started");
			context.waitTicks(30);
			context.takeScreenshot("benchmark-running");
			context.waitFor(mc -> !BenchmarkController.running(), 1400);
			BenchmarkController.Outcome outcome = context.computeOnClient(mc -> BenchmarkController.lastOutcome());
			check(outcome != null && !outcome.cancelled(), "benchmark finished: " + outcome);
			check(!outcome.result().measurements().isEmpty(), "benchmark measured something: " + outcome);
			check(outcome.result().measurements().stream().allMatch(m -> m.stats().frames() > 0), "frames recorded: " + outcome);
			check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == rdBefore, "render distance restored");
			check(context.computeOnClient(mc -> mc.gui.hud.isHidden()) == hudBefore, "HUD visibility restored");
			check(Math.abs(context.computeOnClient(mc -> mc.player.getYRot()) - yawBefore) < 0.01f, "camera rotation restored");
			context.waitForScreen(BenchmarkResultScreen.class);
			context.waitTicks(3);
			context.takeScreenshot("benchmark-result");
			pressByKey(context, "rigtune.benchmark.keep");
			context.waitTicks(2);
			check(context.computeOnClient(mc -> mc.options.renderDistance().get()) == rdBefore, "keep leaves render distance alone");

			checkNotices(context);
		}
	}

	// runClientGameTest starts from a fresh run directory, so the previous-exit files are faked here.
	private static void checkNotices(ClientGameTestContext context) {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		ApplyResult fake = new ApplyResult(Instant.now().toString(), List.of(
				new ApplyResult.OpResult(PendingActions.Op.disableFile(Path.of("mods", "indium.jar")), ApplyResult.Status.OK, "Disabled"),
				new ApplyResult.OpResult(PendingActions.Op.enableFile(Path.of("mods", "a.jar.rigtune-pending"), Path.of("mods", "a.jar")),
						ApplyResult.Status.FAILED, "File in use")));
		try {
			fake.save(ApplyResult.defaultPath(configDir));
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		new RigTunePreLaunch().onPreLaunch();
		context.runOnClient(RigTuneClient::showNotices);
		context.waitTicks(15);
		context.takeScreenshot("notices");
		check(fake.finishedAt().equals(ClientState.load(configDir).lastShownApply), "apply result marked as shown");
	}

	private static void checkSettingsBridge(ClientGameTestContext context) {
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
	}

	private static void checkControllerApply(ClientGameTestContext context) {
		boolean shadows = context.computeOnClient(mc -> mc.options.entityShadows().get());
		List<Recommendation> recs = List.of(
				new Recommendation("set:vanilla.entityShadows", Category.SETTING, Impact.LOW, "Entity shadows", "test",
						new Action.SetSetting("vanilla.entityShadows", Boolean.toString(shadows), Boolean.toString(!shadows)), true),
				new Recommendation("set:sodium.performance.use_fog_occlusion", Category.SETTING, Impact.LOW, "Fog occlusion", "test",
						new Action.SetSetting("sodium.performance.use_fog_occlusion", "true", "false"), true));
		Component message = context.computeOnClient(mc -> RigTuneClient.controller().apply(recs));
		check(context.computeOnClient(mc -> mc.options.entityShadows().get()) == !shadows, "vanilla setting applied immediately");
		check(message.getString().contains("Restart"), "restart message: " + message.getString());
		Path pendingFile = PendingActions.defaultPath(FabricLoader.getInstance().getConfigDir());
		try {
			PendingActions plan = PendingActions.load(pendingFile);
			check(plan.ops().stream().anyMatch(op -> op.type() == PendingActions.Type.PATCH_JSON
					&& op.path().endsWith("sodium-options.json")
					&& "false".equals(op.patches().get("performance.use_fog_occlusion"))), "sodium patch staged: " + plan);
		} catch (IOException e) {
			throw new AssertionError("pending.json missing", e);
		}
		context.runOnClient(mc -> mc.options.entityShadows().set(shadows));
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
