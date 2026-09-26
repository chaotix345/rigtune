package io.github.chaotix345.rigtune.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.PowerWatcher;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.client.undo.ClientJournal;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.HelperLauncher;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.ChangeRecorder;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class RigTuneClient implements ClientModInitializer {
	public static final String SODIUM_VIDEO_SCREEN = "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();
	private static final int BUTTON_WIDTH = 60;

	private static final SystemToast.SystemToastId NOTICE_ID = new SystemToast.SystemToastId(8000L);
	private static final SystemToast.SystemToastId PRIVACY_ID = new SystemToast.SystemToastId(10000L);
	private static final StartupNotices.PrivacyToast PRIVACY_TOAST = new StartupNotices.PrivacyToast();
	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(RigTune.MOD_ID, "benchmark");

	private static RigTuneController controller;
	private static volatile @Nullable HardwareProfile hardware;
	private static KeyMapping openKey;
	private static boolean titleSeen;
	private static boolean toastShown;
	private static boolean noticesShown;

	@Override
	public void onInitializeClient() {
		ChangeRecorder.install(ClientJournal.get());
		RealController real = new RealController();
		controller = real;
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(RigTune.MOD_ID, "rigtune"));
		//? if >=26.3 {
		/*InputConstants.Type keyboard = InputConstants.Type.KEYBOARD;
		*///?} else
		InputConstants.Type keyboard = InputConstants.Type.KEYSYM;
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rigtune.open", keyboard, InputConstants.KEY_F8, category));

		ClientLifecycleEvents.CLIENT_STARTED.register(real::start);
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> {
			BenchmarkController.cancel();
			real.unstageQueuedUpdates();
			launchHelperIfPending();
		});
		powerWatcher(real);
		ClientTickEvents.END_CLIENT_TICK.register(RigTuneClient::onTick);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> addEntryButton(screen, width, height));
		// The sleep overlay is the one vanilla HUD layer drawn while the GUI is hidden, which the benchmark does.
		HudElementRegistry.attachElementAfter(VanillaHudElements.SLEEP, HUD_ID, (graphics, delta) -> {
			Component progress = BenchmarkController.progress();
			if (progress != null) {
				Font font = Minecraft.getInstance().font;
				graphics.fill(4, 4, 12 + font.width(progress), 18, 0x90000000);
				graphics.text(font, progress, 8, 7, 0xFFFFFFFF, false);
			}
		});
	}

	public static RigTuneController controller() {
		return controller;
	}

	// v0.4 (WS-P, docs/v0.4/SPEC.md 4): follows the power state once the startup probe has found a real battery.
	private static void powerWatcher(RealController real) {
		ClientLifecycleEvents.CLIENT_STARTED.register(minecraft -> HardwareProbe.slowPart().thenAcceptAsync(slow ->
				PowerWatcher.startIfBattery(slow.hasBattery(), slow.onBattery(), real.profileService()::powerChanged), Probes.EXECUTOR));
		ClientLifecycleEvents.CLIENT_STOPPING.register(minecraft -> PowerWatcher.stop());
	}

	public static void setController(RigTuneController newController) {
		controller = newController;
	}

	public static @Nullable HardwareProfile hardware() {
		return hardware;
	}

	static void setHardware(@Nullable HardwareProfile profile) {
		if (profile != null && hardware == null) {
			RigTune.LOGGER.info("Hardware: {}", profile);
		}
		hardware = profile;
	}

	public static KeyMapping openKey() {
		return openKey;
	}

	public static RigTuneScreen open(@Nullable Screen parent) {
		RigTuneScreen screen = new RigTuneScreen(parent, controller);
		Minecraft.getInstance().gui.setScreen(screen);
		return screen;
	}

	private static void launchHelperIfPending() {
		Path configDir = FabricLoader.getInstance().getConfigDir();
		Path pending = PendingActions.defaultPath(configDir);
		if (!Files.isRegularFile(pending)) {
			return;
		}
		try {
			HelperLauncher.launch(configDir, pending);
			RigTune.LOGGER.info("Started the RigTune apply helper for {}", pending);
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.error("Could not start the RigTune apply helper; staged changes stay in {}", pending, e);
		}
	}

	private static void onTick(Minecraft minecraft) {
		BenchmarkController.tick(minecraft);
		while (openKey.consumeClick()) {
			if (!(minecraft.gui.screen() instanceof RigTuneScreen) && !BenchmarkController.running()) {
				open(minecraft.gui.screen());
			}
		}
		if (minecraft.gui.screen() instanceof RigTuneScreen
				&& PRIVACY_TOAST.onRigTuneScreen(minecraft.gui.toastManager().getToast(SystemToast.class, PRIVACY_ID) != null)) {
			SystemToast.forceHide(minecraft.gui.toastManager(), PRIVACY_ID);
		}
		if (!titleSeen || !(minecraft.gui.screen() instanceof TitleScreen)) {
			return;
		}
		Path configDir = FabricLoader.getInstance().getConfigDir();
		ClientSettings settings = ClientSettings.shared(configDir);
		if (!noticesShown) {
			noticesShown = true;
			showNotices(minecraft);
			PRIVACY_TOAST.take(StartupNotices.takePrivacyNotice(settings, configDir, Probes.EXECUTOR));
		}
		if (PRIVACY_TOAST.onTitleScreen()) {
			SystemToast.add(minecraft.gui.toastManager(), PRIVACY_ID, Component.translatable("rigtune.settings.privacy_toast.title"),
					Component.translatable("rigtune.settings.privacy_toast.body"));
		}
		if (!toastShown) {
			Report report = controller.report();
			if (report != null) {
				toastShown = true;
				long important = report.recommendations().stream().filter(RigTuneClient::important).count();
				if (StartupNotices.showSuggestionsToast(settings, important)) {
					SystemToast.add(minecraft.gui.toastManager(), TOAST_ID,
							Component.translatable("rigtune.toast.title", report.recommendations().size()),
							Component.translatable("rigtune.toast.body", openKey.getTranslatedKeyMessage()));
				}
			}
		}
	}

	public static void showNotices(Minecraft minecraft) {
		ApplyResult result = RigTunePreLaunch.takeUnseenResult();
		if (result != null) {
			int total = result.results().size();
			int failed = result.failedOps().size();
			if (failed == 0) {
				SystemToast.add(minecraft.gui.toastManager(), NOTICE_ID,
						Component.translatable("rigtune.toast.applied.title", total),
						Component.translatable("rigtune.toast.applied.body"));
			} else {
				SystemToast.add(minecraft.gui.toastManager(), NOTICE_ID,
						Component.translatable("rigtune.toast.failed.title", failed, total),
						Component.translatable("rigtune.toast.failed.body"));
			}
			int abandoned = result.abandonedOps().size();
			if (abandoned > 0) {
				SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(10000L),
						Component.translatable("rigtune.toast.abandoned.title", abandoned),
						Component.translatable("rigtune.toast.abandoned.body"));
			}
			Path configDir = FabricLoader.getInstance().getConfigDir();
			ClientState state = ClientState.shared(configDir);
			state.lastShownApply = result.finishedAt();
			state.save(configDir);
		}
		if (RigTunePreLaunch.takeHelperBusy()) {
			SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(12000L),
					Component.translatable("rigtune.toast.busy.title"),
					Component.translatable("rigtune.toast.busy.body"));
		}
		int leftover = RigTunePreLaunch.takeLeftoverOps();
		if (leftover > 0) {
			SystemToast.add(minecraft.gui.toastManager(), new SystemToast.SystemToastId(10000L),
					Component.translatable("rigtune.toast.leftover.title", leftover),
					Component.translatable("rigtune.toast.leftover.body"));
		}
	}

	static boolean important(Recommendation r) {
		return r.category() == Category.WARNING || r.impact() == Impact.HIGH;
	}

	private static void addEntryButton(Screen screen, int width, int height) {
		boolean title = screen instanceof TitleScreen;
		boolean video = screen instanceof VideoSettingsScreen || screen.getClass().getName().equals(SODIUM_VIDEO_SCREEN);
		if (!title && !video) {
			return;
		}
		titleSeen |= title;
		List<AbstractWidget> widgets = Screens.getWidgets(screen);
		Button button = Button.builder(Component.translatable("rigtune.button"), b -> open(screen))
				.tooltip(Tooltip.create(Component.translatable("rigtune.button.tooltip")))
				.size(BUTTON_WIDTH, 20)
				.build();
		int[] spot;
		if (screen.getClass().getName().equals(SODIUM_VIDEO_SCREEN)) {
			spot = new int[]{6, height - 26};
		} else {
			AbstractWidget anchor = findByKey(widgets, title ? "menu.options" : "gui.done");
			spot = anchor == null ? null : place(anchor, widgets, width, title);
		}
		if (spot == null) {
			spot = new int[]{width - BUTTON_WIDTH - 4, 4};
		}
		button.setPosition(spot[0], spot[1]);
		widgets.add(button);
		// Sodium's page list sits earlier in the child list and swallows clicks in its column, so claim ours first.
		ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> {
			if (event.button() == 0 && button.visible && button.isMouseOver(event.x(), event.y()) && Screens.getWidgets(s).contains(button)) {
				button.playDownSound(Minecraft.getInstance().getSoundManager());
				button.onPress(event);
				return false;
			}
			return true;
		});
	}

	private static int @Nullable [] place(AbstractWidget anchor, List<AbstractWidget> widgets, int width, boolean preferLeft) {
		int left = anchor.getX() - 4 - BUTTON_WIDTH;
		int right = anchor.getX() + anchor.getWidth() + 4;
		int[][] candidates = preferLeft ? new int[][]{{left, anchor.getY()}, {right, anchor.getY()}} : new int[][]{{right, anchor.getY()}, {left, anchor.getY()}};
		for (int[] c : candidates) {
			if (c[0] >= 2 && c[0] + BUTTON_WIDTH <= width - 2 && free(c[0], c[1], widgets)) {
				return c;
			}
		}
		return null;
	}

	private static boolean free(int x, int y, List<AbstractWidget> widgets) {
		for (AbstractWidget w : widgets) {
			if (w.visible && x < w.getX() + w.getWidth() && x + BUTTON_WIDTH > w.getX() && y < w.getY() + w.getHeight() && y + 20 > w.getY()) {
				return false;
			}
		}
		return true;
	}

	private static @Nullable AbstractWidget findByKey(List<AbstractWidget> widgets, String key) {
		for (AbstractWidget w : widgets) {
			if (w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals(key)) {
				return w;
			}
		}
		return null;
	}
}
