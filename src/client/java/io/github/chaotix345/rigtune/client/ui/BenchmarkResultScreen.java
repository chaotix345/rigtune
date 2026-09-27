package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.KeepSettings;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.stutter.StutterHooks;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.ResultNotes;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.benchmark.ShaderAdvice;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.stutter.Attributor;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;

public class BenchmarkResultScreen extends Screen {
	private static final int ROW = 12;
	private static final int LINE = 11;
	private static final int CHART_RUNS = BenchmarkTrend.MAX_RUNS;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_PASS = 0xFF7FE07F;
	private static final int COLOR_FAIL = 0xFFFF7A6B;
	private static final int COLOR_WARN = 0xFFFFD166;

	// spoken: what its Tab stop narrates (the whole line; null for a continuation row of a wrapped trend line, which joins
	// the stop above it).
	private record Line(Component text, int color, @Nullable Component spoken, boolean continuation) {
		Line(Component text, int color) {
			this(text, color, text, false);
		}
	}

	// docs/v0.5/SPEC.md 2A (L3): a status line's Tab stop over the rows it's drawn on.
	private record Stop(Component text, int firstRow, int rows) {
	}

	// One drawn row of a status line: its wrapped text, or (full != null) the whole line clipped to one row with the full
	// text as a tooltip.
	private record StatusRow(FormattedCharSequence text, int color, @Nullable Component full) {
	}

	private final @Nullable Screen parent;
	private final BenchmarkController.Outcome outcome;
	private final List<PlannerResult.Measurement> rows;
	private final List<BenchmarkRecord> chartRuns;
	private final BenchmarkTrend.@Nullable View trend;
	private List<Line> lines = List.of();
	private List<StatusRow> shownRows = List.of();
	private List<Stop> stops = List.of();
	private @Nullable ResultTable table;
	// docs/v0.4/SPEC.md 11 (RowList): the table row that had the keyboard focus, and the table's scroll, kept across a
	// rebuild (a resize or GUI scale change).
	private int focusedRow = -1;
	private double scroll;
	// Where the table and the chart are drawn (chartWidth 0: no chart).
	private int tableLeft;
	private int tableWidth;
	private int chartLeft;
	private int chartWidth;
	// The trend's lines in `lines` (review M3: they give way before the table or chart does).
	private int trendFrom;
	private int trendTo;
	private int contentTop;
	private int contentBottom;

	public BenchmarkResultScreen(@Nullable Screen parent, BenchmarkController.Outcome outcome) {
		super(Component.translatable("rigtune.benchmark.title"));
		this.parent = parent;
		this.outcome = outcome;
		this.rows = outcome.result().measurements().stream().sorted(Comparator.comparingInt(PlannerResult.Measurement::rd)).toList();
		// v0.4 (docs/v0.4/SPEC.md 7): the chart shows the runs comparable with this one, not every run of the scene.
		this.chartRuns = outcome.record() != null ? BenchmarkStore.history().comparable(outcome.record(), CHART_RUNS)
				: BenchmarkStore.history().chart(outcome.request().scene().name(), HardwareProbe.minecraftVersion(), CHART_RUNS);
		this.trend = trend(outcome);
	}

	// The trend of this run's context, when this run is the newest of it (it was saved).
	private static BenchmarkTrend.@Nullable View trend(BenchmarkController.Outcome outcome) {
		BenchmarkRecord record = outcome.record();
		if (record == null) {
			return null;
		}
		BenchmarkTrend.View view = RigTuneClient.controller().benchmarkTrend(BenchmarkTrend.contextKey(record));
		return view.latest() != null && record.id().equals(view.latest().id()) ? view : null;
	}

	// Under the gain line (docs/v0.4/SPEC.md 7): the run against the usual of its comparable runs, or why nothing is claimed;
	// a regression's changes as one line with their number (the list is on Benchmark history).
	static List<TrendText.Line> trendLines(BenchmarkTrend.@Nullable View view, ZoneId zone, Function<HistoryModel.Change, Text> describe) {
		return view == null ? List.of() : TrendText.assessment(view, zone, describe, 0);
	}

	/** For the game tests: where the table or chart starts and ends. */
	public int contentTop() {
		return contentTop;
	}

	public int contentBottom() {
		return contentBottom;
	}

	public BenchmarkController.Outcome outcome() {
		return outcome;
	}

	private boolean tune() {
		return outcome.request().mode() == BenchmarkRequest.Mode.TUNE;
	}

	@Override
	protected void init() {
		lines = lines();
		contentBottom = height - 34;
		// Room for the table's header and 3 rows (or the chart).
		int maxLines = Math.max(1, (contentBottom - 26 - (tune() ? 4 * ROW + 2 : 44)) / LINE);
		shownRows = layout(maxLines);
		contentTop = 22 + shownRows.size() * LINE + 4;
		// docs/v0.5/SPEC.md 2A (L3): every status line, each table row and the chart are Tab stops the narrator reads, in
		// the order they're drawn.
		for (Stop stop : stops) {
			addRenderableWidget(RowFocus.standalone(stop.text(), 8, 22 + stop.firstRow() * LINE - 1, Math.max(1, width - 16), stop.rows() * LINE));
		}
		placeContent();
		table = null;
		if (tune() && !rows.isEmpty()) {
			table = addRenderableWidget(new ResultTable());
			table.setScrollAmount(scroll);
		} else if (tune()) {
			addRenderableWidget(RowFocus.standalone(Component.translatable("rigtune.benchmark.none"), 8, contentTop - 1, Math.max(1, width - 16), LINE));
		}
		Component chart = chartNarration();
		if (chart != null) {
			addRenderableWidget(RowFocus.standalone(chart, chartLeft, contentTop - 1, chartWidth, contentBottom - contentTop + 1));
		}
		int buttonWidth = Math.min(150, (Math.min(width - 32, 360) - 4) / 2);
		int y = height - 28;
		if (!tune()) {
			addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(width / 2 - buttonWidth / 2, y, buttonWidth, 20).build());
			return;
		}
		SessionResult session = outcome.session();
		Knobs chosen = session.chosen();
		boolean sdChanged = chosen.simulationDistance() != session.original().simulationDistance();
		Component label = sdChanged
				? Component.translatable("rigtune.benchmark.use_both", chosen.renderDistance(), chosen.simulationDistance())
				: Component.translatable(outcome.result().targetMet() ? "rigtune.benchmark.use" : "rigtune.benchmark.use_best", chosen.renderDistance());
		Button use = addRenderableWidget(Button.builder(label, b -> {
			Map<String, String> values = new LinkedHashMap<>();
			values.put("vanilla.renderDistance", Integer.toString(chosen.renderDistance()));
			if (sdChanged) {
				values.put("vanilla.simulationDistance", Integer.toString(chosen.simulationDistance()));
			}
			KeepSettings.apply(minecraft, values);
			onClose();
		}).bounds(width / 2 - buttonWidth - 2, y, buttonWidth, 20).build());
		// Never for a distance that couldn't be measured (review M1): with no Render distance step on loaded terrain, the
		// suggestion is where it started.
		use.active = !session.measurements().isEmpty() && (rows.isEmpty() || rows.stream().anyMatch(PlannerResult.Measurement::complete));
		addRenderableWidget(Button.builder(Component.translatable("rigtune.benchmark.keep", outcome.originalRd()), b -> onClose())
				.bounds(width / 2 + 2, y, buttonWidth, 20).build());
	}

	@Override
	protected void rebuildWidgets() {
		if (table != null) {
			scroll = table.scrollAmount();
			focusedRow = table.focusedRow();
		}
		super.rebuildWidgets();
	}

	// The focused table row comes back after a rebuild; otherwise, after keyboard use, the first button has it as in 0.4
	// (Use, or Done), not the first status line.
	@Override
	protected void setInitialFocus() {
		boolean keyboard = minecraft.getLastInputType().isKeyboard();
		ComponentPath path = focusedRow >= 0 ? RowList.initialFocus(this, table, focusedRow, keyboard) : null;
		focusedRow = -1;
		if (path == null && keyboard) {
			for (GuiEventListener child : children()) {
				if (child instanceof Button button && button.active) {
					path = ComponentPath.path(this, ComponentPath.leaf(button));
					break;
				}
			}
		}
		if (path != null) {
			changeFocus(path);
		}
	}

	// review-8 P5B-F4: every status line wrapped to the screen's width, within maxRows rows (see fit).
	private List<StatusRow> layout(int maxRows) {
		int textWidth = Math.max(40, width - 16);
		List<List<FormattedCharSequence>> wrapped = new ArrayList<>();
		int[] counts = new int[lines.size()];
		for (int i = 0; i < lines.size(); i++) {
			List<FormattedCharSequence> split = font.split(lines.get(i).text(), textWidth);
			wrapped.add(split.isEmpty() ? List.of(FormattedCharSequence.EMPTY) : split);
			counts[i] = wrapped.get(i).size();
		}
		int[] shown = fit(counts, maxRows, trendFrom, trendTo);
		List<StatusRow> out = new ArrayList<>();
		List<Stop> lineStops = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			Line line = lines.get(i);
			if (shown[i] == 0) {
				continue;
			}
			// A wrapped trend line's rows narrate as one line.
			if (line.continuation() && !lineStops.isEmpty() && shown[i - 1] > 0) {
				Stop previous = lineStops.removeLast();
				lineStops.add(new Stop(previous.text(), previous.firstRow(), previous.rows() + shown[i]));
			} else {
				lineStops.add(new Stop(line.spoken() != null ? line.spoken() : line.text(), out.size(), shown[i]));
			}
			// A wrapped trend line whose later rows fit() left out: its first row shows the whole line as a tooltip.
			boolean cut = line.spoken() != null && i + 1 < lines.size() && lines.get(i + 1).continuation() && shown[i + 1] == 0;
			if (shown[i] == counts[i]) {
				wrapped.get(i).forEach(row -> out.add(new StatusRow(row, line.color(), cut ? line.spoken() : null)));
			} else {
				out.add(new StatusRow(clip(line.text()), line.color(), line.text()));
			}
		}
		stops = lineStops;
		return out;
	}

	// How many rows each status line gets (0: left out; 1 of more: clipped to one row) so they fit maxRows: the trend's
	// lines after its first give way first, from its end (review M3), then the line wrapped to the most rows folds back to
	// one clipped row (the last such line first), until they fit or every line is one row; the table or chart keeps its room.
	static int[] fit(int[] wrapped, int maxRows, int trendFrom, int trendTo) {
		int[] shown = wrapped.clone();
		int total = 0;
		for (int n : shown) {
			total += n;
		}
		for (int i = trendTo - 1; i > trendFrom && total > maxRows; i--) {
			total -= shown[i];
			shown[i] = 0;
		}
		while (total > maxRows) {
			int longest = -1;
			for (int i = 0; i < shown.length; i++) {
				if (shown[i] > 1 && (longest < 0 || shown[i] >= shown[longest])) {
					longest = i;
				}
			}
			if (longest < 0) {
				break;
			}
			total -= shown[longest] - 1;
			shown[longest] = 1;
		}
		return shown;
	}

	/** For the game tests: the status rows as drawn, and how many of them are clipped (their whole text is a tooltip). */
	public List<FormattedCharSequence> statusRows() {
		return shownRows.stream().map(StatusRow::text).toList();
	}

	public int clippedStatusRows() {
		return (int) shownRows.stream().filter(r -> r.full() != null).count();
	}

	// docs/v0.5/SPEC.md 2B: the noise warning with what it went with (RW-7), Distant Horizons generating terrain during the
	// run (RW-6) and measured with its rendering off (RW-9).
	private void noteLines(List<Line> out, BenchmarkMath.Aggregate result) {
		StutterReport capture = StutterHooks.lastBenchmark();
		int spikes = capture == null ? 0 : capture.spikes().total();
		BenchmarkRecord.Context context = outcome.record() == null ? null : outcome.record().context();
		Text noisy = ResultNotes.noisy(result.cv(), spikes, tagged(capture, Attributor.DH), tagged(capture, Attributor.CHUNKS_LOADING),
				context != null && Boolean.TRUE.equals(context.dhGenerating()));
		if (noisy != null) {
			out.add(new Line(Texts.component(noisy), COLOR_WARN));
		}
		Text generating = ResultNotes.dhGenerating(context);
		if (generating != null) {
			out.add(new Line(Texts.component(generating), COLOR_WARN));
		}
		Text dhOff = ResultNotes.dhOff(OptionalMods.dhLoaded(), outcome.session().original().dhRendering());
		if (dhOff != null) {
			out.add(new Line(Texts.component(dhOff), COLOR_LABEL));
		}
	}

	private static int tagged(@Nullable StutterReport capture, String tag) {
		Integer n = capture == null ? null : capture.tags().get(tag);
		return n == null ? 0 : n;
	}

	// v0.4 (docs/v0.4/SPEC.md 5): the Stutter Doctor's line for the benchmark's sweeps ("2 spikes; likely causes: ..."), and
	// (docs/v0.5/SPEC.md RW-15) how many steps it left out.
	private void stutterLine(List<Line> out) {
		Component line = StutterScreen.benchmarkLine(StutterHooks.lastBenchmark());
		if (line == null) {
			return;
		}
		out.add(new Line(line, COLOR_LABEL));
		Text leftOut = ResultNotes.stutterStepsLeftOut(outcome.stepsLeftOut());
		if (leftOut != null) {
			out.add(new Line(Texts.component(leftOut), COLOR_LABEL));
		}
	}

	private List<Line> lines() {
		SessionResult session = outcome.session();
		BenchmarkMath.Aggregate result = session.result();
		List<Line> out = new ArrayList<>();
		if (tune()) {
			PlannerResult rd = outcome.result();
			Component value = Component.literal(Integer.toString(session.chosen().renderDistance())).withStyle(ChatFormatting.BOLD);
			Text nothing = ResultNotes.nothingMeasured(rows, outcome.originalRd());
			out.add(nothing != null ? new Line(Texts.component(nothing), COLOR_WARN)
					: new Line(Component.translatable(rd.targetMet() ? "rigtune.benchmark.met" : "rigtune.benchmark.missed", value),
							rd.targetMet() ? COLOR_PASS : COLOR_WARN));
		} else if (result != null) {
			out.add(new Line(Component.translatable("rigtune.benchmark.measured", fps(result.avgFps()), fps(result.onePercentLowFps())), 0xFFFFFFFF));
		}
		out.add(new Line(Component.translatable("rigtune.benchmark.target", Math.round(outcome.targetFps())), 0xFFFFFFFF));
		serverLimitLine(out);
		if (result != null) {
			String p99 = String.format(Locale.ROOT, "%.1f", result.p99FrameMs());
			// Measure's headline already has the averages.
			out.add(new Line(tune()
					? Component.translatable("rigtune.benchmark.result", fps(result.avgFps()), fps(result.onePercentLowFps()), p99, result.repeats())
					: Component.translatable("rigtune.benchmark.result.detail", p99, result.repeats()), COLOR_LABEL));
			noteLines(out, result);
			stutterLine(out);
		}
		boolean sdMeasured = session.measurements().stream().anyMatch(m -> m.step().kind() == Step.Kind.SIMULATION_DISTANCE);
		if (tune() && outcome.request().scene() == BenchmarkRequest.Scene.BENCHMARK_WORLD) {
			out.add(new Line(Component.translatable("rigtune.benchmark.sd.world"), COLOR_LABEL));
		} else if (tune() && sdMeasured) {
			int chosen = session.chosen().simulationDistance();
			int original = session.original().simulationDistance();
			out.add(chosen < original
					? new Line(Component.translatable("rigtune.benchmark.sd.lowered", chosen, original), COLOR_PASS)
					: new Line(Component.translatable("rigtune.benchmark.sd.kept", chosen), COLOR_LABEL));
		}
		BenchmarkMath.Gain gain = outcome.gain();
		if (gain != null) {
			out.add(gain.significant()
					? new Line(Component.translatable("rigtune.benchmark.gain", BenchmarkMath.percent(gain.lowPercent()), BenchmarkMath.percent(gain.avgPercent())),
							gain.lowPercent() >= 0 ? COLOR_PASS : COLOR_FAIL)
					: new Line(Component.translatable("rigtune.benchmark.gain.none"), COLOR_LABEL));
		} else if (ResultNotes.pairCaveat(outcome.before(), outcome.record()) != null) {
			// No verdict (review M4): both runs' numbers and why they can't be compared.
			Text before = ResultNotes.before(outcome.before());
			if (before != null) {
				out.add(new Line(Texts.component(before), COLOR_LABEL));
			}
			out.add(new Line(Texts.component(ResultNotes.pairCaveat(outcome.before(), outcome.record())), COLOR_WARN));
		} else if (outcome.record() != null && BenchmarkRecord.BEFORE.equals(outcome.record().phase())) {
			out.add(new Line(Component.translatable("rigtune.benchmark.saved_before"), COLOR_LABEL));
		}
		// Wrapped: "Performance changed under different conditions (…); cause unknown." must be read whole.
		trendFrom = out.size();
		for (TrendText.Line line : trendLines(trend, ZoneId.systemDefault(), BenchmarkTrendLines::describe)) {
			Component full = Texts.component(line.text());
			boolean first = true;
			for (FormattedText row : font.getSplitter().splitLines(full, width - 16, Style.EMPTY)) {
				out.add(new Line(Component.literal(row.getString()), BenchmarkTrendLines.color(line.tone()), first ? full : null, !first));
				first = false;
			}
		}
		trendTo = out.size();
		if (session.dhCost() != null) {
			out.add(new Line(Component.translatable("rigtune.benchmark.cost.dh", BenchmarkMath.percent(session.dhCost().lowGainPercent()),
					BenchmarkMath.percent(session.dhCost().avgGainPercent())), COLOR_LABEL));
		} else if (session.notMeasured().containsKey(BenchmarkRecord.DISTANT_HORIZONS)) {
			out.add(new Line(Component.translatable("rigtune.benchmark.cost.dh.not_measured",
					reason(session.notMeasured().get(BenchmarkRecord.DISTANT_HORIZONS))), COLOR_WARN));
		}
		if (session.shaderCost() != null) {
			out.add(new Line(Component.translatable("rigtune.benchmark.cost.shaders", BenchmarkMath.percent(session.shaderCost().lowGainPercent()),
					BenchmarkMath.percent(session.shaderCost().avgGainPercent())), COLOR_LABEL));
			// Advice only (docs/v0.3/SPEC.md 8a): nothing in iris.properties or the pack's settings is touched.
			OptionalInt shaderCost = ShaderAdvice.costPercent(session.shaderCost(), session.targetFps());
			if (shaderCost.isPresent()) {
				out.add(new Line(Component.translatable("rigtune.benchmark.shader_advice", shaderCost.getAsInt()), COLOR_WARN));
				out.add(new Line(Component.translatable("rigtune.benchmark.shader_advice.hint"), COLOR_WARN));
			}
		} else if (session.notMeasured().containsKey(BenchmarkRecord.SHADERS)) {
			out.add(new Line(Component.translatable("rigtune.benchmark.cost.shaders.not_measured",
					reason(session.notMeasured().get(BenchmarkRecord.SHADERS))), COLOR_WARN));
		}
		// docs/v0.5/SPEC.md RW-5: a distance whose terrain hadn't loaded (on the second try too) isn't measured: not a pass,
		// not a fail.
		Text unmeasured = tune() ? ResultNotes.unmeasured(rows) : null;
		if (unmeasured != null) {
			out.add(new Line(Texts.component(unmeasured), COLOR_WARN));
		}
		if (session.deadlineHit()) {
			out.add(new Line(Component.translatable("rigtune.benchmark.deadline"), COLOR_WARN));
		}
		return out;
	}

	// v0.4 (docs/v0.4/SPEC.md 8): the connected server's view distance capped the Tune's steps; the notice's own sentence.
	private void serverLimitLine(List<Line> out) {
		if (outcome.serverLimit() > 0) {
			out.add(new Line(Component.translatable("rigtune.server.notice", outcome.serverLimit()), COLOR_WARN));
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		int y = 22;
		for (StatusRow row : shownRows) {
			graphics.centeredText(font, row.text(), width / 2, y, Palette.of(row.color()));
			if (row.full() != null && mouseX >= 8 && mouseX < width - 8 && mouseY >= y && mouseY < y + LINE) {
				graphics.setTooltipForNextFrame(font, font.split(row.full(), Math.min(250, width - 16)), mouseX, mouseY);
			}
			y += LINE;
		}
		if (table != null) {
			drawTableHeader(graphics);
		} else if (tune()) {
			graphics.centeredText(font, Component.translatable("rigtune.benchmark.none"), width / 2, contentTop, Palette.of(COLOR_LABEL));
		}
		if (chartWidth > 0) {
			drawChart(graphics, chartLeft, chartWidth);
		}
	}

	// Next to the chart when there's room, else the table alone; Measure has only the chart.
	private void placeContent() {
		int area = Math.min(width - 16, 460);
		int left = (width - area) / 2;
		chartWidth = 0;
		if (tune() && !rows.isEmpty() && area >= 300) {
			tableLeft = left;
			tableWidth = (area - 12) / 2;
			chartLeft = left + tableWidth + 12;
			chartWidth = tableWidth;
		} else if (tune()) {
			tableLeft = left;
			tableWidth = area;
		} else {
			chartLeft = left + area / 6;
			chartWidth = area * 2 / 3;
		}
	}

	// Next to the chart the table is narrow, so the P99 column is left out.
	private boolean p99Column() {
		return tableWidth >= 260;
	}

	private int[] columns() {
		return p99Column() ? new int[]{0, tableWidth * 22 / 100, tableWidth * 44 / 100, tableWidth * 66 / 100, tableWidth * 88 / 100}
				: new int[]{0, tableWidth * 30 / 100, tableWidth * 58 / 100, tableWidth * 84 / 100};
	}

	private String[] headers() {
		return p99Column()
				? new String[]{"rigtune.benchmark.col.rd", "rigtune.benchmark.col.avg", "rigtune.benchmark.col.low", "rigtune.benchmark.col.p99", "rigtune.benchmark.col.ok"}
				: new String[]{"rigtune.benchmark.col.rd", "rigtune.benchmark.col.avg", "rigtune.benchmark.col.low", "rigtune.benchmark.col.ok"};
	}

	// A row's cells as drawn: distance, average FPS, 1 % low, P99 (wide table only) and the mark.
	private List<String> cells(PlannerResult.Measurement m) {
		List<String> out = new ArrayList<>(List.of(Integer.toString(m.rd()), fps(m.stats().avgFps()), fps(m.stats().onePercentLowFps())));
		if (p99Column()) {
			out.add(String.format(Locale.ROOT, "%.1f", m.stats().p99FrameMs()));
		}
		out.add(ResultNotes.mark(m));
		return out;
	}

	// The chart's title and textual equivalent (L3) with this run's trend line; null when no chart is drawn.
	private @Nullable Component chartNarration() {
		if (chartWidth <= 0 || !TrendChart.fits(chartRuns, contentTop, chartWidth, contentBottom)) {
			return null;
		}
		Text summary = TrendText.chartSummary(chartRuns, trend == null ? null : trend.median(), ZoneId.systemDefault());
		List<TrendText.Line> trendLines = trendLines(trend, ZoneId.systemDefault(), BenchmarkTrendLines::describe);
		return RowFocus.join(chartTitle(), summary == null ? null : Texts.component(summary),
				trendLines.isEmpty() ? null : Texts.component(trendLines.getFirst().text()));
	}

	/**
	 * For the game tests (docs/v0.5/SPEC.md AC2A.2): every text the screen shows or narrates: the status lines (whole), the
	 * table's header and each row's cells and narration, and the chart's title and textual equivalent.
	 */
	public List<String> textContent() {
		List<String> out = new ArrayList<>();
		lines.forEach(line -> out.add(line.text().getString()));
		stops.forEach(stop -> out.add(stop.text().getString()));
		if (table != null) {
			for (String header : headers()) {
				out.add(Component.translatable(header).getString());
			}
			for (ResultTable.Row row : table.children()) {
				out.add(String.join(" | ", row.cells));
				out.add(row.focus.getMessage().getString());
			}
		}
		Component chart = chartNarration();
		if (chart != null) {
			out.add(chart.getString());
		}
		return out;
	}

	private void drawTableHeader(GuiGraphicsExtractor graphics) {
		int[] columns = columns();
		String[] headers = headers();
		int y = contentTop;
		graphics.fill(tableLeft - 4, y - 3, tableLeft + tableWidth + 4, y + ROW - 2, Palette.of(0x60000000));
		for (int i = 0; i < headers.length; i++) {
			graphics.text(font, Component.translatable(headers[i]), tableLeft + columns[i], y, Palette.of(COLOR_LABEL), false);
		}
	}

	// The runs comparable with this one (TrendChart): bars, the 1% low and average polylines, and the usual (median) line.
	private void drawChart(GuiGraphicsExtractor graphics, int left, int width) {
		TrendChart.draw(graphics, font, chartTitle(), chartRuns, trend == null ? null : trend.median(), outcome.record() == null ? null : outcome.record().id(),
				left, contentTop, width, contentBottom);
	}

	private Component chartTitle() {
		Component sceneName = Component.translatable("rigtune.benchmark.scene." + outcome.request().scene().name().toLowerCase(Locale.ROOT));
		return Component.translatable("rigtune.benchmark.chart.title", sceneName);
	}

	// docs/v0.5/SPEC.md 2A (L3): the measured distances as list rows, each a Tab stop that narrates its distance, numbers
	// and verdict; the header stays painted above them, and rows that don't fit scroll. Drawn as the painted table was.
	public final class ResultTable extends RowList<ResultTable.Row> {
		ResultTable() {
			super(BenchmarkResultScreen.this.minecraft, BenchmarkResultScreen.this.tableWidth + 8,
					Math.max(ROW, BenchmarkResultScreen.this.contentBottom - (BenchmarkResultScreen.this.contentTop + ROW - 2)),
					BenchmarkResultScreen.this.contentTop + ROW - 2, ROW);
			setX(tableLeft - 4);
			int suggested = outcome.session().chosen().renderDistance();
			for (PlannerResult.Measurement m : rows) {
				addEntry(new Row(m, m.rd() == suggested));
			}
		}

		@Override
		public int getRowWidth() {
			return tableWidth + 8;
		}

		// Inside the table's right edge, clear of the chart next to it.
		@Override
		protected int scrollBarX() {
			return getRowRight() - scrollbarWidth();
		}

		@Override
		protected void extractListBackground(GuiGraphicsExtractor graphics) {
		}

		@Override
		protected void extractListSeparators(GuiGraphicsExtractor graphics) {
		}

		public final class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final PlannerResult.Measurement measurement;
			private final boolean best;
			private final List<String> cells;
			private final RowFocus focus;

			Row(PlannerResult.Measurement measurement, boolean best) {
				this.measurement = measurement;
				this.best = best;
				this.cells = cells(measurement);
				this.focus = new RowFocus(this, Texts.component(ResultNotes.row(measurement, best)));
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = getY() + 2;
				if (best) {
					graphics.fill(getX(), getY(), getX() + getWidth(), getY() + ROW - 1, Palette.of(outcome.result().targetMet() ? 0x3000FF00 : 0x30FFD166));
				}
				int[] columns = columns();
				int color = Palette.of(best ? 0xFFFFFFFF : 0xFFDDDDDD);
				for (int i = 0; i < cells.size() - 1; i++) {
					graphics.text(font, cells.get(i), tableLeft + columns[i], y, color, false);
				}
				graphics.text(font, cells.getLast(), tableLeft + columns[columns.length - 1], y,
						Palette.of(measurement.passed() ? COLOR_PASS : measurement.complete() ? COLOR_FAIL : COLOR_WARN), false);
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

	// The failure's own message (an exception's detail, possibly another mod's, kept in benchmarks.json) as outside text.
	static Component reason(String notMeasured) {
		if (SessionResult.NOT_MEASURED_DEADLINE.equals(notMeasured)) {
			return Component.translatable("rigtune.benchmark.not_measured.deadline");
		}
		String failed = SessionResult.NOT_MEASURED_FAILED;
		return SafeLiteral.of(notMeasured.startsWith(failed) ? notMeasured.substring(failed.length()) : notMeasured);
	}

	// createdAt is UTC (Instant.toString); the chart shows the player's local day.
	static String chartDate(@Nullable String createdAt, ZoneId zone) {
		if (createdAt == null) {
			return "?";
		}
		try {
			return Instant.parse(createdAt).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd"));
		} catch (DateTimeException e) { // also an instant outside the zone's range (a hand-edited benchmarks.json)
			return createdAt.length() >= 10 ? createdAt.substring(5, 10) : "?";
		}
	}

	private FormattedCharSequence clip(Component text) {
		return font.width(text) <= width - 16 ? text.getVisualOrderText() : ComponentRenderUtils.clipText(text, font, width - 16);
	}

	private static String fps(double value) {
		return Long.toString(Math.round(value));
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
