package io.github.chaotix345.rigtune.client.ui;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkResultScreenTest {
	@Test
	void theChartDateIsTheLocalDay() {
		assertEquals("09-26", BenchmarkResultScreen.chartDate("2026-09-25T20:05:31Z", ZoneOffset.ofHours(10)));
		assertEquals("09-25", BenchmarkResultScreen.chartDate("2026-09-25T20:05:31Z", ZoneOffset.UTC));
	}

	@Test
	void anUnreadableDateFallsBackToItsMonthAndDay() {
		assertEquals("09-25", BenchmarkResultScreen.chartDate("2026-09-25 later", ZoneOffset.UTC));
		assertEquals("?", BenchmarkResultScreen.chartDate("soon", ZoneOffset.UTC));
		assertEquals("?", BenchmarkResultScreen.chartDate(null, ZoneOffset.UTC));
	}
}
