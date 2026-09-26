package io.github.chaotix345.rigtune.core.footprint;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Run;
import io.github.chaotix345.rigtune.core.model.ModSetHash;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.Saved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The "written by 0.4" startup-times.json (plan review H-M1; set ws-f) for WS-H's downgrade run and released-jar
// harness: written here by StartupTimesStore itself. The committed file must match what the store writes today; to
// regenerate it (after a deliberate format change), delete it and run this test once.
class StartupTimesFixtureTest {
	static final String FIXTURE = "src/test/resources/v040-written/ws-f/" + StartupTimesStore.FILE_NAME;

	@TempDir
	Path configDir;

	@Test
	void theCommittedFixtureIsWhatTheStoreWrites() throws IOException {
		String before = ModSetHash.of(Map.of("fabric-api", "0.161.0+26.2", "rigtune", "0.4.0+mc26.2", "sodium", "0.9.2+mc26.2"));
		String after = ModSetHash.of(Map.of("fabric-api", "0.161.0+26.2", "rigtune", "0.4.0+mc26.2", "sodium", "0.9.2+mc26.2",
				"modmenu", "18.0.0"));
		StartupTimesStore store = new StartupTimesStore(configDir);
		assertEquals(Saved.OK, store.record(new Run("2026-09-23T09:12:40Z", 14517, "26.2", "0.4.0+mc26.2", 3, before)));
		assertEquals(Saved.OK, store.record(new Run("2026-09-24T20:58:10Z", 14343, "26.2", "0.4.0+mc26.2", 3, before)));
		assertEquals(Saved.OK, store.record(new Run("2026-09-25T19:02:44Z", 15125, "26.2", "0.4.0+mc26.2", 4, after)));
		String written = Files.readString(StartupTimesStore.file(configDir), StandardCharsets.UTF_8);

		Path fixture = RepoFiles.resolve(FIXTURE);
		if (!Files.exists(fixture)) {
			Files.createDirectories(fixture.getParent());
			Files.writeString(fixture, written, StandardCharsets.UTF_8);
		}
		assertEquals(written, Files.readString(fixture, StandardCharsets.UTF_8), FIXTURE + " is stale: delete it and rerun this test");
		Path reread = configDir.resolve("reread");
		Files.createDirectories(StartupTimesStore.file(reread).getParent());
		Files.copy(fixture, StartupTimesStore.file(reread));
		StartupTimesStore.Summary summary = StartupTimesStore.summarize(new StartupTimesStore(reread).runs());
		assertEquals(15_125L, summary.lastMs());
		assertEquals(14_517L, summary.medianMs());
		assertTrue(summary.modSetChanged());
	}
}
