package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 3b, X11 and 4h (AC4h.3's round trip; PLAN "Fixtures"): the "written by 0.5" set
// src/test/resources/v050-written/ws-w/: awareness.json with the options snapshot a clean exit stores (optionsAtExit),
// written through AwarenessStore and OutsideOptions as the stop handler does (stamped with the exit time), and the set's expect.json (0.4.0's
// AwarenessStore keeps the field; compat040 checks it). RIGTUNE_REGENERATE_FIXTURES=1 rewrites awareness.json; otherwise
// the committed one must be exactly what the code writes now.
class V050WrittenWsWTest {
	private static final String SET = "src/test/resources/v050-written/ws-w/";

	@TempDir
	Path config;

	@Test
	void theWsWSetIsWhatThisVersionWrites() throws IOException {
		List<JournalEntry> journal = List.of(new JournalEntry("5e0a1c3d-0000-4000-8000-00000000ws01", "2026-09-20T18:00:00Z", JournalEntry.APPLY, "0.5.0",
				"26.2", null, List.of(JournalChange.setting("vanilla.renderDistance", "16", "12", JournalChange.APPLIED, null),
				JournalChange.setting("vanilla.renderClouds", "true", "fast", JournalChange.APPLIED, null),
				JournalChange.setting("vanilla.maxFps", "260", "120", JournalChange.APPLIED, null))));
		Map<String, String> atExit = Map.of("renderDistance", "12", "renderClouds", "fast", "maxFps", "144", "fullscreen", "false", "gamma", "0.5");
		AwarenessStore store = AwarenessStore.shared(config);
		assertTrue(store.setOptionsAtExit(OutsideOptions.stamped(OutsideOptions.snapshot(OutsideOptions.applied(journal).keySet(), atExit),
				Instant.parse("2026-09-20T18:30:00Z"))));
		Path written = AwarenessStore.file(config);
		Path committed = RepoFiles.resolve(SET);
		if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
			Files.createDirectories(committed);
			Files.copy(written, committed.resolve("awareness.json"), StandardCopyOption.REPLACE_EXISTING);
		}
		assertEquals(Files.readString(written, StandardCharsets.UTF_8), Files.readString(committed.resolve("awareness.json"), StandardCharsets.UTF_8),
				"regenerate with RIGTUNE_REGENERATE_FIXTURES=1");

		// The round trip: this version reads the committed snapshot back, once.
		Path reread = config.resolve("reread");
		Files.createDirectories(reread.resolve("rigtune"));
		Files.copy(committed.resolve("awareness.json"), AwarenessStore.file(reread));
		AwarenessStore again = AwarenessStore.shared(reread);
		Map<String, String> snapshot = Map.of(OutsideOptions.EXIT_AT, "2026-09-20T18:30:00Z", "vanilla.maxFps", "144", "vanilla.renderClouds", "fast",
				"vanilla.renderDistance", "12");
		assertEquals(snapshot, again.optionsAtExit());
		assertEquals(snapshot, again.takeOptionsAtExit());
		assertEquals(Map.of(), again.optionsAtExit(), "consumed");

		JsonObject expect = JsonParser.parseString(Files.readString(committed.resolve("expect.json"), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals("ws-w", expect.get("set").getAsString());
		assertEquals("AwarenessStore", expect.getAsJsonArray("checks").get(0).getAsJsonObject().get("class").getAsString());
		assertTrue(expect.getAsJsonArray("checks").get(0).getAsJsonObject().getAsJsonArray("keeps").toString().contains("\"optionsAtExit\""));
	}
}
