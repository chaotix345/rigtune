package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The hub behind RigTuneScreen's one "Tools…" button (docs/v0.4/SPEC.md C3, X3; plan review X-M2): Benchmark (the
// existing BenchmarkMenuScreen, formerly a footer button), Profiles, Stutter Doctor, JVM & memory and Benchmark history, in
// that order, then the startup-time line (item 13). Features add nothing to RigTuneScreen's footer; each lives behind
// its entry here.
public class ToolsScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_DETAIL = 0xFF808080;
	private static final int COLOR_NOTE = 0xFFE0C060;
	private static final int LINE = 10;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private @Nullable Component startupLine;
	private List<FormattedCharSequence> startupDetail = new ArrayList<>();
	private boolean startupDetailClipped;
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
		StartupTimes.View startup = controller.startupTimes();
		startupLine = startupLine(startup);
		// Wrapped to the screen (not the buttons) so the note and the advice fit above Done at 640x480, GUI scale 2.
		List<FormattedCharSequence> detail = new ArrayList<>();
		for (Component line : startupDetail(startup)) {
			detail.addAll(font.split(line, Math.max(40, width - 16)));
		}
		int room = Math.max(0, (height - 30 - (startupY + LINE + 2)) / LINE);
		startupDetailClipped = detail.size() > room;
		startupDetail = new ArrayList<>(detail.subList(0, Math.min(room, detail.size())));
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(x, height - 28, buttonWidth, 20).build());
	}

	// Item 13 (the footprint workstream): "Last launch 14.5 s · median of the last 10: 14.3 s"; null shows nothing.
	private @Nullable Component startupLine(StartupTimes.View view) {
		if (view.lastMs() == null) {
			return null;
		}
		if (view.runs() < 2 || view.medianMs() == null) {
			return Component.translatable("rigtune.startup.last", seconds(view.lastMs()));
		}
		return Component.translatable("rigtune.startup.last_median", seconds(view.lastMs()),
				Math.min(StartupTimesStore.MEDIAN_OF, view.runs()), seconds(view.medianMs()));
	}

	// Under the line: the mod-set note, then general advice. Never which mod is slow: Fabric Loader times no mod (SPEC 13).
	private List<Component> startupDetail(StartupTimes.View view) {
		if (view.lastMs() == null) {
			return List.of();
		}
		return view.modSetChanged()
				? List.of(Component.translatable("rigtune.startup.mod_set_changed").withColor(Palette.of(COLOR_NOTE)), Component.translatable("rigtune.startup.advice"))
				: List.of(Component.translatable("rigtune.startup.advice"));
	}

	private static String seconds(long ms) {
		return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
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

	// The wrapped lines under the startup line that fit above Done, and whether any didn't, for tests.
	public List<FormattedCharSequence> startupDetail() {
		return List.copyOf(startupDetail);
	}

	public boolean startupDetailClipped() {
		return startupDetailClipped;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		if (startupLine != null) {
			graphics.centeredText(font, startupLine, width / 2, startupY, Palette.of(COLOR_LABEL));
			int y = startupY + LINE + 2;
			for (FormattedCharSequence line : startupDetail) {
				graphics.centeredText(font, line, width / 2, y, Palette.of(COLOR_DETAIL));
				y += LINE;
			}
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
