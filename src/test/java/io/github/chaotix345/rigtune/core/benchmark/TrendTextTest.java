package io.github.chaotix345.rigtune.core.benchmark;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend.Difference;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 7 and X4: the context labels, the last-benchmark line with its "needs a rerun" marker, the note, and
// honest wording (no cause, no bottleneck) in every rigtune.benchmark.trend.* string.
class TrendTextTest {
	@Test
	void theContextLabelNamesEveryCondition() {
		assertEquals("Benchmark world · Minecraft 26.2 · RD 12 · SD 8 · 2560×1440 · shaders off",
				TrendText.context(TrendFixtures.run("a").build()).english());
		assertEquals("Current world · Minecraft 26.3 · RD 16 · SD 10 · 1920×1080 · fullscreen · shaders: Complementary.zip · Distant Horizons on",
				TrendText.context(TrendFixtures.run("b").scene("CURRENT").mc("26.3").rd(16).sd(10)
						.context(new BenchmarkRecord.Context(true, true, "Complementary.zip", 1920, 1080, true, 1)).build()).english());
		assertEquals("Benchmark world · Minecraft 26.2 · RD 12 · SD 8 · 2560×1440 · shaders on · benchmark version 2",
				TrendText.context(TrendFixtures.run("c").context(new BenchmarkRecord.Context(false, true, null, 2560, 1440, false, 2)).build()).english());
		assertEquals("Benchmark world · Minecraft 26.2 · RD 12 · SD 8 · conditions not recorded",
				TrendText.context(TrendFixtures.run("d").context(null).build()).english());
		assertEquals("Benchmark world · RD 12 · SD 8 · 2560×1440 · shaders off", TrendText.context(TrendFixtures.run("e").build(), false).english());
	}

	@Test
	void theLastBenchmarkLineAndItsRerunMarker() {
		BenchmarkRecord run = TrendFixtures.run("a").at("2026-09-24T22:30:00Z").low(543.4).build();
		assertEquals("Last benchmark: 1% low 543 FPS · RD 12 · SD 8 · 2560×1440 · shaders off · 2026-09-24",
				TrendText.last(run, ZoneOffset.UTC).english());
		assertEquals("Last benchmark: 1% low 543 FPS · RD 12 · SD 8 · 2560×1440 · shaders off · 2026-09-25",
				TrendText.last(run, ZoneOffset.ofHours(10)).english());
		assertNull(TrendText.last(TrendFixtures.run("b").noResult().build(), ZoneOffset.UTC));
		assertNull(TrendText.last(null, ZoneOffset.UTC));
		assertNull(TrendText.rerun(List.of()));
		assertEquals("Needs a rerun (changed since: resolution, mod set)", TrendText.rerun(List.of(Difference.RESOLUTION, Difference.MOD_SET)).english());
		assertEquals("Your last benchmark needs a rerun: shaders changed", TrendText.staleNotice(List.of(Difference.SHADERS)).english());
		assertEquals("RD 12 · SD 8", TrendText.conditions(TrendFixtures.run("c").context(null).build()).english());
	}

	@Test
	void theNote() {
		assertEquals("4 comparable runs; 1 with different conditions not shown", TrendText.note(4, 1).english());
		assertEquals("1 comparable run; 0 with different conditions not shown", TrendText.note(1, 0).english());
	}

	@Test
	void datesAreTheLocalDay() {
		assertEquals("2026-09-25", TrendText.date("2026-09-24T20:00:00Z", ZoneOffset.ofHours(10)));
		assertEquals("2026-09-24", TrendText.date("2026-09-24 hand-edited", ZoneOffset.UTC));
		assertEquals("?", TrendText.date("soon", ZoneOffset.UTC));
		assertEquals("?", TrendText.date(null, ZoneOffset.UTC));
	}

	@Test
	void everyDifferenceHasAName() {
		for (Difference d : Difference.values()) {
			assertFalse(TrendText.difference(d).english().isBlank(), d.name());
		}
	}

	// X4: a trend describes, never explains.
	@Test
	void theWordingNeverClaimsACause() throws IOException {
		JsonObject lang = JsonParser.parseString(Files.readString(RepoFiles.resolve("src/main/resources/assets/rigtune/lang/en_us.json")))
				.getAsJsonObject();
		int checked = 0;
		for (String key : lang.keySet()) {
			if (key.startsWith("rigtune.benchmark.trend.")) {
				String value = lang.get(key).getAsString().toLowerCase(Locale.ROOT);
				for (String banned : List.of("caused", "because of", "bottleneck", "limited by")) {
					assertFalse(value.contains(banned), key + ": " + value);
				}
				checked++;
			}
		}
		assertTrue(checked > 40, "checked " + checked);
		assertTrue(lang.get("rigtune.benchmark.trend.changes").getAsString().contains("may be related"));
		assertTrue(lang.get("rigtune.benchmark.trend.different").getAsString().contains("cause unknown"));
	}
}
