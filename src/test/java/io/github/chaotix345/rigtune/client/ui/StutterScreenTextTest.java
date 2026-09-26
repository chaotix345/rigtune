package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.stutter.Attributor;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import net.minecraft.locale.Language;
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
