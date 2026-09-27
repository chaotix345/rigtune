package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.stutter.Attributor;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The Stutter Doctor's count lines at the text the screen shows (RigTune's en_us.json): singular and plural (review-8
// P5B-F3) and the chunk-loading tag (P5A-F2).
class StutterScreenTextTest {
	private Language previous;

	@BeforeEach
	void english() throws IOException {
		previous = Language.getInstance();
		Language.inject(TextsTest.rigtuneEnglish(previous));
	}

	@AfterEach
	void restore() {
		Language.inject(previous);
	}

	static StutterReport report(StutterReport.Spikes spikes, int hitches, Map<String, Integer> tags, Map<String, Double> causes) {
		return new StutterReport("2026-09-26T10:00:00Z", StutterReport.MONITOR, "26.2", "G1", 4096, 200.0, 180.0, 10_000, 60.0, 30.0, new long[9], new long[9],
				spikes, 1800.0, causes, tags, List.of(), new StutterReport.Facts(null, 0, 0, 0, null), List.of(), true, true, hitches);
	}

	@Test
	void oneSpikeReadsInTheSingular() {
		assertEquals("1 spike (0 minor, 0 major, 0 severe, 1 freeze) in 1 hitch, 1.8 s lost",
				StutterScreen.spikesLine(report(new StutterReport.Spikes(0, 0, 0, 1), 1, Map.of(), Map.of())).getString());
		assertEquals("12 spikes (9 minor, 2 major, 1 severe, 0 freezes) in 9 hitches, 1.8 s lost",
				StutterScreen.spikesLine(report(new StutterReport.Spikes(9, 2, 1, 0), 9, Map.of(), Map.of())).getString());
	}

	@Test
	void tagLines() {
		assertEquals("7 of 12 spikes happened during world saves (not measured)", StutterScreen.tagLine(Attributor.WORLD_SAVE, 7, 12).getString());
		assertEquals("The spike happened during world saves (not measured)", StutterScreen.tagLine(Attributor.WORLD_SAVE, 1, 1).getString());
		assertEquals("5 of 8 spikes happened while chunks were loading (not measured)", StutterScreen.tagLine(Attributor.CHUNKS_LOADING, 5, 8).getString());
		assertEquals("The spike happened while chunks were loading (not measured)", StutterScreen.tagLine(Attributor.CHUNKS_LOADING, 1, 1).getString());
		assertEquals("Rendering ● · chunks loading", StutterScreen.notes(List.of("render:low", "chunksLoading:context")).getString());
	}

	// docs/v0.5/SPEC.md 2S SD-2 (AC2S.6): the header's frames line names the window its numbers cover, when there is one.
	@Test
	void theFramesLineNamesTheWindow() {
		StutterReport r = report(new StutterReport.Spikes(9, 2, 1, 0), 9, Map.of(), Map.of());
		StutterReport windowed = new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), 3300, 3276.8, 131_071, 200.0, 200.0,
				new long[]{0, 131_072, 131_072, 0, 0, 0, 0, 0, 0}, r.histogramTimeMs(), r.spikes(), r.lostMs(), r.causes(), r.tags(), r.worst(), r.facts(),
				r.advice(), true, true, r.hitches());
		assertEquals("131,071 frames · average 200 FPS · 1% low 200 FPS over the last 10:55 of gameplay", StutterScreen.framesLine(windowed).getString());
		assertEquals("10,000 frames · average 60 FPS · 1% low 30 FPS", StutterScreen.framesLine(r).getString());
	}

	// docs/v0.5/SPEC.md 2S RW-10 (AC2S.12): the real-world session's causes (gc 0.60, tick 0.18, chunkLoad 0.0, unknown 0.23)
	// show no "Chunk loading 0 %" row, and the shown whole percentages total at most 100.
	@Test
	void rw10NoZeroRowAndAtMostOneHundred() {
		Map<String, Double> causes = new java.util.LinkedHashMap<>();
		causes.put(Attributor.GC, 0.60);
		causes.put(Attributor.CHUNK_LOAD, 0.0);
		causes.put(Attributor.TICK, 0.18);
		causes.put(Attributor.UNKNOWN, 0.23);
		List<String> rows = StutterScreen.causeRows(report(new StutterReport.Spikes(126, 3, 3, 0), 104, Map.of(), causes)).stream().map(Component::getString)
				.toList();
		assertEquals(List.of("Garbage collection 59 %", "Game ticks 18 %", "Not explained 23 %"), rows);
		assertEquals(List.of("Garbage collection 44 %", "Chunk loading 12 %", "Not explained 44 %"), StutterScreen.causeRows(report(new StutterReport.Spikes(9, 2,
				1, 0), 9, Map.of(), Map.of(Attributor.GC, 0.44, Attributor.CHUNK_LOAD, 0.12, Attributor.UNKNOWN, 0.44))).stream().map(Component::getString).toList(),
				"shares that add up are shown as they are");
	}

	// docs/v0.5/SPEC.md 2S RW-11: the session's settings changes and the tag, at the text the screen shows.
	@Test
	void settingsChangedLines() {
		StutterReport r = report(new StutterReport.Spikes(9, 2, 1, 0), 9, Map.of(Attributor.SETTINGS_CHANGED, 3), Map.of());
		assertEquals(null, StutterScreen.settingsLine(r), "no settings recorded (a 0.4 session, a benchmark)");
		StutterReport changed = r.withSettings(Map.of(StutterReport.RENDER_DISTANCE, "32", StutterReport.SIMULATION_DISTANCE, "12", StutterReport.SHADERS, "true"),
				Map.of(StutterReport.RENDER_DISTANCE, "12", StutterReport.SIMULATION_DISTANCE, "12", StutterReport.SHADERS, "false"));
		assertEquals("Settings changed during this session (render distance 32 → 12, shaders on → off)", StutterScreen.settingsLine(changed).getString());
		assertEquals(null, StutterScreen.settingsLine(r.withSettings(Map.of(StutterReport.RENDER_DISTANCE, "12"), Map.of(StutterReport.RENDER_DISTANCE, "12"))));
		assertEquals("3 of 12 spikes happened during the 10 s after a settings change or resource reload (not measured)",
				StutterScreen.tagLine(Attributor.SETTINGS_CHANGED, 3, 12).getString());
		assertEquals("Game ticks ●● · the 10 s after a settings change or resource reload",
				StutterScreen.notes(List.of("tick:medium", "settingsChanged:context")).getString());
	}

	@Test
	void theBenchmarkLineCountsOneSpike() {
		assertEquals("Stutter Doctor: 1 spike during the sweeps; no cause measured",
				StutterScreen.benchmarkLine(report(new StutterReport.Spikes(1, 0, 0, 0), 1, Map.of(), Map.of(Attributor.UNKNOWN, 1.0))).getString());
		assertEquals("Stutter Doctor: 1 spike during the sweeps; likely causes: Garbage collection 60 %",
				StutterScreen.benchmarkLine(report(new StutterReport.Spikes(1, 0, 0, 0), 1, Map.of(), Map.of(Attributor.GC, 0.6, Attributor.UNKNOWN, 0.4))).getString());
		assertEquals("Stutter Doctor: 3 spikes during the sweeps; no cause measured",
				StutterScreen.benchmarkLine(report(new StutterReport.Spikes(3, 0, 0, 0), 2, Map.of(), Map.of(Attributor.UNKNOWN, 1.0))).getString());
	}
}
