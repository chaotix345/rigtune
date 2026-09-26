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
		lines = lines(view, Math.max(1, (bottom - CHART_MIN - linesTop) / LINE));
		List<Row> wrapped = new ArrayList<>();
		for (TrendText.Line line : lines) {
			for (FormattedCharSequence row : font.split(Texts.component(line.text()), width - 16)) {
				wrapped.add(new Row(row, BenchmarkTrendLines.color(line.tone())));
			}
		}
		rows = wrapped;
		chartTop = linesTop + rows.size() * LINE + 4;
		int buttonWidth = Math.min(200, width - 16);
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds((width - buttonWidth) / 2, height - 28, buttonWidth, 20).build());
	}

	private Component contextLabel(String key) {
		BenchmarkRecord example = view.example(key);
		boolean versions = view.examples().stream().map(BenchmarkRecord::mcVersion).distinct().count() > 1;
		return example == null ? Component.literal(key) : Texts.component(TrendText.context(example, versions));
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

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, COLOR_TEXT);
		int y = linesTop;
		for (Row row : rows) {
			graphics.centeredText(font, row.text(), width / 2, y, row.color());
			y += LINE;
		}
		int area = Math.min(width - 16, 460);
		int chartWidth = Math.min(area, BenchmarkTrend.MAX_RUNS * 24);
		BenchmarkRecord latest = view.latest();
		if (latest != null) {
			Component scene = Texts.component(TrendText.scene(latest.scene()));
			TrendChart.draw(graphics, font, Component.translatable("rigtune.benchmark.chart.title", scene), view.points(), view.median(), null,
					(width - chartWidth) / 2, chartTop, chartWidth, height - 34);
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
