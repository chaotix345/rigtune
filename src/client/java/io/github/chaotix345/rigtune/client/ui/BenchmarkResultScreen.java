package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.RigTuneClient;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.KeepSettings;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.benchmark.ShaderAdvice;
import io.github.chaotix345.rigtune.core.benchmark.Step;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
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
	// Change rows listed under a regression before "…and N more" (the rest is on the Benchmark history screen).
	private static final int MAX_CHANGE_LINES = 3;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_PASS = 0xFF7FE07F;
	private static final int COLOR_FAIL = 0xFFFF7A6B;
	private static final int COLOR_WARN = 0xFFFFD166;

	private record Line(Component text, int color) {
	}

	private final @Nullable Screen parent;
	private final BenchmarkController.Outcome outcome;
	private final List<PlannerResult.Measurement> rows;
	private final List<BenchmarkRecord> chartRuns;
	private final BenchmarkTrend.@Nullable View trend;
	private List<Line> lines = List.of();
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

	// Under the gain line (docs/v0.4/SPEC.md 7): the run against the usual of its comparable runs, or why nothing is claimed.
	static List<TrendText.Line> trendLines(BenchmarkTrend.@Nullable View view, ZoneId zone, Function<HistoryModel.Change, Text> describe) {
		return view == null ? List.of() : TrendText.assessment(view, zone, describe, MAX_CHANGE_LINES);
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
		contentTop = 22 + lines.size() * LINE + 4;
		contentBottom = height - 34;
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
		use.active = !session.measurements().isEmpty();
		addRenderableWidget(Button.builder(Component.translatable("rigtune.benchmark.keep", outcome.originalRd()), b -> onClose())
				.bounds(width / 2 + 2, y, buttonWidth, 20).build());
	}

	private List<Line> lines() {
		SessionResult session = outcome.session();
		BenchmarkMath.Aggregate result = session.result();
		List<Line> out = new ArrayList<>();
		if (tune()) {
			PlannerResult rd = outcome.result();
			Component value = Component.literal(Integer.toString(session.chosen().renderDistance())).withStyle(ChatFormatting.BOLD);
			out.add(new Line(Component.translatable(rd.targetMet() ? "rigtune.benchmark.met" : "rigtune.benchmark.missed", value),
					rd.targetMet() ? COLOR_PASS : COLOR_WARN));
		} else if (result != null) {
			out.add(new Line(Component.translatable("rigtune.benchmark.measured", fps(result.avgFps()), fps(result.onePercentLowFps())), 0xFFFFFFFF));
		}
		out.add(new Line(Component.translatable("rigtune.benchmark.target", Math.round(outcome.targetFps())), 0xFFFFFFFF));
		if (result != null) {
			String p99 = String.format(Locale.ROOT, "%.1f", result.p99FrameMs());
			// Measure's headline already has the averages.
			out.add(new Line(tune()
					? Component.translatable("rigtune.benchmark.result", fps(result.avgFps()), fps(result.onePercentLowFps()), p99, result.repeats())
					: Component.translatable("rigtune.benchmark.result.detail", p99, result.repeats()), COLOR_LABEL));
			if (BenchmarkMath.noisy(result.cv())) {
				out.add(new Line(Component.translatable("rigtune.benchmark.noisy",
						String.format(Locale.ROOT, "%.0f%%", result.cv() * 100)), COLOR_WARN));
			}
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
		} else if (outcome.record() != null && BenchmarkRecord.BEFORE.equals(outcome.record().phase())) {
			out.add(new Line(Component.translatable("rigtune.benchmark.saved_before"), COLOR_LABEL));
		}
		for (TrendText.Line line : trendLines(trend, ZoneId.systemDefault(), BenchmarkTrendLines::describe)) {
			out.add(new Line(Texts.component(line.text()), BenchmarkTrendLines.color(line.tone())));
		}
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
		// docs/v0.3/SPEC.md E-M1: a step measured before its terrain had loaded doesn't count, whatever its FPS.
		if (tune() && rows.stream().anyMatch(m -> !m.complete())) {
			out.add(new Line(Component.translatable("rigtune.benchmark.incomplete"), COLOR_WARN));
		}
		if (session.deadlineHit()) {
			out.add(new Line(Component.translatable("rigtune.benchmark.deadline"), COLOR_WARN));
		}
		return out;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		int y = 22;
		for (Line line : lines) {
			graphics.centeredText(font, clip(line.text()), width / 2, y, line.color());
			y += LINE;
		}
		int area = Math.min(width - 16, 460);
		int left = (width - area) / 2;
		boolean table = tune() && !rows.isEmpty();
		if (table && area >= 300) {
			int half = (area - 12) / 2;
			drawTable(graphics, left, half);
			drawChart(graphics, left + half + 12, half);
		} else if (table) {
			drawTable(graphics, left, area);
		} else if (tune()) {
			graphics.centeredText(font, Component.translatable("rigtune.benchmark.none"), width / 2, contentTop, COLOR_LABEL);
		} else {
			drawChart(graphics, left + area / 6, area * 2 / 3);
		}
	}

	private void drawTable(GuiGraphicsExtractor graphics, int left, int tableWidth) {
		int suggested = outcome.session().chosen().renderDistance();
		boolean met = outcome.result().targetMet();
		// Next to the chart the table is narrow, so the P99 column is left out.
		boolean p99 = tableWidth >= 260;
		int[] columns = p99 ? new int[]{0, tableWidth * 22 / 100, tableWidth * 44 / 100, tableWidth * 66 / 100, tableWidth * 88 / 100}
				: new int[]{0, tableWidth * 30 / 100, tableWidth * 58 / 100, tableWidth * 84 / 100};
		String[] headers = p99
				? new String[]{"rigtune.benchmark.col.rd", "rigtune.benchmark.col.avg", "rigtune.benchmark.col.low", "rigtune.benchmark.col.p99", "rigtune.benchmark.col.ok"}
				: new String[]{"rigtune.benchmark.col.rd", "rigtune.benchmark.col.avg", "rigtune.benchmark.col.low", "rigtune.benchmark.col.ok"};
		int y = contentTop;
		graphics.fill(left - 4, y - 3, left + tableWidth + 4, y + ROW - 2, 0x60000000);
		for (int i = 0; i < headers.length; i++) {
			graphics.text(font, Component.translatable(headers[i]), left + columns[i], y, COLOR_LABEL, false);
		}
		y += ROW + 2;
		int maxRows = Math.max(0, (contentBottom - y) / ROW);
		for (PlannerResult.Measurement m : rows.subList(0, Math.min(rows.size(), maxRows))) {
			boolean best = m.rd() == suggested;
			if (best) {
				graphics.fill(left - 4, y - 2, left + tableWidth + 4, y + ROW - 3, met ? 0x3000FF00 : 0x30FFD166);
			}
			int color = best ? 0xFFFFFFFF : 0xFFDDDDDD;
			graphics.text(font, Integer.toString(m.rd()), left + columns[0], y, color, false);
			graphics.text(font, fps(m.stats().avgFps()), left + columns[1], y, color, false);
			graphics.text(font, fps(m.stats().onePercentLowFps()), left + columns[2], y, color, false);
			if (p99) {
				graphics.text(font, String.format(Locale.ROOT, "%.1f", m.stats().p99FrameMs()), left + columns[3], y, color, false);
			}
			graphics.text(font, m.passed() ? "✔" : m.complete() ? "✘" : "✘*", left + columns[columns.length - 1], y,
					m.passed() ? COLOR_PASS : COLOR_FAIL, false);
			y += ROW;
		}
	}

	// The runs comparable with this one (TrendChart): bars, the 1% low and average polylines, and the usual (median) line.
	private void drawChart(GuiGraphicsExtractor graphics, int left, int chartWidth) {
		Component sceneName = Component.translatable("rigtune.benchmark.scene." + outcome.request().scene().name().toLowerCase(Locale.ROOT));
		TrendChart.draw(graphics, font, Component.translatable("rigtune.benchmark.chart.title", sceneName), chartRuns, trend == null ? null : trend.median(),
				outcome.record() == null ? null : outcome.record().id(), left, contentTop, chartWidth, contentBottom);
	}

	// The failure's own message is shown as it is (an exception's detail, kept in benchmarks.json).
	private static Component reason(String notMeasured) {
		if (SessionResult.NOT_MEASURED_DEADLINE.equals(notMeasured)) {
			return Component.translatable("rigtune.benchmark.not_measured.deadline");
		}
		String failed = SessionResult.NOT_MEASURED_FAILED;
		return Component.literal(notMeasured.startsWith(failed) ? notMeasured.substring(failed.length()) : notMeasured);
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
