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

	// Parses as an Instant but has no date in the zone: shown as text, never an exception while drawing (re-check of review 6).
	@Test
	void anInstantOutsideTheZonesRangeDoesntThrow() {
		assertEquals("00000", BenchmarkResultScreen.chartDate("+1000000000-01-01T00:00:00Z", ZoneOffset.UTC));
		assertEquals("99999", BenchmarkResultScreen.chartDate("+999999999-12-31T23:59:59Z", ZoneOffset.ofHours(10)));
	}
}
