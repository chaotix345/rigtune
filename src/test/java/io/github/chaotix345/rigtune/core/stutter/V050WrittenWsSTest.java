package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2S RW-11 (AC2S.13), 3b and X11: the "written by 0.5" set src/test/resources/v050-written/ws-s/ is what
// this version's StutterStore writes for a session that spanned a settings change: stutter.json with settingsAtStart and
// settingsAtEnd and the settingsChanged tag, from a capture run through StutterAnalyzer. Its expect.json has compat040
// read it with 0.4.0's StutterStore. RIGTUNE_REGENERATE_FIXTURES=1 rewrites the set; otherwise the committed file must be
// exactly what the code writes now.
class V050WrittenWsSTest {
	private static final String SET = "src/test/resources/v050-written/ws-s/";
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
		// The render distance went from 32 to 12 at 70 s (RW-11: the next 10 s of spikes are tagged).
		rings.event(StutterRings.SETTINGS_CHANGED, t0 + 70 * S, 1);
		StutterAnalyzer.Result r = StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), rings.snapshot(), t0, now,
				Instant.parse("2026-09-24T20:15:00Z"), StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, true, false));
		return r.report().withAdvice(List.of()).withSettings(settings("32"), settings("12"));
	}

	// In SettingsWatch's order (a Map.of would write its keys in a different order each run).
	private static Map<String, String> settings(String renderDistance) {
		Map<String, String> out = new LinkedHashMap<>();
		out.put(StutterReport.RENDER_DISTANCE, renderDistance);
		out.put(StutterReport.SIMULATION_DISTANCE, "12");
		out.put(StutterReport.SHADERS, "true");
		return out;
	}

	@Test
	void theWsSSetIsWhatThisVersionWrites() throws IOException {
		assertEquals(JsonStateFile.Saved.OK, new StutterStore(dir).add(report()));
		Path file = StutterStore.file(dir);
		Path committed = RepoFiles.resolve(SET);
		if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
			Files.createDirectories(committed);
			Files.copy(file, committed.resolve("stutter.json"), StandardCopyOption.REPLACE_EXISTING);
		}
		assertEquals(Files.readString(file, StandardCharsets.UTF_8), Files.readString(committed.resolve("stutter.json"), StandardCharsets.UTF_8),
				"regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		try (var files = Files.list(committed)) {
			assertEquals(List.of("expect.json", "stutter.json"), files.map(p -> p.getFileName().toString()).sorted().toList());
		}

		Path reread = dir.resolve("reread");
		Files.createDirectories(StutterStore.file(reread).getParent());
		Files.copy(committed.resolve("stutter.json"), StutterStore.file(reread));
		StutterReport back = new StutterStore(reread).latest();
		assertTrue(back.tags().getOrDefault(Attributor.SETTINGS_CHANGED, 0) > 0, "the set carries the tag: " + back.tags());
		assertEquals(List.of(new StutterReport.SettingChange(StutterReport.RENDER_DISTANCE, "32", "12")), back.settingChanges());
		String expect = Files.readString(committed.resolve("expect.json"), StandardCharsets.UTF_8);
		assertTrue(expect.contains("\"set\": \"ws-s\""), expect);
	}
}
