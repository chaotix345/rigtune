package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.model.Text;
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

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

// Benchmark history (docs/v0.4/SPEC.md 7), opened from ToolsScreen and from the regression notice: one context at a time
// (the selector lists every context with a result, newest first), the note "N comparable runs; M with different
// conditions not shown", the newest run's trend (a regression lists what History recorded in between, as possibly
// related), the last benchmark with its "needs a rerun" marker, and the chart of the comparable runs.
public class BenchmarkHistoryScreen extends Screen {
	private static final int LINE = 11;
	private static final int CHART_MIN = 60;
	private static final int COLOR_TEXT = 0xFFFFFFFF;

	private final @Nullable Screen parent;
	protected final RigTuneController controller;
	private @Nullable String contextKey;
	private BenchmarkTrend.View view = BenchmarkTrend.View.EMPTY;
	private List<TrendText.Line> lines = List.of();
	// The lines wrapped to the screen's width.
	private List<Row> rows = List.of();
	private int linesTop;
	private int chartTop;
	private boolean chartDrawn;

	private record Row(FormattedCharSequence text, int color) {
	}

	public BenchmarkHistoryScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.benchmark.trend.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		view = controller.benchmarkTrend(contextKey);
		contextKey = view.contextKey();
		int column = Math.min(360, width - 16);
		int left = (width - column) / 2;
		linesTop = 24;
		if (contextKey != null) {
			CycleButton<String> selector = CycleButton.builder(this::contextLabel, contextKey)
					.withValues(view.contextKeys())
					.withTooltip(key -> Tooltip.create(contextLabel(key).copy().append(CommonComponents.NEW_LINE)
							.append(Component.translatable("rigtune.benchmark.trend.selector.tooltip"))))
					.displayOnlyValue()
					.create(left, 22, column, 20, Component.translatable("rigtune.benchmark.trend.selector"), (button, key) -> {
						contextKey = key;
						rebuildWidgets();
					});
			selector.active = view.contextKeys().size() > 1;
			addRenderableWidget(selector);
			linesTop = 46;
		}
		int bottom = height - 34;
		int maxRows = Math.max(1, (bottom - CHART_MIN - linesTop) / LINE);
		// Wrapped rows count (review L1): fewer change rows until they fit (at least 2 are listed).
		for (int budget = maxRows; ; budget--) {
			lines = lines(view, budget);
			rows = wrap(lines);
			if (rows.size() <= maxRows || budget <= 1) {
				break;
			}
		}
		chartTop = linesTop + rows.size() * LINE + 4;
		// review-8 UV-3: each line (the note, the trend or regression, its changes, the last benchmark) is a Tab stop the
		// narrator reads, over the rows it wraps to. The chart itself stays painted (v0.5).
		int row = 0;
		for (TrendText.Line line : lines) {
			Component text = Texts.component(line.text());
			int count = Math.max(1, font.split(text, width - 16).size());
			addRenderableWidget(RowFocus.standalone(text, 8, linesTop + row * LINE - 1, Math.max(1, width - 16), count * LINE));
			row += count;
		}
		int buttonWidth = Math.min(200, width - 16);
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
	}

	private List<Row> wrap(List<TrendText.Line> shown) {
		List<Row> out = new ArrayList<>();
		for (TrendText.Line line : shown) {
			for (FormattedCharSequence row : font.split(Texts.component(line.text()), width - 16)) {
				out.add(new Row(row, BenchmarkTrendLines.color(line.tone())));
			}
		}
		return out;
	}

	private Component contextLabel(String key) {
		BenchmarkRecord example = view.example(key);
		boolean versions = view.examples().stream().map(BenchmarkRecord::mcVersion).distinct().count() > 1;
		return example == null ? SafeLiteral.of(key) : Texts.component(TrendText.context(example, versions));
	}

	// The note, the trend, the last benchmark; change rows are cut first (to "…and N more") when space is short.
	static List<TrendText.Line> lines(BenchmarkTrend.View view, int maxLines) {
		if (view.last() == null) {
			return List.of(new TrendText.Line(TrendText.empty(), TrendText.Tone.NORMAL));
		}
		List<TrendText.Line> tail = new ArrayList<>();
		Text last = TrendText.last(view.last(), ZoneId.systemDefault());
		if (last != null) {
			tail.add(new TrendText.Line(last, TrendText.Tone.NORMAL));
		}
		Text rerun = TrendText.rerun(view.stale());
		if (rerun != null) {
			tail.add(new TrendText.Line(rerun, TrendText.Tone.WARNING));
		}
		List<TrendText.Line> out = new ArrayList<>();
		out.add(new TrendText.Line(TrendText.note(view.comparableRuns(), view.otherRuns()), TrendText.Tone.NORMAL));
		// Without change rows the trend needs at most 4 lines (regression, header, outside, "more").
		int changeRows = Math.max(2, maxLines - 1 - tail.size() - 3);
		out.addAll(TrendText.assessment(view, ZoneId.systemDefault(), BenchmarkTrendLines::describe, changeRows));
		out.addAll(tail);
		return out;
	}

	/** For the game tests: the lines shown, in English templates' keys and arguments. */
	public List<TrendText.Line> shownLines() {
		return lines;
	}

	public BenchmarkTrend.View view() {
		return view;
	}

	/** For the game tests: the chart fitted and was drawn in the last frame. */
	public boolean chartDrawn() {
		return chartDrawn;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, COLOR_TEXT);
		int y = linesTop;
		for (Row row : rows) {
			graphics.centeredText(font, row.text(), width / 2, y, Palette.of(row.color()));
			y += LINE;
		}
		int area = Math.min(width - 16, 460);
		int chartWidth = Math.min(area, BenchmarkTrend.MAX_RUNS * 24);
		BenchmarkRecord latest = view.latest();
		if (latest != null) {
			Component scene = Texts.component(TrendText.scene(latest.scene()));
			chartDrawn = TrendChart.draw(graphics, font, Component.translatable("rigtune.benchmark.chart.title", scene), view.points(), view.median(), null,
					(width - chartWidth) / 2, chartTop, chartWidth, height - 34);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
