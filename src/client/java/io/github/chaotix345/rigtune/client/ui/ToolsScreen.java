package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

// The hub behind RigTuneScreen's one "Tools…" button (docs/v0.4/SPEC.md C3, X3; plan review X-M2): Benchmark (the
// existing BenchmarkMenuScreen, formerly a footer button), Profiles, Stutter Doctor, JVM & memory and Benchmark history, in
// that order, then the startup-time line (item 13). Features add nothing to RigTuneScreen's footer; each lives behind
// its entry here.
public class ToolsScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int COLOR_LABEL = 0xFFA8A8A8;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private @Nullable Component startupLine;
	private int startupY;

	public ToolsScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.tools.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		int buttonWidth = Math.min(BUTTON_WIDTH, width - 16);
		int x = (width - buttonWidth) / 2;
		int y = 28;
		for (Button button : List.of(
				Button.builder(Component.translatable("rigtune.screen.benchmark_menu"), b -> openBenchmark()).build(),
				Button.builder(Component.translatable("rigtune.tools.profiles"), b -> openProfiles()).build(),
				Button.builder(Component.translatable("rigtune.tools.stutter"), b -> openStutter()).build(),
				Button.builder(Component.translatable("rigtune.tools.jvm"), b -> openJvm()).build(),
				Button.builder(Component.translatable("rigtune.tools.benchmark_history"), b -> openBenchmarkHistory()).build())) {
			button.setRectangle(buttonWidth, 20, x, y);
			addRenderableWidget(button);
			y += 24;
		}
		startupY = y + 4;
		startupLine = startupLine(controller.startupTimes());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(x, height - 28, buttonWidth, 20).build());
	}

	// Item 13 (the footprint workstream): "Last launch 14.5 s · median of the last 10: 14.3 s"; null shows nothing.
	private @Nullable Component startupLine(StartupTimes.View view) {
		return null;
	}

	public void openBenchmark() {
		minecraft.gui.setScreen(new BenchmarkMenuScreen(this, controller));
	}

	public void openProfiles() {
		minecraft.gui.setScreen(new ProfilesScreen(this, controller));
	}

	public void openStutter() {
		minecraft.gui.setScreen(new StutterScreen(this, controller));
	}

	public void openJvm() {
		minecraft.gui.setScreen(new JvmScreen(this, controller));
	}

	public void openBenchmarkHistory() {
		minecraft.gui.setScreen(new BenchmarkHistoryScreen(this, controller));
	}

	public @Nullable Component startupLine() {
		return startupLine;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		if (startupLine != null) {
			graphics.centeredText(font, startupLine, width / 2, startupY, COLOR_LABEL);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
