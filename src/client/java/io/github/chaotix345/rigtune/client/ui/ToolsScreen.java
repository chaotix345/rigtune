package io.github.chaotix345.rigtune.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.PreloadTimer;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend;
import io.github.chaotix345.rigtune.core.hardware.PerfCounterAdvice;
import io.github.chaotix345.rigtune.core.hardware.PerfCounters;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// The hub behind RigTuneScreen's one "Tools…" button (docs/v0.4/SPEC.md C3, X3; plan review X-M2): Benchmark (the
// existing BenchmarkMenuScreen, formerly a footer button), Profiles, Stutter Doctor, JVM & memory and Benchmark history, in
// that order, then the startup-time line (item 13). Features add nothing to RigTuneScreen's footer; each lives behind
// its entry here.
// v0.5 (docs/v0.5/PLAN.md WS-W; SPEC X6, X12): the launch-time section is a scrolling RowList under the buttons, so it
// never clips (0.4 cut its notes at about 4 lines at 640x480 GUI scale 2): the startup line, its notes and, while
// Windows' performance counters are off, 2L's advice (C18's lines go in the same list). Every line is a Tab stop; the
// Microsoft pages open vanilla's link confirmation. The buttons stay screen widgets above it.
// C18 (docs/v0.5/SPEC.md 9, WS-W2): while the latest launch is slower than usual, the STARTUP_REGRESSION notice's two lines
// (the numbers and the one "may be related" cause) under the startup line, in the note colour.
public class ToolsScreen extends Screen {
	private static final int BUTTON_WIDTH = 200;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_DETAIL = 0xFF808080;
	private static final int COLOR_NOTE = 0xFFE0C060;
	private static final int COLOR_LINK = 0xFF7EC8FF;
	private static final int LINE = 10;
	private static final int TOP = 28;
	private static final int BUTTON_ROW = 24;
	private static final int GAP = 4;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private @Nullable Component startupLine;
	private List<FormattedCharSequence> startupDetail = new ArrayList<>();
	private List<Component> perfCounterLines = List.of();
	private List<Component> regressionLines = List.of();
	private PerfCounters shownPerfCounters = PerfCounters.NOT_READ;
	private @Nullable ToolsList list;
	private double scroll;
	// docs/v0.4/SPEC.md 11: the row that had the keyboard focus before a rebuild, which gets it back.
	private int focusedRow = -1;

	public ToolsScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.tools.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		List<Button> buttons = List.of(
				Button.builder(Component.translatable("rigtune.screen.benchmark_menu"), b -> openBenchmark()).build(),
				Button.builder(Component.translatable("rigtune.tools.profiles"), b -> openProfiles()).build(),
				Button.builder(Component.translatable("rigtune.tools.stutter"), b -> openStutter()).build(),
				Button.builder(Component.translatable("rigtune.tools.jvm"), b -> openJvm()).build(),
				Button.builder(Component.translatable("rigtune.tools.benchmark_history"), b -> openBenchmarkHistory()).build());
		int buttonWidth = Math.min(BUTTON_WIDTH, width - 16);
		int x = (width - buttonWidth) / 2;
		int y = TOP;
		for (Button button : buttons) {
			button.setRectangle(buttonWidth, 20, x, y);
			addRenderableWidget(button);
			y += BUTTON_ROW;
		}
		// The game keeps the GUI at least 320x240, so the list has at least 60 px (about 5 lines) under the buttons.
		int footerTop = height - 28;
		list = new ToolsList(y, Math.max(LINE + 2, footerTop - GAP - y), Math.max(40, width - 24));
		populate(list);
		addRenderableWidget(list);
		list.setScrollAmount(scroll);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
				.bounds(x, footerTop, buttonWidth, 20).build());
	}

	// The probe's slow part finishes off the render thread: show its performance-counter result when it arrives (review L9).
	@Override
	public void tick() {
		if (HardwareProbe.perfCounters() != shownPerfCounters) {
			rebuildWidgets();
		}
	}

	@Override
	protected void rebuildWidgets() {
		if (list != null) {
			scroll = list.scrollAmount();
			focusedRow = list.focusedRow();
		}
		super.rebuildWidgets();
	}

	@Override
	protected void setInitialFocus() {
		ComponentPath path = RowList.initialFocus(this, list, focusedRow, minecraft.getLastInputType().isKeyboard());
		focusedRow = -1;
		if (path != null) {
			changeFocus(path);
		}
	}

	// The launch-time section: item 13's line and notes, then 2L's advice.
	private void populate(ToolsList target) {
		StartupTimes.View startup = controller.startupTimes();
		startupLine = startupLine(startup);
		List<FormattedCharSequence> detail = new ArrayList<>();
		if (startupLine != null) {
			target.row(startupLine, COLOR_LABEL, null, 1);
		}
		regressionLines(target, startup);
		for (Component line : startupDetail(startup)) {
			detail.addAll(target.row(line, COLOR_DETAIL, null, 1).lines);
		}
		startupDetail = detail;
		perfCounterLines(target);
	}

	// 2L (docs/v0.5/SPEC.md): while Windows' performance counters are off, what that setting is and Microsoft's pages, with
	// this launch's crash-report setup time when it was measured.
	private void perfCounterLines(ToolsList target) {
		List<Component> shown = new ArrayList<>();
		shownPerfCounters = HardwareProbe.perfCounters();
		for (PerfCounterAdvice.Line line : PerfCounterAdvice.lines(shownPerfCounters, PreloadTimer.preloadMs())) {
			Component text = Texts.component(line.text());
			URI link = line.url() == null ? null : URI.create(line.url());
			boolean first = shown.isEmpty();
			target.row(text, link != null ? COLOR_LINK : first ? COLOR_NOTE : COLOR_DETAIL, link == null ? null : () -> ConfirmLinkScreen.confirmLinkNow(this, link),
					first ? 6 : 1);
			shown.add(text);
		}
		perfCounterLines = List.copyOf(shown);
	}

	// C18: the regression line and its cause while the latest launch is SLOWER (never from fewer than 5 comparable launches).
	private void regressionLines(ToolsList target, StartupTimes.View view) {
		List<Component> shown = new ArrayList<>();
		StartupTrend.Assessment assessment = view.assessment();
		if (assessment != null && assessment.slower()) {
			for (Text line : List.of(StartupTrend.regression(assessment), StartupTrend.cause(assessment))) {
				Component text = Texts.component(line);
				target.row(text, COLOR_NOTE, null, 1);
				shown.add(text);
			}
		}
		regressionLines = List.copyOf(shown);
	}

	// Item 13 (the footprint workstream): "Last launch 14.5 s · median of the last 10: 14.3 s"; null shows nothing. C18
	// (review L3): once the trend compares, its "usual" (the comparable launches before the last one) is the median shown.
	private @Nullable Component startupLine(StartupTimes.View view) {
		if (view.lastMs() == null) {
			return null;
		}
		StartupTrend.Assessment assessment = view.assessment();
		if (assessment != null && assessment.rawMedianMs() != null) {
			return Component.translatable("rigtune.startup.last_median", seconds(view.lastMs()), assessment.baselineRuns(),
					seconds(Math.round(assessment.rawMedianMs())));
		}
		if (view.runs() < 2 || view.medianMs() == null) {
			return Component.translatable("rigtune.startup.last", seconds(view.lastMs()));
		}
		return Component.translatable("rigtune.startup.last_median", seconds(view.lastMs()),
				Math.min(StartupTimesStore.MEDIAN_OF, view.runs()), seconds(view.medianMs()));
	}

	// Under the line: the mod-set note, then general advice. Never which mod is slow: Fabric Loader times no mod (SPEC 13).
	// C18: no mod-set note when the regression's cause line already names the same change (a streak's names an earlier one).
	private List<Component> startupDetail(StartupTimes.View view) {
		if (view.lastMs() == null) {
			return List.of();
		}
		return view.modSetChanged() && !modSetCause(view.assessment())
				? List.of(Component.translatable("rigtune.startup.mod_set_changed").withColor(Palette.of(COLOR_NOTE)), Component.translatable("rigtune.startup.advice"))
				: List.of(Component.translatable("rigtune.startup.advice"));
	}

	private static boolean modSetCause(StartupTrend.@Nullable Assessment assessment) {
		return assessment != null && assessment.slower() && assessment.streak() == 1
				&& (assessment.cause() == StartupTrend.Cause.MOD_COUNT || assessment.cause() == StartupTrend.Cause.MOD_SET);
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

	// The wrapped lines under the startup line, for tests.
	public List<FormattedCharSequence> startupDetail() {
		return List.copyOf(startupDetail);
	}

	// v0.5: the list scrolls, so no line under the startup line is ever cut off (0.4 clipped them above Done).
	public boolean startupDetailClipped() {
		return false;
	}

	/** For the game tests: 2L's advice lines shown (none while the counters are on or unknown). */
	public List<Component> perfCounterLines() {
		return perfCounterLines;
	}

	/** For the game tests: C18's regression and cause lines shown (none unless the latest launch is SLOWER). */
	public List<Component> regressionLines() {
		return regressionLines;
	}

	/** For the game tests: every row's text, in order. */
	public List<String> rowText() {
		List<String> out = new ArrayList<>();
		if (list != null) {
			for (ToolsList.Row row : list.children()) {
				out.add(row.text.getString());
			}
		}
		return out;
	}

	/** For the game tests: every row's lines fit inside the list's rows. */
	public boolean rowsFit() {
		return list == null || list.children().stream().allMatch(ToolsList.Row::textFits);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	public final class ToolsList extends RowList<ToolsList.Row> {
		private final int rowWidth;

		ToolsList(int top, int listHeight, int rowWidth) {
			super(ToolsScreen.this.minecraft, ToolsScreen.this.width, listHeight, top, LINE);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		// top: the space above the row's first line (more above the first line of a new part).
		Row row(Component text, int color, @Nullable Runnable action, int top) {
			// The row's content width (an entry's content is its width less 2 px a side).
			Row row = new Row(text, color, action, top, rowWidth - 4);
			addEntry(row, row.height());
			return row;
		}

		// Wrapped, centred lines of text (as 0.4 drew them): a Tab stop that narrates them; with an action (a link), a left
		// click or Enter runs it.
		public final class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final Component text;
			private final List<FormattedCharSequence> lines;
			private final int color;
			private final int top;
			private final @Nullable Runnable action;
			private final RowFocus focus;

			Row(Component text, int color, @Nullable Runnable action, int top, int wrap) {
				this.text = text;
				this.lines = font.split(text, Math.max(40, wrap));
				this.color = color;
				this.top = top;
				this.action = action;
				this.focus = new RowFocus(this, text, action, null);
			}

			int height() {
				return top + lines.size() * LINE + 1;
			}

			boolean textFits() {
				return lines.stream().allMatch(line -> font.width(line) <= getContentWidth());
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = getContentY() + top;
				int center = getContentX() + getContentWidth() / 2;
				for (FormattedCharSequence line : lines) {
					graphics.centeredText(font, line, center, y, Palette.of(color));
					y += LINE;
				}
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				if (action != null && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
					action.run();
					return true;
				}
				return false;
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of(focus);
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of(focus);
			}
		}
	}
}
