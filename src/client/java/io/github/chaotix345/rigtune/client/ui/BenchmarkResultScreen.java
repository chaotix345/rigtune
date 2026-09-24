package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.benchmark.PlannerResult;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BenchmarkResultScreen extends Screen {
	private static final int ROW = 12;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_PASS = 0xFF7FE07F;
	private static final int COLOR_FAIL = 0xFFFF7A6B;
	private static final int COLOR_WARN = 0xFFFFD166;

	private final @Nullable Screen parent;
	private final BenchmarkController.Outcome outcome;
	private final List<PlannerResult.Measurement> rows;
	private int left;
	private int tableWidth;
	private int tableTop;

	public BenchmarkResultScreen(@Nullable Screen parent, BenchmarkController.Outcome outcome) {
		super(Component.translatable("rigtune.benchmark.title"));
		this.parent = parent;
		this.outcome = outcome;
		this.rows = outcome.result().measurements().stream().sorted(Comparator.comparingInt(PlannerResult.Measurement::rd)).toList();
	}

	public BenchmarkController.Outcome outcome() {
		return outcome;
	}

	@Override
	protected void init() {
		tableWidth = Math.min(width - 32, 360);
		left = (width - tableWidth) / 2;
		tableTop = 70;
		int best = outcome.result().suggestedRd();
		int buttonWidth = Math.min(150, (tableWidth - 4) / 2);
		int y = height - 28;
		addRenderableWidget(Button.builder(Component.translatable("rigtune.benchmark.use", best), b -> {
			SettingsBridge.applyVanilla(Map.of("vanilla.renderDistance", Integer.toString(best)));
			onClose();
		}).bounds(width / 2 - buttonWidth - 2, y, buttonWidth, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("rigtune.benchmark.keep", outcome.originalRd()), b -> onClose())
				.bounds(width / 2 + 2, y, buttonWidth, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		PlannerResult result = outcome.result();
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 12, 0xFFFFFFFF);
		int suggested = result.suggestedRd();
		Component summary = Component.translatable(result.targetMet() ? "rigtune.benchmark.met" : "rigtune.benchmark.missed",
				Component.literal(Integer.toString(suggested)).withStyle(ChatFormatting.BOLD));
		graphics.centeredText(font, clip(summary), width / 2, 28, result.targetMet() ? COLOR_PASS : COLOR_WARN);
		graphics.centeredText(font, clip(Component.translatable("rigtune.benchmark.target", Math.round(outcome.targetFps()))), width / 2, 40, 0xFFFFFFFF);
		graphics.centeredText(font, clip(Component.literal(result.reason())), width / 2, 52, COLOR_LABEL);

		int[] columns = {0, tableWidth * 22 / 100, tableWidth * 44 / 100, tableWidth * 66 / 100, tableWidth * 88 / 100};
		String[] headers = {"rigtune.benchmark.col.rd", "rigtune.benchmark.col.avg", "rigtune.benchmark.col.low", "rigtune.benchmark.col.p99", "rigtune.benchmark.col.ok"};
		int y = tableTop;
		graphics.fill(left - 4, y - 3, left + tableWidth + 4, y + ROW - 2, 0x60000000);
		for (int i = 0; i < headers.length; i++) {
			graphics.text(font, Component.translatable(headers[i]), left + columns[i], y, COLOR_LABEL, false);
		}
		y += ROW + 2;
		int maxRows = Math.max(0, (height - 36 - y) / ROW);
		for (PlannerResult.Measurement m : rows.subList(0, Math.min(rows.size(), maxRows))) {
			boolean best = m.rd() == suggested;
			if (best) {
				graphics.fill(left - 4, y - 2, left + tableWidth + 4, y + ROW - 3, result.targetMet() ? 0x3000FF00 : 0x30FFD166);
			}
			int color = best ? 0xFFFFFFFF : 0xFFDDDDDD;
			graphics.text(font, Integer.toString(m.rd()), left + columns[0], y, color, false);
			graphics.text(font, fps(m.stats().avgFps()), left + columns[1], y, color, false);
			graphics.text(font, fps(m.stats().onePercentLowFps()), left + columns[2], y, color, false);
			graphics.text(font, String.format(Locale.ROOT, "%.1f", m.stats().p99FrameMs()), left + columns[3], y, color, false);
			graphics.text(font, m.passed() ? "✔" : "✘", left + columns[4], y, m.passed() ? COLOR_PASS : COLOR_FAIL, false);
			y += ROW;
		}
		if (rows.isEmpty()) {
			graphics.centeredText(font, Component.translatable("rigtune.benchmark.none"), width / 2, y, COLOR_LABEL);
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
