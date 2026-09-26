package io.github.chaotix345.rigtune.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// docs/v0.4/SPEC.md 11: the base of RigTune's lists, whose rows are focusable (RowFocus or a checkbox). It draws the
// frame around the keyboard-focused row, narrates that row on 26.2 even with the cursor resting on another one, and
// helps a screen keep the focused row across a rebuild.
public abstract class RowList<E extends ContainerObjectSelectionList.Entry<E>> extends ContainerObjectSelectionList<E> {
	protected RowList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
		super(minecraft, width, height, y, itemHeight);
	}

	@Override
	protected void extractItem(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, E entry) {
		super.extractItem(graphics, mouseX, mouseY, partialTick, entry);
		RowFocus.outline(graphics, entry);
	}

	//? if <26.3 {
	// 26.2 narrates the row under the cursor before the focused one, so a keyboard user whose cursor rests on the list
	// would hear that row instead of the one Tab moved to (26.3 narrates the focused row for keyboard navigation).
	@Override
	public void updateWidgetNarration(NarrationElementOutput output) {
		E focused = getFocused();
		if (focused != null && focused.getFocused() instanceof AbstractWidget child && child.isFocused()) {
			child.updateNarration(output.nest());
			narrateListElementPosition(output, focused);
			return;
		}
		super.updateWidgetNarration(output);
	}
	//?}

	// The row whose child has the keyboard focus, or -1.
	public int focusedRow() {
		E focused = getFocused();
		return focused != null && focused.getFocused() != null ? children().indexOf(focused) : -1;
	}

	// Where the focus goes after a screen rebuild: back to row `row` (or the last row, if the list got shorter) when a row
	// had it, else, after keyboard use, the first widget outside the list: vanilla's initial focus as it was before the
	// rows were focusable, so a rebuild neither jumps to the first row nor scrolls the list to the top. Null: no focus.
	public static @Nullable ComponentPath initialFocus(ContainerEventHandler screen, @Nullable RowList<?> list, int row, boolean keyboard) {
		if (list != null && row >= 0 && !list.children().isEmpty()) {
			ContainerObjectSelectionList.Entry<?> entry = list.children().get(Math.min(row, list.children().size() - 1));
			return ComponentPath.path(screen, ComponentPath.path(list, entry.focusPathAtIndex(new FocusNavigationEvent.TabNavigation(true), 0)));
		}
		if (!keyboard) {
			return null;
		}
		List<GuiEventListener> children = new ArrayList<>(screen.children());
		children.sort(Comparator.comparingInt(GuiEventListener::getTabOrderGroup));
		for (GuiEventListener child : children) {
			ComponentPath path = child == list ? null : child.nextFocusPath(new FocusNavigationEvent.TabNavigation(true));
			if (path != null) {
				return ComponentPath.path(screen, path);
			}
		}
		return null;
	}
}
