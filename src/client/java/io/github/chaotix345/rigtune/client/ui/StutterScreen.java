package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Category;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.stutter.Attributor;
import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.StutterAdvisor;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterSummary;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Stutter Doctor (docs/v0.4/SPEC.md 5), opened from ToolsScreen: the session monitor's controls and the latest analysis
// (the running session's, refreshed every few seconds, or the last saved summary): a header (session length, gameplay
// time, frames, average FPS, 1 % low, spikes by severity, lost time), the time-weighted frame-time histogram, the likely
// causes with their share of the lost time and what's not explained, correlations as counts ("not measured"), the 10
// worst spikes with a confidence marker, and the advice that fired. Everything is "likely"; nothing is sent anywhere.
public class StutterScreen extends Screen {
	private static final int LINE = 10;
	private static final int ROW_GAP = 4;
	private static final int MAX_COLUMN = 420;
	private static final int COLOR_TEXT = 0xFFE0E0E0;
	private static final int COLOR_LABEL = 0xFFA8A8A8;
	private static final int COLOR_HEADING = 0xFFFFFFFF;
	private static final int COLOR_NOTE = 0xFFFFD166;
	private static final int COLOR_GOOD = 0xFF7FE07F;
	private static final int COLOR_AMBER = 0xFFFFB347;
	private static final int COLOR_BAD = 0xFFFF7A6B;
	private static final int COLOR_BAR_BG = 0x40FFFFFF;
	private static final int MIN_BAR = 30;
	private static final String[] EDGES = {"4.2", "8.3", "16.7", "33", "50", "100", "250", "1000"};
	private static final Map<String, String> CAUSES = Map.of(Attributor.GC, "rigtune.stutter.cause.gc", Attributor.CHUNK_LOAD,
			"rigtune.stutter.cause.chunk_load", Attributor.CHUNK_BUILD, "rigtune.stutter.cause.chunk_build", Attributor.TICK, "rigtune.stutter.cause.tick",
			Attributor.RENDER, "rigtune.stutter.cause.render", Attributor.UNKNOWN, "rigtune.stutter.cause.unknown");
	private static final Map<String, String> TAGS = Map.of(Attributor.WORLD_SAVE, "rigtune.stutter.tag.world_save", Attributor.DH,
			"rigtune.stutter.tag.dh", Attributor.CPU_CONTENTION, "rigtune.stutter.tag.cpu_contention", Attributor.AFTER_TELEPORT,
			"rigtune.stutter.tag.after_teleport", Attributor.MOVING_FAST, "rigtune.stutter.tag.moving_fast");

	private final @Nullable Screen parent;
	protected final RigTuneController controller;
	private StutterView view = StutterView.EMPTY;
	private @Nullable Component status;
	private @Nullable StutterList list;
	// docs/v0.4/SPEC.md 11: the row that had the keyboard focus before a rebuild, which gets it back.
	private int focusedRow = -1;
	private final List<Component> shownText = new ArrayList<>();
	private double scroll;

	public StutterScreen(@Nullable Screen parent, RigTuneController controller) {
		super(Component.translatable("rigtune.stutter.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		view = controller.stutter();
		int column = Math.min(MAX_COLUMN, width - 32);
		int x = (width - column) / 2;
		int gap = 4;
		int third = (column - 2 * gap) / 3;
		int half = (column - gap) / 2;
		int lower = height - 26;
		int upper = lower - 24;

		Button monitor = view.monitorOn()
				? Button.builder(Component.translatable("rigtune.stutter.stop"), b -> controller.setStutterMonitor(false))
						.tooltip(Tooltip.create(Component.translatable("rigtune.stutter.stop.tooltip"))).build()
				: Button.builder(Component.translatable("rigtune.stutter.start"), b -> controller.setStutterMonitor(true))
						.tooltip(Tooltip.create(Component.translatable("rigtune.stutter.start.tooltip"))).build();
		monitor.setRectangle(third, 20, x, upper);
		addRenderableWidget(monitor);
		Button pause = Button.builder(Component.translatable(view.paused() ? "rigtune.stutter.resume" : "rigtune.stutter.pause"),
				b -> controller.pauseStutterMonitor(!view.paused())).bounds(x + third + gap, upper, third, 20).build();
		pause.active = view.recording();
		addRenderableWidget(pause);
		addRenderableWidget(Button.builder(Component.translatable("rigtune.stutter.clear"), b -> controller.clearStutter())
				.tooltip(Tooltip.create(Component.translatable("rigtune.stutter.clear.tooltip"))).bounds(x + 2 * (third + gap), upper, third, 20).build());
		Button copy = Button.builder(Component.translatable("rigtune.stutter.copy"), b -> copySummary())
				.tooltip(Tooltip.create(Component.translatable("rigtune.stutter.copy.tooltip"))).bounds(x, lower, half, 20).build();
		copy.active = view.report() != null;
		addRenderableWidget(copy);
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose()).bounds(x + half + gap, lower, half, 20).build());

		int listTop = 32;
		list = new StutterList(listTop, Math.max(20, upper - 4 - listTop), column);
		populate(list, column - 12);
		list.fitColumns();
		addRenderableWidget(list);
		list.setScrollAmount(scroll);
	}

	@Override
	public void tick() {
		StutterView next = controller.stutter();
		if (!same(next, view)) {
			if (list != null) {
				scroll = list.scrollAmount();
			}
			rebuildWidgets();
		}
	}

	// docs/v0.4/SPEC.md 11 (review M1): a live session refreshes the screen every few seconds; the focused row stays.
	@Override
	protected void rebuildWidgets() {
		focusedRow = list == null ? -1 : list.focusedRow();
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

	private static boolean same(StutterView a, StutterView b) {
		return a.monitorOn() == b.monitorOn() && a.recording() == b.recording() && a.paused() == b.paused() && a.analysing() == b.analysing()
				&& a.live() == b.live() && a.report() == b.report();
	}

	private void copySummary() {
		String text = controller.stutterSummary();
		if (!text.isEmpty()) {
			minecraft.keyboardHandler.setClipboard(text);
			status = Component.translatable("rigtune.stutter.copied");
		}
	}

	public StutterView shownView() {
		return view;
	}

	// Every text the list shows (for the game tests).
	public List<Component> shownText() {
		return List.copyOf(shownText);
	}

	public @Nullable StutterList list() {
		return list;
	}

	private void populate(StutterList l, int width) {
		shownText.clear();
		String statusKey = view.recording() ? (view.paused() ? "rigtune.stutter.status.paused" : "rigtune.stutter.status.recording")
				: view.monitorOn() ? "rigtune.stutter.status.waiting" : "rigtune.stutter.status.off";
		text(l, Component.translatable(statusKey), COLOR_LABEL, width, 0);
		if (!view.recording() && view.report() != null) {
			text(l, Component.translatable("rigtune.stutter.status.saved"), COLOR_LABEL, width, 0);
		}
		StutterReport r = view.report();
		if (r == null) {
			text(l, Component.translatable(view.analysing() ? "rigtune.stutter.analysing" : "rigtune.stutter.none"), COLOR_TEXT, width, ROW_GAP);
			if (!view.monitorOn()) {
				text(l, Component.translatable("rigtune.stutter.start.tooltip"), COLOR_LABEL, width, 0);
			}
			return;
		}
		header(l, r, width);
		histogram(l, r, width);
		causes(l, r, width);
		worst(l, r, width);
		advice(l, view.advice(), width);
	}

	private void header(StutterList l, StutterReport r, int width) {
		Component time = StutterReport.BENCHMARK.equals(r.source())
				? Component.translatable("rigtune.stutter.header.time.benchmark", clock(r.gameplaySeconds()))
				: Component.translatable("rigtune.stutter.header.time.monitor", clock(r.sessionSeconds()), clock(r.gameplaySeconds()));
		text(l, time, COLOR_TEXT, width, ROW_GAP);
		text(l, Component.translatable("rigtune.stutter.header.frames", number(r.frames()), number(r.avgFps()), number(r.onePercentLowFps())), COLOR_TEXT,
				width, 0);
		StutterReport.Spikes s = r.spikes();
		text(l, Component.translatable("rigtune.stutter.header.spikes", s.total(), s.minor(), s.major(), s.severe(), s.freeze(), r.hitches(),
				String.format(Locale.ROOT, "%.1f", r.lostMs() / 1000)), COLOR_TEXT, width, 0);
		if (!r.enoughData()) {
			text(l, Component.translatable("rigtune.stutter.not_enough"), COLOR_NOTE, width, 0);
		}
		if (r.facts().gcOffsetMs() == null) {
			text(l, Component.translatable("rigtune.stutter.gc_uncalibrated"), COLOR_LABEL, width, 0);
		}
		if (!r.phaseTiming()) {
			text(l, Component.translatable("rigtune.stutter.phase_unavailable"), COLOR_LABEL, width, 0);
		}
	}

	private void histogram(StutterList l, StutterReport r, int width) {
		heading(l, "rigtune.stutter.histogram", width);
		long total = 0;
		for (long ms : r.histogramTimeMs()) {
			total += ms;
		}
		for (int i = 0; i < FrameRing.BUCKETS && i < r.histogramTimeMs().length && i < r.histogramCounts().length; i++) {
			double share = total <= 0 ? 0 : (double) r.histogramTimeMs()[i] / total;
			int color = i <= 3 ? COLOR_GOOD : i <= 5 ? COLOR_AMBER : COLOR_BAD;
			bar(l, bucketLabel(i), share, color, Component.translatable("rigtune.stutter.bucket.value", percent(share), number(r.histogramCounts()[i])));
		}
	}

	private static Component bucketLabel(int i) {
		if (i == 0) {
			return Component.translatable("rigtune.stutter.bucket.below", EDGES[0]);
		}
		if (i >= EDGES.length) {
			return Component.translatable("rigtune.stutter.bucket.above", EDGES[EDGES.length - 1]);
		}
		return Component.translatable("rigtune.stutter.bucket.range", EDGES[i - 1], EDGES[i]);
	}

	private void causes(StutterList l, StutterReport r, int width) {
		heading(l, "rigtune.stutter.causes", width);
		if (r.spikes().total() == 0) {
			text(l, Component.translatable("rigtune.stutter.causes.none"), COLOR_LABEL, width, 0);
			return;
		}
		for (String cause : Attributor.CAUSES) {
			Double share = r.causes().get(cause);
			if (share == null) {
				continue;
			}
			bar(l, Component.translatable(CAUSES.get(cause)), share, cause.equals(Attributor.UNKNOWN) ? COLOR_LABEL : COLOR_AMBER, Component.literal(percent(share)));
		}
		for (String tag : Attributor.TAGS) {
			Integer n = r.tags().get(tag);
			if (n != null && n > 0) {
				text(l, Component.translatable("rigtune.stutter.tag", n, r.spikes().total(), Component.translatable(TAGS.get(tag))), COLOR_LABEL, width, 0);
			}
		}
	}

	private void worst(StutterList l, StutterReport r, int width) {
		if (r.worst().isEmpty()) {
			return;
		}
		heading(l, "rigtune.stutter.worst", width);
		for (StutterReport.Worst w : r.worst()) {
			text(l, Component.translatable("rigtune.stutter.worst.row", clock(w.t()), number(w.ms()), String.format(Locale.ROOT, "%.1f", w.baseMs()),
					notes(w.causes())), COLOR_TEXT, width, 0);
		}
	}

	// "gc:high:FULL" -> "Garbage collection ●●● full GC"; context tags get no marker.
	static Component notes(List<String> notes) {
		MutableComponent out = Component.empty();
		boolean first = true;
		for (String text : notes) {
			Attributor.Note note = Attributor.Note.parse(text);
			String key = CAUSES.containsKey(note.name()) ? CAUSES.get(note.name()) : TAGS.get(note.name());
			if (key == null) {
				continue;
			}
			if (!first) {
				out.append(Component.literal(" · "));
			}
			first = false;
			out.append(Component.translatable(key));
			if (note.confidence() != null) {
				out.append(Component.literal(switch (note.confidence()) {
					case HIGH -> " ●●●";
					case MEDIUM -> " ●●";
					case LOW -> " ●";
				}));
			}
			flag(out, note.full(), "rigtune.stutter.flag.full");
			flag(out, note.explicit(), "rigtune.stutter.flag.explicit");
			flag(out, note.stall(), "rigtune.stutter.flag.stall");
		}
		return first ? Component.translatable("rigtune.stutter.worst.unexplained") : out;
	}

	private static void flag(MutableComponent out, boolean set, String key) {
		if (set) {
			out.append(Component.literal(" ")).append(Component.translatable(key));
		}
	}

	private void advice(StutterList l, List<StutterAdvisor.Fired> advice, int width) {
		heading(l, "rigtune.stutter.advice", width);
		if (advice.isEmpty()) {
			text(l, Component.translatable("rigtune.stutter.advice.none"), COLOR_LABEL, width, 0);
			return;
		}
		LauncherInfo launcher = controller.launcher();
		for (StutterAdvisor.Fired f : advice) {
			text(l, Component.literal(f.title()).withStyle(ChatFormatting.BOLD), f.info() ? COLOR_TEXT : COLOR_NOTE, width, ROW_GAP);
			if (!f.text().isEmpty()) {
				text(l, Component.literal(f.text()), COLOR_LABEL, width, 0);
			}
			if (f.memory()) {
				Component steps = LauncherLines.adviceLine(Recommendation.of(f.recommendationId(), Category.ADVICE, f.impact(), Text.literal(f.title()),
						Text.literal(f.text()), new Action.None(), false), launcher);
				if (steps != null) {
					text(l, steps, COLOR_LABEL, width, 0);
				}
			}
		}
	}

	private void heading(StutterList l, String key, int width) {
		text(l, Component.translatable(key).withStyle(ChatFormatting.BOLD), COLOR_HEADING, width, 7);
	}

	private void text(StutterList l, Component text, int color, int width, int top) {
		shownText.add(text);
		l.add(new TextRow(text, font.split(text, Math.max(40, width)), color, top));
	}

	private void bar(StutterList l, Component label, double share, int color, Component value) {
		shownText.add(label);
		shownText.add(value);
		l.add(new BarRow(label, share, color, value));
	}

	// "1,234" for counts and large rates, "61.2" for small ones (numbers are data, not text).
	private static String number(double value) {
		return value >= 100 || value == Math.rint(value) ? String.format(Locale.ROOT, "%,d", Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
	}

	private static String percent(double share) {
		return String.format(Locale.ROOT, "%d %%", Math.round(share * 100));
	}

	private static String clock(double seconds) {
		return StutterSummary.clock(seconds);
	}

	// BenchmarkResultScreen's line (SPEC 5: "2 spikes; likely causes: garbage collection"); null without a capture.
	public static @Nullable Component benchmarkLine(@Nullable StutterReport r) {
		if (r == null) {
			return null;
		}
		int spikes = r.spikes().total();
		if (spikes == 0) {
			return Component.translatable("rigtune.stutter.benchmark.none");
		}
		MutableComponent causes = Component.empty();
		boolean any = false;
		for (String cause : Attributor.CAUSES) {
			Double share = r.causes().get(cause);
			if (share != null && share > 0 && !cause.equals(Attributor.UNKNOWN)) {
				if (any) {
					causes.append(Component.literal(", "));
				}
				causes.append(Component.translatable(CAUSES.get(cause))).append(Component.literal(" " + percent(share)));
				any = true;
			}
		}
		return any ? Component.translatable("rigtune.stutter.benchmark.spikes", spikes, causes)
				: Component.translatable("rigtune.stutter.benchmark.unexplained", spikes);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, 0xFFFFFFFF);
		if (status != null) {
			graphics.centeredText(font, status, width / 2, 20, Palette.of(COLOR_GOOD));
		}
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	// docs/v0.4/SPEC.md 11: every row is a Tab/arrow stop and narrates what it shows.
	abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
		abstract int height();

		abstract RowFocus focus();

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(focus());
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(focus());
		}
	}

	private final class TextRow extends Row {
		private final List<FormattedCharSequence> lines;
		private final int color;
		private final int top;
		private final RowFocus focus;

		TextRow(Component text, List<FormattedCharSequence> lines, int color, int top) {
			this.lines = lines;
			this.color = color;
			this.top = top;
			this.focus = new RowFocus(this, text);
		}

		@Override
		RowFocus focus() {
			return focus;
		}

		@Override
		int height() {
			return top + lines.size() * LINE + 1;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			int y = getContentY() + top;
			for (FormattedCharSequence line : lines) {
				graphics.text(font, line, getContentX(), y, Palette.of(color), false);
				y += LINE;
			}
		}
	}

	// A label, a bar for the share and the value to its right, on one line.
	private final class BarRow extends Row {
		private final Component label;
		private final double share;
		private final int color;
		private final Component value;
		private final RowFocus focus;

		BarRow(Component label, double share, int color, Component value) {
			this.label = label;
			this.share = Math.max(0, Math.min(1, share));
			this.color = color;
			this.value = value;
			this.focus = new RowFocus(this, RowFocus.join(label, value));
		}

		@Override
		RowFocus focus() {
			return focus;
		}

		@Override
		int height() {
			return LINE + 1;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			StutterList l = list;
			int x = getContentX();
			int y = getContentY();
			int width = getContentWidth();
			int labelWidth = l == null ? width / 3 : l.labelColumn;
			int valueWidth = l == null ? width / 3 : l.valueColumn;
			int barX = x + labelWidth + 4;
			int barWidth = Math.max(MIN_BAR, width - labelWidth - valueWidth - 8);
			graphics.text(font, font.substrByWidth(label, labelWidth).getString(), x, y, Palette.of(COLOR_TEXT), false);
			graphics.fill(barX, y + 1, barX + barWidth, y + 8, Palette.of(COLOR_BAR_BG));
			graphics.fill(barX, y + 1, barX + (int) Math.round(barWidth * share), y + 8, Palette.of(color));
			graphics.text(font, font.substrByWidth(value, valueWidth).getString(), barX + barWidth + 4, y, Palette.of(COLOR_LABEL), false);
		}

		boolean fits() {
			StutterList l = list;
			return l != null && font.width(label) <= l.labelColumn && font.width(value) <= l.valueColumn;
		}
	}

	public final class StutterList extends RowList<Row> {
		private final int rowWidth;
		private int labelColumn;
		private int valueColumn;

		StutterList(int top, int listHeight, int rowWidth) {
			super(StutterScreen.this.minecraft, StutterScreen.this.width, listHeight, top, LINE + 1);
			this.rowWidth = rowWidth;
		}

		void add(Row row) {
			addEntry(row, row.height());
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		public int rows() {
			return children().size();
		}

		// The bars line up: the label and value columns are as wide as their widest text, within 40 % of the row each, so
		// the bar keeps at least MIN_BAR px.
		void fitColumns() {
			int width = getRowWidth() - 12;
			int labels = 0;
			int values = 0;
			for (Row row : children()) {
				if (row instanceof BarRow bar) {
					labels = Math.max(labels, font.width(bar.label));
					values = Math.max(values, font.width(bar.value));
				}
			}
			int cap = Math.max(20, (width - MIN_BAR - 8) / 2);
			labelColumn = Math.min(labels, cap);
			valueColumn = Math.min(values, cap);
		}

		// Every bar row's label and value fit their columns (for the game test).
		public boolean barsFit() {
			return children().stream().noneMatch(row -> row instanceof BarRow bar && !bar.fits());
		}
	}
}
