package io.github.chaotix345.rigtune.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

import java.util.List;

// docs/v0.5/SPEC.md 5 (C20): a StutterScreen row of buttons ("Try this fix…"; "Undo this change…" and "Dismiss"), left
// to right from the row's left edge. Its buttons are its children, so each is its own Tab stop that narrates itself (the
// main list's checkbox pattern); the row draws nothing else, and RowList's focus frame stays with the buttons' own.
final class ButtonRow extends StutterScreen.Row {
	static final int HEIGHT = 22;
	static final int GAP = 4;

	private final List<Button> buttons;
	private final RowFocus focus;

	ButtonRow(List<Button> buttons) {
		this.buttons = List.copyOf(buttons);
		this.focus = new RowFocus(this, Component.empty());
	}

	List<Button> buttons() {
		return buttons;
	}

	@Override
	int height() {
		return HEIGHT;
	}

	// Not a child (the buttons are the stops); the Row contract's focus for everything that asks it.
	@Override
	RowFocus focus() {
		return focus;
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return buttons;
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return buttons;
	}

	@Override
	public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
		int x = getContentX();
		for (Button b : buttons) {
			b.setPosition(x, getContentY() + 1);
			b.extractRenderState(graphics, mouseX, mouseY, partialTick);
			x += b.getWidth() + GAP;
		}
	}
}
