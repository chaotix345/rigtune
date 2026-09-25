package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkWorld;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Mode;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

// Tune, or Measure before/after a change (docs/v0.2/SPEC.md item 6), in the current world or the benchmark world.
public class BenchmarkMenuScreen extends Screen {
	private static final int LINE = 10;
	private static final int BUTTON_WIDTH = 200;
	private static final int COLOR_TEXT = 0xFFDDDDDD;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_WARN = 0xFFFFD166;

	private final @Nullable Screen parent;
	protected final RigTuneController controller;
	private Scene scene = Scene.CURRENT;
	private List<FormattedCharSequence> intro = List.of();
	private int hintY;
	private int statusY;
	private @Nullable Component status;

	public BenchmarkMenuScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.benchmark.menu.title"));
		this.parent = parent;
		this.controller = controller;
	}

	private static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	protected void init() {
		scene = savedScene();
		int textWidth = Math.min(width - 40, 360);
		intro = font.split(Component.translatable("rigtune.benchmark.menu.intro"), textWidth);
		intro = intro.subList(0, Math.min(3, intro.size()));
		int x = (width - BUTTON_WIDTH) / 2;
		int y = 24 + intro.size() * LINE + 6;
		if (BenchmarkWorld.supported()) {
			addRenderableWidget(CycleButton.builder((Scene s) -> sceneName(s), scene)
					.withValues(Scene.values())
					.create(x, y, BUTTON_WIDTH, 20, Component.translatable("rigtune.benchmark.menu.scene"), (button, value) -> {
						scene = value;
						ClientSettings settings = ClientSettings.shared(configDir());
						settings.benchmarkScene = value.name();
						settings.save(configDir());
						status = null;
						rebuildWidgets();
					}));
			y += 24;
		}
		hintY = y;
		y += 2 * LINE + 4;

		String refusal = BenchmarkController.unavailable(minecraft, scene);
		Optional<BenchmarkRecord> before = BenchmarkStore.history().openBefore(scene.name(), HardwareProbe.minecraftVersion());
		addAction(Component.translatable("rigtune.benchmark.menu.tune"), new BenchmarkRequest(Mode.TUNE, scene, null), refusal, null, x, y);
		y += 24;
		addAction(Component.translatable("rigtune.benchmark.menu.measure_before"), new BenchmarkRequest(Mode.MEASURE, scene, UUID.randomUUID().toString()),
				refusal, null, x, y);
		y += 24;
		String afterRefusal = refusal != null ? refusal : before.isEmpty() ? "rigtune.benchmark.menu.measure_after.none" : null;
		Component afterTip = scene == Scene.CURRENT ? Component.translatable("rigtune.benchmark.menu.measure_after.location") : null;
		addAction(Component.translatable("rigtune.benchmark.menu.measure_after"),
				new BenchmarkRequest(Mode.MEASURE, scene, before.map(BenchmarkRecord::pairId).orElse(null)), afterRefusal, afterTip, x, y);
		y += 24;
		statusY = y + 2;
		addRenderableWidget(Button.builder(CommonComponents.GUI_BACK, b -> onClose()).bounds(x, height - 28, BUTTON_WIDTH, 20).build());
	}

	private void addAction(Component label, BenchmarkRequest request, @Nullable String refusal, @Nullable Component tip, int x, int y) {
		Button button = Button.builder(label, b -> start(request)).bounds(x, y, BUTTON_WIDTH, 20).build();
		button.active = refusal == null;
		if (refusal != null) {
			button.setTooltip(Tooltip.create(Component.translatable(refusal)));
		} else if (tip != null) {
			button.setTooltip(Tooltip.create(tip));
		}
		addRenderableWidget(button);
	}

	private void start(BenchmarkRequest request) {
		controller.startBenchmark(request);
		if (minecraft.gui.screen() == this) {
			status = BenchmarkController.running() ? null : controller.status();
			rebuildWidgets();
		}
	}

	private static Scene savedScene() {
		String saved = ClientSettings.shared(configDir()).benchmarkScene;
		try {
			Scene s = saved == null ? Scene.CURRENT : Scene.valueOf(saved);
			return s == Scene.BENCHMARK_WORLD && !BenchmarkWorld.supported() ? Scene.CURRENT : s;
		} catch (IllegalArgumentException e) {
			return Scene.CURRENT;
		}
	}

	private static Component sceneName(Scene s) {
		return Component.translatable("rigtune.benchmark.scene." + s.name().toLowerCase(Locale.ROOT));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 10, 0xFFFFFFFF);
		int y = 24;
		for (FormattedCharSequence line : intro) {
			graphics.centeredText(font, line, width / 2, y, COLOR_TEXT);
			y += LINE;
		}
		String hint = "rigtune.benchmark.menu.scene." + scene.name().toLowerCase(Locale.ROOT) + ".hint";
		graphics.centeredText(font, Component.translatable(hint), width / 2, hintY, COLOR_LABEL);
		graphics.centeredText(font, Component.translatable("rigtune.benchmark.menu.duration"), width / 2, hintY + LINE, COLOR_LABEL);
		if (status != null) {
			graphics.centeredText(font, status, width / 2, statusY, COLOR_WARN);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
