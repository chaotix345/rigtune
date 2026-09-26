package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.benchmark.BenchmarkStore;
import io.github.chaotix345.rigtune.client.benchmark.KeepSettings;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.stutter.StutterHooks;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkMath;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.Knobs;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import io.github.chaotix345.rigtune.core.benchmark.SessionResult;
import io.github.chaotix345.rigtune.core.benchmark.ShaderAdvice;
import io.github.chaotix345.rigtune.core.benchmark.Step;
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
import java.util.Objects;
import java.util.OptionalInt;

public class BenchmarkResultScreen extends Screen {
	private static final int ROW = 12;
	private static final int LINE = 11;
	private static final int CHART_RUNS = 10;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_PASS = 0xFF7FE07F;
	private static final int COLOR_FAIL = 0xFFFF7A6B;
	private static final int COLOR_WARN = 0xFFFFD166;
	private static final int COLOR_AVG = 0xFF5B8DD6;
	private static final int COLOR_LOW = 0xFF7FE07F;

	private record Line(Component text, int color) {
	}

	private final @Nullable Screen parent;
	private final BenchmarkController.Outcome outcome;
	private final List<PlannerResult.Measurement> rows;
	private final List<BenchmarkRecord> chartRuns;
	private List<Line> lines = List.of();
	private int contentTop;
	private int contentBottom;

	public BenchmarkResultScreen(@Nullable Screen parent, BenchmarkController.Outcome outcome) {
		super(Component.translatable("rigtune.benchmark.title"));
		this.parent = parent;
		this.outcome = outcome;
		this.rows = outcome.result().measurements().stream().sorted(Comparator.comparingInt(PlannerResult.Measurement::rd)).toList();
		this.chartRuns = BenchmarkStore.history().chart(outcome.request().scene().name(), HardwareProbe.minecraftVersion(), CHART_RUNS);
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

	// v0.4 (docs/v0.4/SPEC.md 5): the Stutter Doctor's line for the benchmark's sweeps ("2 spikes; likely causes: ...").
	private static void stutterLine(List<Line> out) {
		Component line = StutterScreen.benchmarkLine(StutterHooks.lastBenchmark());
		if (line != null) {
			out.add(new Line(line, COLOR_LABEL));
		}
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
		} else if (outcome.record() != null && BenchmarkRecord.BEFORE.equals(outcome.record().phase())) {
			out.add(new Line(Component.translatable("rigtune.benchmark.saved_before"), COLOR_LABEL));
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

	// The last runs of this scene, two bars each (average and 1% low), scaled to the highest value shown.
	private void drawChart(GuiGraphicsExtractor graphics, int left, int chartWidth) {
		int top = contentTop;
		int bottom = contentBottom;
		if (chartRuns.isEmpty() || bottom - top < 44 || chartWidth < 60) {
			return;
		}
		double max = 1;
		for (BenchmarkRecord run : chartRuns) {
			max = Math.max(max, Math.max(run.result().avgFps(), run.result().onePercentLowFps()));
		}
		Component sceneName = Component.translatable("rigtune.benchmark.scene." + outcome.request().scene().name().toLowerCase(Locale.ROOT));
		graphics.text(font, Component.translatable("rigtune.benchmark.chart.title", sceneName), left, top, COLOR_LABEL, false);
		int legendY = top + LINE;
		graphics.fill(left, legendY + 1, left + 6, legendY + 7, COLOR_AVG);
		Component avg = Component.translatable("rigtune.benchmark.chart.avg");
		graphics.text(font, avg, left + 9, legendY, COLOR_LABEL, false);
		int lowX = left + 9 + font.width(avg) + 8;
		graphics.fill(lowX, legendY + 1, lowX + 6, legendY + 7, COLOR_LOW);
		Component low = Component.translatable("rigtune.benchmark.chart.low");
		graphics.text(font, low, lowX + 9, legendY, COLOR_LABEL, false);
		graphics.text(font, Component.translatable("rigtune.benchmark.chart.max", fps(max)), lowX + 9 + font.width(low) + 8, legendY, COLOR_LABEL, false);

		int barsTop = legendY + LINE + 2;
		int baseline = bottom - LINE;
		int slot = Math.min(24, chartWidth / CHART_RUNS);
		int barWidth = Math.max(1, slot / 2 - 1);
		int barsHeight = baseline - barsTop;
		String currentId = outcome.record() == null ? null : outcome.record().id();
		for (int i = 0; i < chartRuns.size(); i++) {
			BenchmarkRecord run = chartRuns.get(i);
			int x = left + i * slot;
			if (Objects.equals(run.id(), currentId)) {
				graphics.fill(x - 1, barsTop - 1, x + slot - 1, baseline, 0x30FFFFFF);
			}
			int avgHeight = (int) Math.round(barsHeight * run.result().avgFps() / max);
			int lowHeight = (int) Math.round(barsHeight * run.result().onePercentLowFps() / max);
			graphics.fill(x, baseline - avgHeight, x + barWidth, baseline, COLOR_AVG);
			graphics.fill(x + barWidth + 1, baseline - lowHeight, x + 2 * barWidth + 1, baseline, COLOR_LOW);
		}
		graphics.fill(left, baseline, left + chartRuns.size() * slot, baseline + 1, COLOR_LABEL);
		String first = date(chartRuns.getFirst());
		graphics.text(font, first, left, baseline + 2, COLOR_LABEL, false);
		if (chartRuns.size() > 1) {
			String last = date(chartRuns.getLast());
			int lastX = Math.max(left + font.width(first) + 6, left + chartRuns.size() * slot - font.width(last));
			graphics.text(font, last, lastX, baseline + 2, COLOR_LABEL, false);
		}
	}

	// The failure's own message is shown as it is (an exception's detail, kept in benchmarks.json).
	private static Component reason(String notMeasured) {
		if (SessionResult.NOT_MEASURED_DEADLINE.equals(notMeasured)) {
			return Component.translatable("rigtune.benchmark.not_measured.deadline");
		}
		String failed = SessionResult.NOT_MEASURED_FAILED;
		return Component.literal(notMeasured.startsWith(failed) ? notMeasured.substring(failed.length()) : notMeasured);
	}

	private static String date(BenchmarkRecord run) {
		return chartDate(run.createdAt(), ZoneId.systemDefault());
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
