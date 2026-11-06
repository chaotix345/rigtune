package io.github.chaotix345.rigtune.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigTuneScreenLayoutTest {
	@Test
	void issue35TheBadgeGetsItsOwnLineOnlyWhileTheListKeepsThreeRows() {
		assertTrue(RigTuneScreen.roomForOwnBadgeLine(86), "426x240 without a notice: 76 px left");
		assertTrue(RigTuneScreen.roomForOwnBadgeLine(82));
		assertFalse(RigTuneScreen.roomForOwnBadgeLine(81));
		assertFalse(RigTuneScreen.roomForOwnBadgeLine(76), "640x480@2 (AC8.18's 0.4.0 height): no line taken");
		assertFalse(RigTuneScreen.roomForOwnBadgeLine(60), "640x480@2 with the guide");
	}

	@Test
	void issue38TheTooltipWrapsInsideTheScreen() {
		assertEquals(250, RigTuneScreen.tooltipWidth(1280));
		assertEquals(184, RigTuneScreen.tooltipWidth(200));
		assertEquals(40, RigTuneScreen.tooltipWidth(50));
	}
}
