package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.jvm.JvmFinding;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.launcher.LauncherAdvice;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// JVM & memory (docs/v0.4/SPEC.md 6), opened from ToolsScreen: this Java's version and vendor, the collector and whether
// it was typed, the heap, the notes on the Java arguments (flag names only, never an argument), and the fired jvm-*/ram-*
// advice with the launcher's steps. Advice only: nothing here changes a setting.
public class JvmScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int LINE = 9;
	private static final int INDENT = 12;
	private static final int COLOR_HEADING = 0xFFFFD166;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_REASON = 0xFFB8B8B8;
	private static final int COLOR_LAUNCHER = 0xFFA8E0B0;

	private final @Nullable Screen parent;
	protected final RigTuneController controller;
	private JvmReport shownJvm = JvmReport.UNAVAILABLE;
	private @Nullable Report shownReport;
	private double scroll;
	private @Nullable JvmList list;

	public JvmScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.jvm.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		shownJvm = controller.jvmReport();
		shownReport = controller.report();
		int column = Math.min(width - 32, 480);
		int footerTop = height - MARGIN / 2 - 20;
		int listTop = 32;
		list = new JvmList(listTop, Math.max(20, footerTop - 4 - listTop), column);
		populate(list, column - 10);
		addRenderableWidget(list);
		list.setScrollAmount(scroll);
		int buttonWidth = Math.min(150, column);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds((width - buttonWidth) / 2, footerTop, buttonWidth, 20).build());
	}

	// The probe finishes off the render thread, and a rescan brings a new report: show them when they arrive.
	@Override
	public void tick() {
		if (controller.jvmReport() != shownJvm || controller.report() != shownReport) {
			rebuildWidgets();
		}
	}

	@Override
	protected void rebuildWidgets() {
		if (list != null) {
			scroll = list.scrollAmount();
		}
		super.rebuildWidgets();
	}

	private void populate(JvmList target, int width) {
		JvmReport jvm = shownJvm;
		if (jvm.javaVersion() == null) {
			return;
		}
		target.heading("rigtune.jvm.section.java", width);
		target.row(Component.translatable("rigtune.jvm.java", jvm.javaVersion(), jvm.vendor() == null ? Component.translatable("rigtune.jvm.unknown")
				: Component.literal(jvm.vendor())), COLOR_TEXT, INDENT, width);
		Component collector = jvm.collectorName() == null ? Component.translatable("rigtune.jvm.unknown") : Component.literal(jvm.collectorName());
		target.row(Component.translatable("rigtune.jvm.collector", collector,
				Component.translatable(jvm.collectorTyped() ? "rigtune.jvm.collector.typed" : "rigtune.jvm.collector.default")), COLOR_TEXT, INDENT, width);
		target.row(Component.translatable("rigtune.jvm.heap", RigTuneScreen.gb(jvm.maxHeapMb()), RigTuneScreen.gb(jvm.initialHeapMb())), COLOR_TEXT,
				INDENT, width);

		target.heading("rigtune.jvm.section.findings", width);
		if (!jvm.available()) {
			target.row(Component.translatable("rigtune.jvm.unavailable"), COLOR_REASON, INDENT, width);
		} else if (jvm.findings().isEmpty()) {
			target.row(Component.translatable("rigtune.jvm.no_findings"), COLOR_REASON, INDENT, width);
		}
		for (JvmFinding finding : jvm.findings()) {
			target.row(Component.translatable("rigtune.jvm.finding", Component.literal(finding.flag()).withStyle(ChatFormatting.WHITE),
					Component.translatable(finding.kind().reasonKey())), COLOR_REASON, INDENT, width);
		}

		target.heading("rigtune.jvm.section.advice", width);
		Report report = shownReport;
		if (report == null) {
			target.row(Component.translatable("rigtune.jvm.advice_loading"), COLOR_REASON, INDENT, width);
			return;
		}
		List<Recommendation> advice = advice(report);
		if (advice.isEmpty()) {
			target.row(Component.translatable("rigtune.jvm.no_advice"), COLOR_REASON, INDENT, width);
		}
		for (Recommendation r : advice) {
			target.row(Texts.component(r.titleText()).copy().withStyle(ChatFormatting.BOLD), RigTuneScreen.accentColor(r.category()), INDENT, width);
			if (r.reason() != null && !r.reason().isBlank()) {
				target.row(Texts.component(r.reasonText()), r.category() == Category.WARNING ? 0xFFE8B0A8 : COLOR_REASON, 2 * INDENT, width);
			}
			Component line = LauncherLines.adviceLine(r, controller.launcher(), jvm);
			if (line != null) {
				target.row(line, COLOR_LAUNCHER, 2 * INDENT, width);
			}
		}
	}

	// The fired advice about Java and memory: jvm-* and ram-* ids, in the report's order.
	static List<Recommendation> advice(Report report) {
		List<Recommendation> out = new ArrayList<>();
		for (Recommendation r : report.recommendations()) {
			if (LauncherAdvice.isJvmAdvice(r) || LauncherAdvice.isRamAdvice(r)) {
				out.add(r);
			}
		}
		return out;
	}

	/** For the game tests: the report shown. */
	public JvmReport shownJvm() {
		return shownJvm;
	}

	/** For the game tests: the rows as drawn, one string per row. */
	public List<String> rowText() {
		List<String> out = new ArrayList<>();
		if (list != null) {
			for (JvmList.Row row : list.children()) {
				out.add(row.text.getString());
			}
		}
		return out;
	}

	/** For the game tests: every row's lines end inside the list. */
	public boolean rowsFit() {
		return list == null || list.children().stream().allMatch(JvmList.Row::textFits);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		graphics.centeredText(font, clip(Component.translatable("rigtune.jvm.subtitle"), width - 16), width / 2, 20, COLOR_LABEL);
		if (shownJvm.javaVersion() == null && list != null) {
			graphics.centeredText(font, Component.translatable("rigtune.jvm.checking"), width / 2, list.getY() + list.getHeight() / 2 - LINE / 2, COLOR_LABEL);
		}
	}

	private FormattedCharSequence clip(Component text, int maxWidth) {
		return font.width(text) <= maxWidth ? text.getVisualOrderText() : ComponentRenderUtils.clipText(text, font, maxWidth);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	public final class JvmList extends ContainerObjectSelectionList<JvmList.Row> {
		private final int rowWidth;
		private boolean first = true;

		JvmList(int top, int listHeight, int rowWidth) {
			super(JvmScreen.this.minecraft, JvmScreen.this.width, listHeight, top, 12);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void heading(String key, int width) {
			Row row = new Row(Component.translatable(key).withStyle(ChatFormatting.BOLD), COLOR_HEADING, 0, first ? 1 : 7, width, true);
			first = false;
			addEntry(row, row.height());
		}

		void row(Component text, int color, int indent, int width) {
			Row row = new Row(text, color, indent, 1, width, false);
			addEntry(row, row.height());
		}

		public final class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final Component text;
			private final List<FormattedCharSequence> lines;
			private final int color;
			private final int indent;
			private final int top;
			private final boolean shadow;

			Row(Component text, int color, int indent, int top, int width, boolean shadow) {
				this.text = text;
				this.lines = font.split(text, Math.max(40, width - indent));
				this.color = color;
				this.indent = indent;
				this.top = top;
				this.shadow = shadow;
			}

			int height() {
				return top + lines.size() * LINE + 1;
			}

			boolean textFits() {
				int widest = 0;
				for (FormattedCharSequence line : lines) {
					widest = Math.max(widest, font.width(line));
				}
				return indent + widest <= getContentWidth();
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int y = getContentY() + top;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, getContentX() + indent, y, color, shadow);
					y += LINE;
				}
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return List.of();
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return List.of();
			}
		}
	}
}
