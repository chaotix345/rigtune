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
// awareness.json with acknowledgedStartupRegressions, written by StartupTimes' Got it (through AwarenessStore);
// startup-times.json whose runs carry RW-19's optional preloadMs, written by StartupTimesStore as StartupTimes records a
// launch; and the set's expect.json (0.4.0's AwarenessStore keeps the array, its StartupTimesStore reads every run;
// compat040 checks them). RIGTUNE_REGENERATE_FIXTURES=1 rewrites both files; otherwise the committed ones must be exactly
// what the code writes now.
class V050WrittenWsW2Test {
	private static final String SET = "src/test/resources/v050-written/ws-w2/";
	private static final String AT = "2026-09-20T18:00:00Z";

	@TempDir
	Path config;

	// Five launches of 9 s plus a warm or cold crash-report setup, then one of 14 s plus a warm one at AT: SLOWER.
	private static void slowerLaunch(Path configDir) {
		StartupTimesStore store = new StartupTimesStore(configDir);
		long[] preload = {1_000, 6_000, 1_200, 5_800, 1_100};
		for (int i = 0; i < preload.length; i++) {
			store.record(new Run("2026-09-20T1" + i + ":00:00Z", 9_000 + preload[i], "26.2", "0.5.0+mc26.2", 83, "h", preload[i]));
		}
		store.record(new Run(AT, 15_000, "26.2", "0.5.0+mc26.2", 95, "h2", 1_000L));
	}

	@Test
	void theWsW2SetIsWhatThisVersionWrites() throws IOException {
		slowerLaunch(config);
		StartupTimes times = new StartupTimes(null, config);
		String key = "startup.regression." + AT;
		times.view();
		times.acknowledge(key);
		assertTrue(times.view().assessment().preloadSubtracted() && times.view().assessment().slower(), "" + times.view().assessment());
		Path committed = RepoFiles.resolve(SET);
		for (Path written : new Path[]{AwarenessStore.file(config), StartupTimesStore.file(config)}) {
			String name = written.getFileName().toString();
			if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
				Files.createDirectories(committed);
				Files.copy(written, committed.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
			assertEquals(Files.readString(written, StandardCharsets.UTF_8), Files.readString(committed.resolve(name), StandardCharsets.UTF_8),
					name + ": regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		}

		// The round trip: the next launch reads the committed acknowledgement back.
		Path reread = config.resolve("reread");
		Files.createDirectories(reread.resolve("rigtune"));
		Files.copy(committed.resolve("awareness.json"), AwarenessStore.file(reread));
		Files.copy(committed.resolve(StartupTimesStore.FILE_NAME), StartupTimesStore.file(reread));
		assertEquals(Set.of(key), AwarenessStore.shared(reread).acknowledgedStartupRegressions());
		assertEquals(1_000L, new StartupTimesStore(reread).runs().getLast().preloadMs());
		StartupTimes next = new StartupTimes(null, reread);
		next.view();
		assertTrue(next.acknowledged(key));

		JsonObject expect = JsonParser.parseString(Files.readString(committed.resolve("expect.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("ws-w2", expect.get("set").getAsString());
		JsonObject check = expect.getAsJsonArray("checks").get(0).getAsJsonObject();
		assertEquals("AwarenessStore", check.get("class").getAsString());
		assertEquals("awareness.json", check.get("file").getAsString());
		assertTrue(check.getAsJsonArray("keeps").toString().contains("\"acknowledgedStartupRegressions\""));
		JsonObject runs = expect.getAsJsonArray("checks").get(1).getAsJsonObject();
		assertEquals("StartupTimesStore", runs.get("class").getAsString());
		assertEquals(StartupTimesStore.FILE_NAME, runs.get("file").getAsString());
		assertEquals(6, runs.get("runs").getAsInt());
	}
}
