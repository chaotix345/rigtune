package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.history.Journal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// review 12 R12APPLY-5: the title toast's result is read after RW-20's relabel, so at the user's first 0.5 start it
// doesn't count the DH pair as applied while History says "Not applied: installed another way".
class PreLaunchResultTest {
	@TempDir
	Path instance;

	@AfterEach
	void clear() {
		RigTunePreLaunch.takeUnseenResult();
		RigTunePreLaunch.takeLeftoverOps();
	}

	@Test
	void theToastsResultIsTheRelabelledOne() throws IOException {
		Path rigtune = Files.createDirectories(instance.resolve("config").resolve("rigtune"));
		Files.createDirectories(instance.resolve("mods"));
		for (String name : List.of("history.json", "last-apply.json")) {
			String text = Files.readString(RepoFiles.resolve("src/test/resources/realworld/2026-09-28/rigtune/" + name), StandardCharsets.UTF_8)
					.replace("${INSTANCE}", instance.toAbsolutePath().toString().replace('\\', '/'));
			Files.writeString(rigtune.resolve(name), text, StandardCharsets.UTF_8);
		}
		Path config = instance.resolve("config");
		Journal journal = new Journal(config, "0.5.0+mc26.2", "26.2", (m, e) -> {
			throw new AssertionError(m, e);
		});

		RigTunePreLaunch.readAtStart(config, journal, true, false, null, null);

		ApplyResult shown = RigTunePreLaunch.takeUnseenResult();
		assertNotNull(shown);
		assertEquals(List.of(ApplyResult.Status.ABANDONED, ApplyResult.Status.ABANDONED), shown.results().stream().map(ApplyResult.OpResult::status).toList());
		assertEquals("2026-09-27T01:08:48.292306800Z", shown.finishedAt());
	}
}
