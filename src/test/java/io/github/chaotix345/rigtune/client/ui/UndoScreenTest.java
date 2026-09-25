package io.github.chaotix345.rigtune.client.ui;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UndoScreenTest {
	@Test
	void theApplyTimeIsShownInLocalTime() {
		assertEquals("2026-09-25 20:05", UndoScreen.when("2026-09-25T10:05:31.123Z", ZoneOffset.ofHours(10)));
	}

	@Test
	void anUnreadableTimeIsShownAsItIs() {
		assertEquals("yesterday", UndoScreen.when("yesterday", ZoneOffset.UTC));
	}
}
