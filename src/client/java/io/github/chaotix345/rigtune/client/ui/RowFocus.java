package io.github.chaotix345.rigtune.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

// docs/v0.4/SPEC.md 11: a custom list row's focusable, narratable child. A row is a Tab/arrow stop only through a
// focusable child, and Entry.updateNarration is package-private, so narratables() is the only way to be narrated
// (docs/research/v0.4/bench-history-a11y.md B2). It draws nothing (the row draws itself), never takes a mouse click
// (clicks reach the row as before) and its message is the row's text. With an action, Enter or Space runs it.
// standalone(...) is the same for a line of text that isn't a list row (a notice, a trend line, the startup line; review-8
// UV-2 to UV-4): placed over the text, it draws only the focus frame while it has the keyboard focus.
public final class RowFocus extends AbstractWidget {
	private final @Nullable LayoutElement row;
	private final @Nullable Runnable action;
	private final @Nullable BooleanSupplier selected;

	public RowFocus(LayoutElement row, Component message) {
		this(row, message, null, null);
	}

	// selected: whether the row is the list's selected one (History's open entry, the chosen profile), said after its text.
	public RowFocus(LayoutElement row, Component message, @Nullable Runnable action, @Nullable BooleanSupplier selected) {
		this(row, message, action, selected, 0, 0, 0, 0);
	}

	private RowFocus(@Nullable LayoutElement row, Component message, @Nullable Runnable action, @Nullable BooleanSupplier selected, int x, int y,
			int width, int height) {
		super(x, y, width, height, message);
		this.row = row;
		this.action = action;
		this.selected = selected;
	}

	// A focus stop for a line of text drawn by its screen at (x, y, width, height).
	public static RowFocus standalone(Component message, int x, int y, int width, int height) {
		return new RowFocus(null, message, null, null, x, y, width, height);
	}

	// Where the row is, for arrow navigation out of the list.
	@Override
	public ScreenRectangle getRectangle() {
		return row == null ? super.getRectangle() : row.getRectangle();
	}

	@Override
	public boolean isMouseOver(double mouseX, double mouseY) {
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (action != null && event.isSelection()) {
			action.run();
			return true;
		}
		return false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (row == null && isFocused()) {
			fill(graphics, getX(), getY(), getWidth(), getHeight());
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, selected != null && selected.getAsBoolean()
				? join(getMessage(), Component.translatable("rigtune.a11y.selected")) : getMessage());
		if (action != null) {
			output.add(NarratedElementType.USAGE, Component.translatable("rigtune.a11y.row.select"));
		}
	}

	// A row's parts as one narration ("a. b. c"), leaving out missing and empty ones.
	public static Component join(@Nullable Component... parts) {
		List<Component> shown = new ArrayList<>();
		for (Component part : parts) {
			if (part != null && !part.getString().isEmpty()) {
				shown.add(part);
			}
		}
		return CommonComponents.joinForNarration(shown.toArray(Component[]::new));
	}

	// A 1 px frame around a row whose RowFocus has the keyboard focus (the row itself draws nothing for focus); lists call
	// it from extractItem. Nothing is drawn otherwise, so mouse use looks the same as before.
	public static void outline(GuiGraphicsExtractor graphics, ContainerObjectSelectionList.Entry<?> entry) {
		if (!(entry.getFocused() instanceof RowFocus focus) || !focus.isFocused()) {
			return;
		}
		fill(graphics, entry.getX(), entry.getY(), entry.getWidth(), entry.getHeight());
	}

	private static void fill(GuiGraphicsExtractor graphics, int left, int top, int width, int height) {
		int color = Palette.focus();
		for (int[] r : frame(left, top, width, height)) {
			graphics.fill(r[0], r[1], r[2], r[3], color);
		}
	}

	// The frame's four 1 px edges as fill rectangles {x0, y0, x1, y1} (ends exclusive, as GuiGraphicsExtractor.fill), around
	// exactly [left, left + width) x [top, top + height) (review-8 UV-1: the bottom edge was a row short).
	static int[][] frame(int left, int top, int width, int height) {
		int right = left + width;
		int bottom = top + height;
		return new int[][]{{left, top, right, top + 1}, {left, bottom - 1, right, bottom}, {left, top + 1, left + 1, bottom - 1},
				{right - 1, top + 1, right, bottom - 1}};
	}
}
