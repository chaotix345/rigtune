package io.github.chaotix345.rigtune.client;

import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumButtonSpotTest {
	static List<ScreenRectangle> sodium(int width, int height) {
		int contentWidth = width;
		boolean insetX = false;
		if (width > 605) {
			contentWidth = width < 655 ? 505 + (int) ((float) (width - 605) / 50 * 50) : 555;
			insetX = true;
		}
		int contentHeight = height;
		boolean insetY = false;
		if (height > 312 && insetX) {
			contentHeight = height - 12;
			insetY = true;
		}
		int x = (width - contentWidth) / 2;
		int y = (height - contentHeight) / 2;
		int w = contentWidth;
		int h = contentHeight;
		List<ScreenRectangle> out = new ArrayList<>();
		out.add(new ScreenRectangle(x, y, w - 20, 20));
		out.add(new ScreenRectangle(x + w - 20, y, 20, 20));
		int topBarClear = 20 + (insetY ? 5 : 0);
		out.add(new ScreenRectangle(x, y + topBarClear, 125, h - topBarClear));
		boolean stack = w > 422 && w < 557;
		boolean reserve = w < 422;
		int closeX = x + w - 65 - (insetX ? 0 : 5);
		int closeY = y + h - ((insetY ? 0 : 5) + 20);
		int dx = stack ? 0 : -70;
		int dy = stack ? -25 : 0;
		int rowX = closeX + dx;
		int rowY = stack ? closeY + dy : y + h - 25;
		out.add(new ScreenRectangle(closeX, closeY, 65, 20));
		out.add(new ScreenRectangle(rowX, rowY, 65, 20));
		out.add(new ScreenRectangle(rowX + dx, rowY + dy, 65, 20));
		out.add(new ScreenRectangle(x + 125, y + 25, 222, h - 20 - (reserve ? 30 : 5) - (insetY ? 0 : 5)));
		return out;
	}

	private static void assertClear(int width, int height) {
		List<ScreenRectangle> widgets = sodium(width, height);
		int[] spot = RigTuneClient.sodiumSpot(widgets, width, height);
		assertNotNull(spot, width + "x" + height);
		ScreenRectangle button = new ScreenRectangle(spot[0], spot[1], 60, 20);
		assertTrue(spot[0] >= 0 && spot[1] >= 0 && button.right() <= width && button.bottom() <= height, width + "x" + height + ": on screen");
		for (ScreenRectangle widget : widgets) {
			assertFalse(widget.intersects(button), width + "x" + height + ": clear of " + widget);
		}
	}

	@Test
	void aboveSodiumsButtonsAtEveryLayout() {
		assertClear(426, 240);
		assertClear(427, 240);
		assertClear(480, 270);
		assertClear(640, 360);
		assertClear(960, 540);
		assertClear(1280, 720);
	}

	@Test
	void theStackedColumnAt2560x1440GuiScaleAuto() {
		assertArrayEquals(new int[]{361, 140}, RigTuneClient.sodiumSpot(sodium(426, 240), 426, 240), "above Undo, right-aligned with Done");
	}

	@Test
	void noFreeSpotOnANarrowScreen() {
		assertNull(RigTuneClient.sodiumSpot(sodium(284, 160), 284, 160), "the caller falls back to the old corner");
		assertNull(RigTuneClient.sodiumSpot(List.of(), 426, 240), "not Sodium's layout");
	}
}
