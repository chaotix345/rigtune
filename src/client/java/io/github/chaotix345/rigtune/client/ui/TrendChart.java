package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

// The benchmark chart (docs/v0.4/SPEC.md 7): comparable runs only, oldest first, at most BenchmarkTrend.MAX_RUNS; two
// bars per run (average and 1 % low) scaled to the highest value shown, a thin polyline through each series, the flat
// median line ("your usual") when there is one, and the first and last run's day. Painted, not a widget (like the
// v0.3 chart; its accessibility rebuild is deferred to v0.5).
final class TrendChart {
	static final int COLOR_AVG = 0xFF5B8DD6;
	static final int COLOR_LOW = 0xFF7FE07F;
	private static final int COLOR_AVG_LINE = 0xFFB4CDF2;
	private static final int COLOR_LOW_LINE = 0xFFD2F7D2;
	private static final int COLOR_MEDIAN = 0xFFFFD166;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int LINE = 11;

	private TrendChart() {
	}

	// Returns false when there's no room (or nothing) to draw.
	static boolean draw(GuiGraphicsExtractor graphics, Font font, Component title, List<BenchmarkRecord> runs, @Nullable Double median,
			@Nullable String highlightId, int left, int top, int chartWidth, int bottom) {
		List<BenchmarkRecord> shown = runs.stream().filter(r -> r.result() != null).toList();
		if (shown.isEmpty() || bottom - top < 44 || chartWidth < 60) {
			return false;
		}
		double max = 1;
		for (BenchmarkRecord run : shown) {
			max = Math.max(max, Math.max(run.result().avgFps(), run.result().onePercentLowFps()));
		}
		graphics.text(font, font.width(title) <= chartWidth ? title.getVisualOrderText() : ComponentRenderUtils.clipText(title, font, chartWidth), left, top,
				Palette.of(COLOR_LABEL), false);
		int legendY = top + LINE;
		int x = legend(graphics, font, left, legendY, Palette.of(COLOR_AVG), Component.translatable("rigtune.benchmark.chart.avg"));
		x = legend(graphics, font, x, legendY, Palette.of(COLOR_LOW), Component.translatable("rigtune.benchmark.chart.low"));
		Component maxLabel = Component.translatable("rigtune.benchmark.chart.max", fps(max));
		graphics.text(font, maxLabel, x, legendY, Palette.of(COLOR_LABEL), false);
		x += font.width(maxLabel) + 8;
		if (median != null) {
			Component usual = Component.translatable("rigtune.benchmark.trend.chart.median", fps(median));
			if (x + 9 + font.width(usual) <= left + chartWidth) {
				graphics.fill(x, legendY + 3, x + 6, legendY + 4, Palette.of(COLOR_MEDIAN));
				graphics.text(font, usual, x + 9, legendY, Palette.of(COLOR_LABEL), false);
			}
		}

		int barsTop = legendY + LINE + 2;
		int baseline = bottom - LINE;
		int slot = Math.min(24, chartWidth / BenchmarkTrend.MAX_RUNS);
		int barWidth = Math.max(1, slot / 2 - 1);
		int barsHeight = baseline - barsTop;
		int[] avgY = new int[shown.size()];
		int[] lowY = new int[shown.size()];
		for (int i = 0; i < shown.size(); i++) {
			BenchmarkRecord run = shown.get(i);
			int barX = left + i * slot;
			if (Objects.equals(run.id(), highlightId)) {
				graphics.fill(barX - 1, barsTop - 1, barX + slot - 1, baseline, Palette.of(0x30FFFFFF));
			}
			int avgHeight = (int) Math.round(barsHeight * run.result().avgFps() / max);
			int lowHeight = (int) Math.round(barsHeight * run.result().onePercentLowFps() / max);
			graphics.fill(barX, baseline - avgHeight, barX + barWidth, baseline, Palette.of(COLOR_AVG));
			graphics.fill(barX + barWidth + 1, baseline - lowHeight, barX + 2 * barWidth + 1, baseline, Palette.of(COLOR_LOW));
			avgY[i] = baseline - avgHeight;
			lowY[i] = baseline - lowHeight;
		}
		polyline(graphics, left + barWidth / 2, slot, avgY, Palette.of(COLOR_AVG_LINE));
		polyline(graphics, left + barWidth + 1 + barWidth / 2, slot, lowY, Palette.of(COLOR_LOW_LINE));
		if (median != null) {
			int y = baseline - (int) Math.round(barsHeight * median / max);
			for (int dash = left; dash < left + shown.size() * slot; dash += 4) {
				graphics.fill(dash, y, Math.min(dash + 2, left + shown.size() * slot), y + 1, Palette.of(COLOR_MEDIAN));
			}
		}
		graphics.fill(left, baseline, left + shown.size() * slot, baseline + 1, Palette.of(COLOR_LABEL));
		String first = BenchmarkResultScreen.chartDate(shown.getFirst().createdAt(), ZoneId.systemDefault());
		graphics.text(font, first, left, baseline + 2, Palette.of(COLOR_LABEL), false);
		if (shown.size() > 1) {
			String last = BenchmarkResultScreen.chartDate(shown.getLast().createdAt(), ZoneId.systemDefault());
			int lastX = Math.max(left + font.width(first) + 6, left + shown.size() * slot - font.width(last));
			graphics.text(font, last, lastX, baseline + 2, Palette.of(COLOR_LABEL), false);
		}
		return true;
	}

	private static int legend(GuiGraphicsExtractor graphics, Font font, int x, int y, int color, Component label) {
		graphics.fill(x, y + 1, x + 6, y + 7, color);
		graphics.text(font, label, x + 9, y, Palette.of(COLOR_LABEL), false);
		return x + 9 + font.width(label) + 8;
	}

	// A 1 px line through the points, one column at a time.
	private static void polyline(GuiGraphicsExtractor graphics, int firstX, int step, int[] ys, int color) {
		for (int i = 1; i < ys.length; i++) {
			int x0 = firstX + (i - 1) * step;
			int previous = ys[i - 1];
			for (int dx = 1; dx <= step; dx++) {
				int y = ys[i - 1] + (int) Math.round((ys[i] - ys[i - 1]) * (double) dx / step);
				graphics.fill(x0 + dx - 1, Math.min(previous, y), x0 + dx, Math.max(previous, y) + 1, color);
				previous = y;
			}
		}
	}

	private static String fps(double value) {
		return Long.toString(Math.round(value));
	}
}
