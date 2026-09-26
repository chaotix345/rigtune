package io.github.chaotix345.rigtune.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.narration.ScreenNarrationCollector;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 11: a row's focus child narrates the row's text, takes no mouse clicks, sits where the row is, and
// runs its action on Enter/Space only.
class RowFocusTest {
	// The InputConstants values are compile-time constants (each version's own numbers), so the class isn't loaded.
	private static final KeyEvent ENTER = new KeyEvent(InputConstants.KEY_RETURN, 0, 0);
	private static final KeyEvent SPACE = new KeyEvent(InputConstants.KEY_SPACE, 0, 0);
	private static final KeyEvent TAB = new KeyEvent(InputConstants.KEY_TAB, 0, 0);

	private static final class Row implements LayoutElement {
		@Override
		public void setX(int x) {
		}

		@Override
		public void setY(int y) {
		}

		@Override
		public int getX() {
			return 40;
		}

		@Override
		public int getY() {
			return 60;
		}

		@Override
		public int getWidth() {
			return 300;
		}

		@Override
		public int getHeight() {
			return 24;
		}

		@Override
		public void visitWidgets(Consumer<AbstractWidget> consumer) {
		}
	}

	private static String narration(RowFocus focus) {
		ScreenNarrationCollector collector = new ScreenNarrationCollector();
		//? if >=26.3 {
		/*collector.update(focus::updateNarration, net.minecraft.client.gui.narration.NarrationTrigger.KEYBOARD);
		*///?} else
		collector.update(focus::updateNarration);
		return collector.collectNarrationText(false);
	}

	@Test
	void narratesTheRowsText() {
		RowFocus focus = new RowFocus(new Row(), RowFocus.join(Component.literal("Render distance 12 to 16"), Component.literal("High impact")));
		String text = narration(focus);
		assertTrue(text.contains("Render distance 12 to 16") && text.contains("High impact"), text);
		assertFalse(text.contains("rigtune.a11y.row.select"), "no usage hint without an action: " + text);
	}

	@Test
	void aRowWithAnActionSaysHowToUseIt() {
		String text = narration(new RowFocus(new Row(), Component.literal("Apply · 2026-09-25 20:05"), () -> {
		}));
		assertTrue(text.contains("Apply · 2026-09-25 20:05") && text.contains("rigtune.a11y.row.select"), text);
	}

	@Test
	void joinLeavesOutMissingAndEmptyParts() {
		assertEquals(RowFocus.join(Component.literal("a"), Component.literal("b")).getString(),
				RowFocus.join(null, Component.literal("a"), Component.empty(), Component.literal("b"), null).getString());
		assertTrue(RowFocus.join(Component.literal("a"), Component.literal("b")).getString().matches("a\\W+b"));
	}

	@Test
	void enterAndSpaceRunTheActionOtherKeysDont() {
		AtomicInteger runs = new AtomicInteger();
		RowFocus focus = new RowFocus(new Row(), Component.literal("row"), runs::incrementAndGet);
		assertTrue(focus.keyPressed(ENTER));
		assertTrue(focus.keyPressed(SPACE));
		assertFalse(focus.keyPressed(TAB));
		assertEquals(2, runs.get());
		assertFalse(new RowFocus(new Row(), Component.literal("row")).keyPressed(ENTER), "no action: the key goes on");
	}

	@Test
	void clicksStayWithTheRowAndArrowsUseTheRowsPlace() {
		RowFocus focus = new RowFocus(new Row(), Component.literal("row"));
		assertFalse(focus.isMouseOver(50, 70));
		assertEquals(new ScreenRectangle(40, 60, 300, 24), focus.getRectangle());
	}
}
