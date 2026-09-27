package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.model.Impact;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The Copy summary text: honest wording (X4), the unexplained share always shown, bounded, no paths.
class StutterSummaryTest {
	static StutterReport report(boolean enough, boolean phases, Double offset) {
		return new StutterReport("2026-09-26T10:00:00Z", StutterReport.MONITOR, "26.2", "G1", 6144, 812.0, 734.5, 87700, 119.4, 61.2,
				new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, new long[]{0, 1, 2, 3, 4, 5, 6, 7, 8}, new StutterReport.Spikes(9, 2, 1, 0), 1810.0,
				Map.of(Attributor.GC, 0.44, Attributor.CHUNK_LOAD, 0.12, Attributor.UNKNOWN, 0.44), Map.of(Attributor.WORLD_SAVE, 7),
				List.of(new StutterReport.Worst(431.2, 212, 7.1, List.of("gc:high:FULL:EXPLICIT", "worldSave:low")),
						new StutterReport.Worst(12, 90, 7, List.of())),
				new StutterReport.Facts(81, 2, 0, 1, offset), List.of("ram-stutter-gc-heap"), enough, phases, 9);
	}

	@Test
	void aSummaryReadsHonestly() {
		String text = StutterSummary.text(report(true, true, 21.7),
				List.of(new StutterAdvisor.Fired("ram-stutter-gc-heap", "warning", Impact.HIGH, "Stutter from memory pressure", "x")));
		assertTrue(text.startsWith("**RigTune Stutter Doctor** · session · Minecraft 26.2 · G1, 6144 MB heap\n"), text);
		assertTrue(text.contains("13:32 (12:15 of gameplay) · 87,700 frames · avg 119 FPS · 1% low 61 FPS"), text);
		assertTrue(text.contains("12 spikes (9 minor, 2 major, 1 severe, 0 freezes) in 9 hitches · 1.8 s lost"), text);
		assertTrue(text.contains("Likely causes (share of the lost time): garbage collection 44 %, chunk loading 12 %; not explained 44 %"), text);
		assertTrue(text.contains("7 of 12 spikes happened during world saves (not measured)"), text);
		assertTrue(text.contains("Worst: 212 ms at 7:11 (garbage collection (high), full GC, System.gc(), world saves (low)); 90 ms at 0:12"), text);
		assertTrue(text.contains("Advice: Stutter from memory pressure"), text);
		assertFalse(text.contains("Not enough data"));
		String lower = text.toLowerCase(Locale.ROOT);
		for (String banned : List.of("caused", "because of", "bottleneck", "limited by")) {
			assertFalse(lower.contains(banned), banned);
		}
	}

	@Test
	void adviceTitlesAreInertMarkdown() {
		String text = StutterSummary.text(report(true, true, 21.7),
				List.of(new StutterAdvisor.Fired("a", "warning", Impact.HIGH, "*@everyone* [x](https://e.test) <@&123> ||spoiler||", "x")));
		assertTrue(text.contains("Advice: \\*@\u200Beveryone\\* \\[x\\](https://e.test) \\<@\u200B&123> \\|\\|spoiler\\|\\|\n"), text);
		assertFalse(text.contains("@everyone"), text);
	}

	// review-8 P5B-F3: one spike, one hitch, one freeze read in the singular; the chunk tag reads as its own sentence.
	@Test
	void singularCountsAndTheChunkTag() {
		StutterReport r = report(true, true, 21.7);
		StutterReport one = new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), r.sessionSeconds(), r.gameplaySeconds(),
				r.frames(), r.avgFps(), r.onePercentLowFps(), r.histogramCounts(), r.histogramTimeMs(), new StutterReport.Spikes(0, 0, 0, 1), r.lostMs(),
				r.causes(), Map.of(Attributor.CHUNKS_LOADING, 1), r.worst(), r.facts(), r.advice(), true, true, 1);
		String text = StutterSummary.text(one, List.of());
		assertTrue(text.contains("1 spike (0 minor, 0 major, 0 severe, 1 freeze) in 1 hitch · 1.8 s lost"), text);
		assertTrue(text.contains("The spike happened while chunks were loading (not measured)"), text);
		StutterReport many = new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), r.sessionSeconds(), r.gameplaySeconds(),
				r.frames(), r.avgFps(), r.onePercentLowFps(), r.histogramCounts(), r.histogramTimeMs(), r.spikes(), r.lostMs(), r.causes(),
				Map.of(Attributor.CHUNKS_LOADING, 5), r.worst(), r.facts(), r.advice(), true, true, r.hitches());
		assertTrue(StutterSummary.text(many, List.of()).contains("5 of 12 spikes happened while chunks were loading (not measured)"));
		assertEquals("chunks loading", StutterSummary.notes(List.of("chunksLoading:context")));
	}

	// docs/v0.5/SPEC.md 2S SD-2 (AC2S.6): when frames, average and 1 % low cover only the frame ring's window, the line says
	// so; a session shorter than the ring (every gameplay frame in the histogram is counted in frames) doesn't.
	@Test
	void theWindowIsNamedOnlyWhenTheCaptureIsLonger() {
		StutterReport r = report(true, true, 21.7);
		StutterReport windowed = new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), 3300, 3276.8, 131_071, 200.0, 200.0,
				new long[]{0, 131_072, 131_072, 0, 0, 0, 0, 0, 0}, r.histogramTimeMs(), r.spikes(), r.lostMs(), r.causes(), r.tags(), r.worst(), r.facts(),
				r.advice(), true, true, r.hitches());
		assertTrue(StutterSummary.text(windowed, List.of()).contains("· 131,071 frames · avg 200 FPS · 1% low 200 FPS (over the last 10:55)" + System.lineSeparator()),
				StutterSummary.text(windowed, List.of()));
		StutterReport whole = new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), r.sessionSeconds(), r.gameplaySeconds(),
				36, r.avgFps(), r.onePercentLowFps(), r.histogramCounts(), r.histogramTimeMs(), r.spikes(), r.lostMs(), r.causes(), r.tags(), r.worst(),
				r.facts(), r.advice(), true, true, r.hitches());
		assertFalse(StutterSummary.text(whole, List.of()).contains("over the last"));
		assertFalse(StutterSummary.text(r, List.of()).contains("over the last"), "a 0.4 session: never");
	}

	// RW-10 (AC2S.12): Copy summary shows the same whole percentages as the screen: none at 0, at most 100 in total.
	@Test
	void rw10PercentagesNeverTotalOverOneHundred() {
		Map<String, Double> causes = new java.util.LinkedHashMap<>();
		causes.put(Attributor.GC, 0.60);
		causes.put(Attributor.CHUNK_LOAD, 0.0);
		causes.put(Attributor.TICK, 0.18);
		causes.put(Attributor.UNKNOWN, 0.23);
		assertEquals(Map.of(Attributor.GC, 59, Attributor.TICK, 18, Attributor.UNKNOWN, 23), StutterSummary.percentages(causes));
		// Review fix: a hand-edited share is clamped (no long trimming loop, no overflow).
		assertEquals(Map.of(Attributor.GC, 50, Attributor.UNKNOWN, 50), StutterSummary.percentages(Map.of(Attributor.GC, 2e9, Attributor.UNKNOWN, 0.5)));
		assertEquals(Map.of(Attributor.UNKNOWN, 50), StutterSummary.percentages(Map.of(Attributor.GC, -3.0, Attributor.UNKNOWN, 0.5)));
		StutterReport r = report(true, true, 21.7);
		StutterReport real = new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), r.sessionSeconds(), r.gameplaySeconds(),
				r.frames(), r.avgFps(), r.onePercentLowFps(), r.histogramCounts(), r.histogramTimeMs(), r.spikes(), r.lostMs(), causes, r.tags(), r.worst(),
				r.facts(), r.advice(), true, true, r.hitches());
		assertTrue(StutterSummary.text(real, List.of()).contains("Likely causes (share of the lost time): garbage collection 59 %, game ticks 18 %; not explained 23 %"),
				StutterSummary.text(real, List.of()));
	}

	// docs/v0.5/SPEC.md 2S RW-11: Copy summary names the settings that changed and says the advice used the ones at the end.
	@Test
	void rw11SettingsChangesAreNamed() {
		StutterReport r = report(true, true, 21.7);
		StutterReport tagged = new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), r.sessionSeconds(), r.gameplaySeconds(),
				r.frames(), r.avgFps(), r.onePercentLowFps(), r.histogramCounts(), r.histogramTimeMs(), r.spikes(), r.lostMs(), r.causes(),
				Map.of(Attributor.SETTINGS_CHANGED, 4), r.worst(), r.facts(), r.advice(), true, true, r.hitches())
				.withSettings(Map.of(StutterReport.RENDER_DISTANCE, "32", StutterReport.DH_RENDERING, "false"),
						Map.of(StutterReport.RENDER_DISTANCE, "12", StutterReport.DH_RENDERING, "true"));
		String text = StutterSummary.text(tagged, List.of(new StutterAdvisor.Fired("a", "info", Impact.LOW, "Try this", "x")));
		assertTrue(text.contains("Settings changed during this session (render distance 32 → 12, Distant Horizons rendering off → on)"), text);
		assertTrue(text.contains("4 of 12 spikes happened during the 10 s after a settings change or resource reload (not measured)"), text);
		assertTrue(text.contains("The advice uses the settings at the end of the session"), text);
		assertFalse(StutterSummary.text(r, List.of()).contains("settings at the end"));
	}

	@Test
	void caveatsAreSpelledOut() {
		String text = StutterSummary.text(report(false, false, null), List.of());
		assertTrue(text.contains("Not enough data yet"), text);
		assertTrue(text.contains("Phase timing unavailable"), text);
		assertTrue(text.contains("GC timing not calibrated yet"), text);
		assertFalse(text.contains("Advice:"));
	}

	@Test
	void theSummaryIsBounded() {
		StutterReport r = report(true, true, 1.0);
		StutterReport big = new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), r.sessionSeconds(), r.gameplaySeconds(),
				r.frames(), r.avgFps(), r.onePercentLowFps(), r.histogramCounts(), r.histogramTimeMs(), r.spikes(), r.lostMs(), r.causes(), r.tags(),
				List.of(new StutterReport.Worst(1, 500, 7, Collections.nCopies(400, "gc:high:FULL"))), r.facts(), r.advice(), true, true, r.hitches());
		assertTrue(StutterSummary.text(big, List.of()).length() <= StutterSummary.LIMIT);
	}

	@Test
	void notesReadBack() {
		Attributor.Note n = Attributor.Note.parse("gc:medium:FULL:STALL");
		assertEquals(Attributor.GC, n.name());
		assertEquals(Attributor.Confidence.MEDIUM, n.confidence());
		assertTrue(n.full() && n.stall() && !n.explicit());
		Attributor.Note contention = Attributor.Note.parse("cpuContention:low:builder");
		assertEquals("builder", contention.group());
		assertEquals(null, Attributor.Note.parse("afterTeleport:context").confidence());
		assertEquals("0:05", StutterSummary.clock(4.6));
		assertEquals("1:01:01", StutterSummary.clock(3661));
	}
}
