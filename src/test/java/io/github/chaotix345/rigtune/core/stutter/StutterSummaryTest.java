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
