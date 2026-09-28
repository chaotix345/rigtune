package io.github.chaotix345.rigtune.client.footprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 3b, X11 and 9 (AC9.4; PLAN "Fixtures"): the "written by 0.5" set src/test/resources/v050-written/ws-w2/:
// awareness.json with acknowledgedStartupRegressions, written by StartupTimes' Got it (through AwarenessStore), and the
// set's expect.json (0.4.0's AwarenessStore keeps the array; compat040 checks it). RIGTUNE_REGENERATE_FIXTURES=1 rewrites
// awareness.json; otherwise the committed one must be exactly what the code writes now.
class V050WrittenWsW2Test {
	private static final String SET = "src/test/resources/v050-written/ws-w2/";
	private static final String AT = "2026-09-20T18:00:00Z";

	@TempDir
	Path config;

	// Five launches of 10 s, then one of 15 s at AT: SLOWER.
	private static void slowerLaunch(Path configDir) {
		StartupTimesStore store = new StartupTimesStore(configDir);
		for (int i = 0; i < 5; i++) {
			store.record(new Run("2026-09-20T1" + i + ":00:00Z", 10_000, "26.2", "0.5.0+mc26.2", 83, "h"));
		}
		store.record(new Run(AT, 15_000, "26.2", "0.5.0+mc26.2", 95, "h2"));
	}

	@Test
	void theWsW2SetIsWhatThisVersionWrites() throws IOException {
		slowerLaunch(config);
		StartupTimes times = new StartupTimes(null, config);
		String key = "startup.regression." + AT;
		times.view();
		times.acknowledge(key);
		Path written = AwarenessStore.file(config);
		Path committed = RepoFiles.resolve(SET);
		if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
			Files.createDirectories(committed);
			Files.copy(written, committed.resolve("awareness.json"), StandardCopyOption.REPLACE_EXISTING);
		}
		assertEquals(Files.readString(written, StandardCharsets.UTF_8), Files.readString(committed.resolve("awareness.json"), StandardCharsets.UTF_8),
				"regenerate with RIGTUNE_REGENERATE_FIXTURES=1");

		// The round trip: the next launch reads the committed acknowledgement back.
		Path reread = config.resolve("reread");
		Files.createDirectories(reread.resolve("rigtune"));
		Files.copy(committed.resolve("awareness.json"), AwarenessStore.file(reread));
		assertEquals(Set.of(key), AwarenessStore.shared(reread).acknowledgedStartupRegressions());
		slowerLaunch(reread);
		StartupTimes next = new StartupTimes(null, reread);
		next.view();
		assertTrue(next.acknowledged(key));

		JsonObject expect = JsonParser.parseString(Files.readString(committed.resolve("expect.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("ws-w2", expect.get("set").getAsString());
		JsonObject check = expect.getAsJsonArray("checks").get(0).getAsJsonObject();
		assertEquals("AwarenessStore", check.get("class").getAsString());
		assertEquals("awareness.json", check.get("file").getAsString());
		assertTrue(check.getAsJsonArray("keeps").toString().contains("\"acknowledgedStartupRegressions\""));
	}
}
