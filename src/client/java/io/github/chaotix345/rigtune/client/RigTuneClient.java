package io.github.chaotix345.rigtune.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.client.ui.RigTuneScreen;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
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

import java.util.List;

public final class RigTuneClient implements ClientModInitializer {
	public static final String SODIUM_VIDEO_SCREEN = "net.caffeinemc.mods.sodium.client.gui.VideoSettingsScreen";
	private static final SystemToast.SystemToastId TOAST_ID = new SystemToast.SystemToastId();
	private static final int BUTTON_WIDTH = 60;

	private static RigTuneController controller;
	private static volatile @Nullable HardwareProfile hardware;
	private static KeyMapping openKey;
	private static boolean titleSeen;
	private static boolean toastShown;

	@Override
	public void onInitializeClient() {
		controller = new StubController(() -> hardware);
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(RigTune.MOD_ID, "rigtune"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.rigtune.open", InputConstants.Type.KEYSYM, InputConstants.KEY_F8, category));

		ClientLifecycleEvents.CLIENT_STARTED.register(RigTuneClient::onStarted);
		ClientTickEvents.END_CLIENT_TICK.register(RigTuneClient::onTick);
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> addEntryButton(screen, width, height));
	}

	public static RigTuneController controller() {
		return controller;
	}

	public static void setController(RigTuneController newController) {
		controller = newController;
	}

	public static @Nullable HardwareProfile hardware() {
		return hardware;
	}

	public static KeyMapping openKey() {
		return openKey;
	}

	public static RigTuneScreen open(@Nullable Screen parent) {
		RigTuneScreen screen = new RigTuneScreen(parent, controller);
		Minecraft.getInstance().gui.setScreen(screen);
		return screen;
	}

	private static void onStarted(Minecraft minecraft) {
		try {
			HardwareProbe.probe(minecraft).whenComplete((profile, error) -> {
				if (error != null) {
					RigTune.LOGGER.warn("Hardware probe failed", error);
					return;
				}
				hardware = profile;
				RigTune.LOGGER.info("Hardware: {}", profile);
				minecraft.execute(() -> controller.rescan());
			});
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Hardware probe failed", e);
		}
	}

	private static void onTick(Minecraft minecraft) {
		while (openKey.consumeClick()) {
			if (!(minecraft.gui.screen() instanceof RigTuneScreen)) {
				open(minecraft.gui.screen());
			}
		}
		if (titleSeen && !toastShown && minecraft.gui.screen() instanceof TitleScreen) {
			Report report = controller.report();
			if (report != null) {
				toastShown = true;
				long important = report.recommendations().stream().filter(RigTuneClient::important).count();
				if (important > 0) {
					SystemToast.add(minecraft.gui.toastManager(), TOAST_ID,
							Component.translatable("rigtune.toast.title", report.recommendations().size()),
							Component.translatable("rigtune.toast.body", openKey.getTranslatedKeyMessage()));
				}
			}
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
