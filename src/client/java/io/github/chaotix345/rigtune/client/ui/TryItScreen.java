package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.benchmark.BenchmarkController;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest.Scene;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.tryit.Triable;
import io.github.chaotix345.rigtune.core.tryit.TryIt;
import io.github.chaotix345.rigtune.core.tryit.TryItText;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// docs/v0.5/SPEC.md 6 (C09; docs/research/v0.5/feature-try-it.md §2.7): one screen for every stage of a Try it. From
// Preview (rec set, no try open) it's the intro: the change, what happens, where (a NOW key offers here or the benchmark
// world), and [Start] [Cancel]. Otherwise it shows controller.tryIt(): the change, the steps so far, the stage's line or
// the verdict with its causes and caveats, and at most 3 buttons (TryItView.actions()). Every line wraps (none is cut). A RowList whose rows are Tab stops that narrate their
// text (X6). Revert and Cancel try open History's Undo this for the try's entry; coming back derives the stage again, and
// the buttons stay inactive until it's done (review M5: no Keep on a view from before the undo; the screen rebuilds
// whenever the view changes). A Start that couldn't be recorded shows why (the view's note). Esc goes back to where it
// was opened from; the try stays open.
public class TryItScreen extends Screen {
	private static final int LINE = 9;
	private static final int TOP = 22;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_GOOD = 0xFF7FE07F;
	private static final int COLOR_BAD = 0xFFFF7A6B;
	private static final int COLOR_WARNING = 0xFFFFD166;
	private static final int COLOR_NOTE = 0xFFA8A8A8;

	private final @Nullable Screen parent;
	private final RigTuneController controller;
	private final @Nullable Recommendation rec;
	private @Nullable Scene scene;
	private TryItView shown = TryItView.EMPTY;
	private @Nullable Component status;
	private @Nullable Component change;
	private @Nullable Lines list;
	private int focusedRow = -1;
	// Back from Undo this: the view shown predates it until the derive asked for in init() replaces it (2 s at most; Keep
	// itself derives first anyway).
	private boolean deriving;
	private int derivingTicks;
	private final List<Button> footer = new ArrayList<>();

	// rec: the Preview's ticked setting (the intro), or null to show the open try.
	public TryItScreen(@Nullable Screen parent, RigTuneController controller, @Nullable Recommendation rec) {
		super(Texts.component(TryItText.title()));
		this.parent = parent;
		this.controller = controller;
		this.rec = rec;
	}

	// The rows' text as shown (game tests).
	public List<String> rowText() {
		List<String> out = new ArrayList<>();
		if (list != null) {
			for (Lines.Row row : list.children()) {
				out.add(row.text.getString());
			}
		}
		return out;
	}

	public List<Button> footer() {
		return List.copyOf(footer);
	}

	public TryItView shown() {
		return shown;
	}

	// The buttons wait for the derive after Undo this (game tests).
	public boolean deriving() {
		return deriving;
	}

	private boolean intro() {
		return rec != null && shown.tryIt() == null && rec.action() instanceof Action.SetSetting;
	}

	@Override
	protected void init() {
		shown = controller.tryIt();
		footer.clear();
		int column = Math.min(width - 32, 480);
		int top = TOP;
		List<TryItText.Line> lines;
		if (intro()) {
			Action.SetSetting set = (Action.SetSetting) rec.action();
			change = Texts.component(TryItText.change(set.key(), set.currentValue(), set.newValue(), controller.settingLabels()));
			Triable.Result kind = Triable.check(rec, new Triable.Context(key -> true, minecraft.level != null, false, false, false, s -> false, false,
					true, true, true));
			TryIt.Kind tried = kind.kind() == null ? TryIt.Kind.NOW : kind.kind();
			if (scene == null || !Triable.scenes(tried).contains(scene)) {
				scene = Triable.defaultScene(tried, minecraft.level != null);
			}
			if (tried == TryIt.Kind.NOW) {
				int buttonWidth = Math.min(200, width - 16);
				top += 4;
				addRenderableWidget(CycleButton.builder((Scene s) -> Component.translatable(s == Scene.CURRENT ? "rigtune.benchmark.scene.current"
								: "rigtune.benchmark.scene.benchmark_world"), scene)
						.withValues(Triable.scenes(tried))
						.create((width - buttonWidth) / 2, TOP, buttonWidth, 20, Component.translatable("rigtune.benchmark.menu.scene"), (button, value) -> {
							scene = value;
							status = null;
							rebuildWidgets();
						}));
				top += 24;
			}
			lines = new ArrayList<>(TryItText.intro(tried, scene, set.key()));
			if (shown.note() != null) {
				lines.addFirst(new TryItText.Line(shown.note(), TryItText.Tone.WARNING));
			}
			introFooter();
		} else {
			TryIt t = shown.tryIt();
			change = t == null ? null : Texts.component(TryItText.change(t, controller.settingLabels()));
			lines = TryItText.lines(shown, controller.settingLabels());
			stageFooter();
		}
		int footerTop = height - 28;
		list = new Lines(top, Math.max(20, footerTop - 4 - top), column);
		if (change != null) {
			list.add(change, COLOR_NOTE);
		}
		if (status != null) {
			list.add(status, COLOR_WARNING);
		}
		for (TryItText.Line line : lines) {
			list.add(Texts.component(line.text()), color(line.tone()));
		}
		addRenderableWidget(list);
		layoutFooter(footerTop);
		if (deriving) {
			controller.tryItRefresh();
		}
	}

	private void introFooter() {
		Text refused = controller.tryItRefusal(rec);
		String unavailable = refused == null ? BenchmarkController.unavailable(minecraft, scene) : null;
		Button start = button(Component.translatable("rigtune.tryit.action.start"), b -> {
			Component answer = controller.startTryIt(rec, scene);
			status = answer.getString().isEmpty() ? null : answer;
			rebuildWidgets();
		});
		start.active = refused == null && unavailable == null;
		Component why = refused != null ? Texts.component(refused) : unavailable != null ? Texts.component(TryItText.sceneRefusal(unavailable)) : null;
		start.setTooltip(Tooltip.create(why != null ? why : Texts.component(TryItText.explain())));
		button(Component.translatable("gui.cancel"), b -> onClose());
	}

	private void stageFooter() {
		TryIt t = shown.tryIt();
		for (TryItView.Action action : shown.actions()) {
			switch (action) {
				case MEASURE_NOW, MEASURE_AGAIN -> {
					Button measure = button(Component.translatable(action == TryItView.Action.MEASURE_NOW ? "rigtune.tryit.action.measure_now"
							: "rigtune.tryit.action.measure_again"), b -> controller.tryItMeasureNow());
					String unavailable = t == null ? null : BenchmarkController.unavailable(minecraft, t.scene());
					measure.active = unavailable == null;
					if (unavailable != null) {
						measure.setTooltip(Tooltip.create(Texts.component(TryItText.sceneRefusal(unavailable))));
					}
				}
				case KEEP -> button(Component.translatable("rigtune.tryit.action.keep"), b -> {
					Component answer = controller.tryItKeep();
					status = answer.getString().isEmpty() ? null : answer;
					rebuildWidgets();
				}).setTooltip(Tooltip.create(Component.translatable("rigtune.tryit.action.keep.tooltip")));
				case REVERT -> button(Component.translatable("rigtune.tryit.action.revert"), b -> undo(t))
						.setTooltip(Tooltip.create(Component.translatable("rigtune.tryit.action.revert.tooltip")));
				case CANCEL_TRY -> button(Component.translatable("rigtune.tryit.action.cancel"), b -> undo(t))
						.setTooltip(Tooltip.create(Component.translatable("rigtune.tryit.action.cancel.tooltip")));
				case LATER -> button(Component.translatable("rigtune.tryit.action.later"), b -> onClose());
				case DECIDE_LATER -> button(Component.translatable("rigtune.tryit.action.decide_later"), b -> onClose());
				case DONE -> button(Component.translatable("gui.done"), b -> {
					controller.tryItCancel();
					onClose();
				});
			}
		}
		if (footer.isEmpty() && !shown.stage().chainRunning()) {
			button(Component.translatable("gui.done"), b -> onClose());
		}
		if (deriving) {
			footer.forEach(b -> b.active = false);
		}
	}

	private void undo(@Nullable TryIt t) {
		if (t != null) {
			deriving = true;
			derivingTicks = 0;
			minecraft.gui.setScreen(new UndoScreen(this, controller, t.entryId()));
		}
	}

	private Button button(Component label, Button.OnPress press) {
		Button button = Button.builder(label, press).build();
		footer.add(button);
		return button;
	}

	// At most 3 buttons in one row (ti §2.7: 3 x 98 + 8 fits 320 wide).
	private void layoutFooter(int top) {
		int count = footer.size();
		if (count == 0) {
			return;
		}
		int gap = 4;
		int buttonWidth = Math.min(98, (Math.min(width - 16, 304) - gap * (count - 1)) / count);
		int x = (width - (buttonWidth * count + gap * (count - 1))) / 2;
		for (Button button : footer) {
			button.setRectangle(buttonWidth, 20, x, top);
			addRenderableWidget(button);
			x += buttonWidth + gap;
		}
	}

	@Override
	public void tick() {
		super.tick();
		if (!intro() && controller.tryIt() != shown || intro() && controller.tryIt().tryIt() != null || deriving && ++derivingTicks > 40) {
			deriving = false;
			rebuildWidgets();
		}
	}

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

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		graphics.centeredText(font, title.copy().withStyle(ChatFormatting.BOLD), width / 2, 8, Palette.of(COLOR_TEXT));
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	private static int color(TryItText.Tone tone) {
		return switch (tone) {
			case NORMAL -> COLOR_TEXT;
			case GOOD -> COLOR_GOOD;
			case BAD -> COLOR_BAD;
			case WARNING -> COLOR_WARNING;
			case NOTE -> COLOR_NOTE;
		};
	}

	final class Lines extends RowList<Lines.Row> {
		private final int rowWidth;

		Lines(int top, int listHeight, int rowWidth) {
			super(TryItScreen.this.minecraft, TryItScreen.this.width, listHeight, top, 12);
			this.rowWidth = rowWidth;
		}

		@Override
		public int getRowWidth() {
			return rowWidth;
		}

		void add(Component text, int color) {
			Row row = new Row(text, color, getRowWidth() - 8);
			addEntry(row, row.preferredHeight());
		}

		// docs/v0.4/SPEC.md 11: every line is a Tab/arrow stop and narrates its whole text (a wrapped line included).
		final class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final Component text;
			private final List<FormattedCharSequence> lines;
			private final int color;
			private final RowFocus focus;

			Row(Component text, int color, int width) {
				this.text = text;
				this.lines = font.split(text, Math.max(40, width));
				this.color = color;
				this.focus = new RowFocus(this, text);
			}

			int preferredHeight() {
				return 2 + lines.size() * LINE + 2;
			}

			@Override
			public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = getContentX() + 4;
				int y = getContentY() + 1;
				for (FormattedCharSequence line : lines) {
					graphics.text(font, line, x, y, Palette.of(color), false);
					y += LINE;
				}
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
}
