package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.benchmark.TrendText;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

// The benchmark trend in the client (docs/v0.4/SPEC.md 7): colours for TrendText's tones, History's own descriptions of
// change rows, and the tier badge's "last benchmark" line with its "needs a rerun" marker (external review §2).
public final class BenchmarkTrendLines {
	static final int COLOR_NORMAL = 0xFFA8A8A8;
	static final int COLOR_GOOD = 0xFF7FE07F;
	static final int COLOR_WARNING = 0xFFFFD166;
	static final int COLOR_BAD = 0xFFFF7A6B;
	private static final int TOOLTIP_WIDTH = 240;

	// The badge's last-benchmark line, worked out once per badge (RigTuneScreen makes a new badge on every init), never per frame.
	private static @Nullable Component cachedFor;
	private static @Nullable Component cachedLine;

	private BenchmarkTrendLines() {
	}

	public static int color(TrendText.Tone tone) {
		return switch (tone) {
			case NORMAL -> COLOR_NORMAL;
			case GOOD -> COLOR_GOOD;
			case WARNING -> COLOR_WARNING;
			case BAD -> COLOR_BAD;
		};
	}

	// A change row as the History screen words it.
	public static Text describe(HistoryModel.Change change) {
		return Text.literal(HistoryScreen.describe(change).getString());
	}

	// "Last benchmark: 1% low 543 FPS · RD 12 · SD 8 · 2560×1440 · shaders off · 2026-09-24", then "Needs a rerun (changed
	// since: resolution)" when the game no longer matches it; null without a benchmark. Also the share report's source.
	public static @Nullable Component lastBenchmark(RigTuneController controller) {
		BenchmarkTrend.View view = controller.benchmarkTrend(null);
		Text last = TrendText.last(view.last(), ZoneId.systemDefault());
		if (last == null) {
			return null;
		}
		MutableComponent out = Texts.component(last).withStyle(s -> s.withColor(Palette.of(COLOR_NORMAL)));
		Text rerun = TrendText.rerun(view.stale());
		if (rerun != null) {
			out.append(CommonComponents.NEW_LINE).append(Texts.component(rerun).withStyle(s -> s.withColor(Palette.of(COLOR_WARNING))));
		}
		return out;
	}

	// The tier badge's one tooltip while the mouse is over its text: the tier part's lines (extraLines, WS-A), then the
	// last-benchmark line. x, y: where the badge is drawn.
	public static void badgeTooltip(GuiGraphicsExtractor graphics, Font font, RigTuneController controller, @Nullable Component badge, int x, int y,
			int mouseX, int mouseY, List<Component> extraLines) {
		if (badge == null || mouseX < x || mouseX >= x + font.width(badge) || mouseY < y || mouseY >= y + 9) {
			return;
		}
		if (cachedFor != badge) {
			cachedLine = lastBenchmark(controller);
			cachedFor = badge;
		}
		List<Component> lines = new ArrayList<>(extraLines);
		if (cachedLine != null) {
			lines.add(cachedLine);
		}
		if (lines.isEmpty()) {
			return;
		}
		MutableComponent tooltip = Component.empty();
		for (int i = 0; i < lines.size(); i++) {
			if (i > 0) {
				tooltip.append(CommonComponents.NEW_LINE);
			}
			tooltip.append(lines.get(i));
		}
		// Split into lines (the joins and the rerun line are line breaks); a single Component would draw them as one line.
		graphics.setTooltipForNextFrame(font, font.split(tooltip, TOOLTIP_WIDTH), mouseX, mouseY);
	}
}
