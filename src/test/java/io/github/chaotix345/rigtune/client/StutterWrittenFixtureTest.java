package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import io.github.chaotix345.rigtune.core.stutter.FrameRing;
import io.github.chaotix345.rigtune.core.stutter.GcKind;
import io.github.chaotix345.rigtune.core.stutter.StutterAnalyzer;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The "written by 0.4" fixture set ws-s (PLAN "Wave A" rules; SPEC amendment H-M1): stutter.json and settings.json as
// 0.4 writes them, for WS-H's downgrade run and the released-jar harness. The files come from this test: a capture run
// through StutterAnalyzer and saved by StutterStore, and ClientSettings with the monitor on. RIGTUNE_REGENERATE_FIXTURES=1
// rewrites them; otherwise the committed stutter.json must be exactly what the code writes now.
class StutterWrittenFixtureTest {
	private static final String SET = "src/test/resources/v040-written/ws-s/";
	private static final long MS = 1_000_000L;
	private static final long S = 1_000 * MS;

	@TempDir
	Path dir;

	static StutterReport report() {
		FrameRing ring = new FrameRing(FrameRing.SESSION_FRAMES, FrameRing.SESSION_CANDIDATES);
		StutterRings rings = new StutterRings(0);
		long t0 = 100 * S;
		long now = t0;
		int frame = 0;
		while (now < t0 + 150 * S) {
			long d = frame % 2000 == 999 ? 85 * MS : frame % 3001 == 1500 ? 240 * MS : 8 * MS;
			now += d;
			ring.frame(now, d, now < t0 + 10 * S, 250_000, 900_000, 6 * MS, frame % 2000 == 999 ? 12 : 0);
			if (frame % 2000 == 999) {
				long startMs = (now - d + 10 * MS) / MS - 18;
				rings.gc(now, startMs, startMs + 60, GcKind.classify("G1 Young Generation", "end of minor GC", "G1 Evacuation Pause"), 0);
			}
			frame++;
		}
		rings.gc(t0 + 140 * S, (t0 + 140 * S) / MS - 18 - 40, (t0 + 140 * S) / MS - 18,
				GcKind.classify("G1 Old Generation", "end of major GC", "G1 Compaction Pause"), 2900L << 20);
		rings.event(StutterRings.SAVE_BEGIN, t0 + 60 * S, 0);
		rings.event(StutterRings.SAVE_END, t0 + 61 * S, 0);
		StutterAnalyzer.Result r = StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), rings.snapshot(), t0, now,
				Instant.parse("2026-09-24T19:30:00Z"), StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, true, false));
		return r.report().withAdvice(List.of("ram-stutter-gc-heap"));
	}

	@Test
	void theFixtureSetIsWhatThisVersionWrites() throws IOException {
		assertEquals(JsonStateFile.Saved.OK, new StutterStore(dir).add(report()));
		ClientSettings settings = new ClientSettings();
		settings.privacyNoticeShown = true;
		settings.stutterMonitor = true;
		settings.save(dir);
		Path stutter = StutterStore.file(dir);
		Path settingsFile = ClientSettings.file(dir);
		Path committed = RepoFiles.resolve(SET);
		if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
			Files.createDirectories(committed);
			Files.copy(stutter, committed.resolve("stutter.json"), StandardCopyOption.REPLACE_EXISTING);
			Files.copy(settingsFile, committed.resolve("settings.json"), StandardCopyOption.REPLACE_EXISTING);
		}
		assertEquals(Files.readString(stutter, StandardCharsets.UTF_8), Files.readString(committed.resolve("stutter.json"), StandardCharsets.UTF_8),
				"regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		Path reread = dir.resolve("reread");
		Files.createDirectories(reread.resolve("rigtune"));
		Files.copy(committed.resolve("stutter.json"), StutterStore.file(reread));
		Files.copy(committed.resolve("settings.json"), ClientSettings.file(reread));
		List<StutterReport> sessions = new StutterStore(reread).sessions();
		assertEquals(1, sessions.size());
		StutterReport session = sessions.getFirst();
		assertEquals(List.of("ram-stutter-gc-heap"), session.advice());
		assertTrue(session.spikes().total() >= 3 && session.enoughData() && session.facts().gcOffsetMs() != null, session.toString());
		assertTrue(ClientSettings.load(reread).stutterMonitor);
	}
}
