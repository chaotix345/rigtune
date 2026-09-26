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
public final class RowFocus extends AbstractWidget {
	private final LayoutElement row;
	private final @Nullable Runnable action;
	private final @Nullable BooleanSupplier selected;

	public RowFocus(LayoutElement row, Component message) {
		this(row, message, null, null);
	}

	// selected: whether the row is the list's selected one (History's open entry, the chosen profile), said after its text.
	public RowFocus(LayoutElement row, Component message, @Nullable Runnable action, @Nullable BooleanSupplier selected) {
		super(0, 0, 0, 0, message);
		this.row = row;
		this.action = action;
		this.selected = selected;
	}

	// Where the row is, for arrow navigation out of the list.
	@Override
	public ScreenRectangle getRectangle() {
		return row.getRectangle();
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
		int color = Palette.focus();
		int left = entry.getX();
		int top = entry.getY();
		int right = left + entry.getWidth();
		int bottom = top + entry.getHeight() - 1;
		graphics.fill(left, top, right, top + 1, color);
		graphics.fill(left, bottom - 1, right, bottom, color);
		graphics.fill(left, top + 1, left + 1, bottom - 1, color);
		graphics.fill(right - 1, top + 1, right, bottom - 1, color);
	}
}
