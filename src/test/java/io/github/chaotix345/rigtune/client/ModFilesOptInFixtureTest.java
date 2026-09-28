package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.RepoFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The "written by 0.5" set ws-l1 (src/test/resources/v050-written/README.md; docs/v0.5/SPEC.md 3b, 4e, AC4e.3):
// settings.json as 0.5 writes it with the per-instance opt-in on, from ClientSettings itself. compat040/compat030 feed it
// to the released 0.4.0/0.3.0 (which ignore modFilesByRigTune and drop it on a rewrite: the opt-in turns off, the safe
// side). RIGTUNE_REGENERATE_FIXTURES=1 rewrites it; otherwise the committed file must be exactly what the code writes.
class ModFilesOptInFixtureTest {
	private static final String SET = "src/test/resources/v050-written/ws-l1/";

	@TempDir
	Path dir;

	@Test
	void theFixtureSetIsWhatThisVersionWrites() throws IOException {
		ClientSettings settings = new ClientSettings();
		settings.privacyNoticeShown = true;
		settings.modFilesByRigTune = true;
		settings.save(dir);
		Path written = ClientSettings.file(dir);
		Path committed = RepoFiles.resolve(SET).resolve("settings.json");
		if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
			Files.createDirectories(committed.getParent());
			Files.copy(written, committed, StandardCopyOption.REPLACE_EXISTING);
		}
		assertEquals(Files.readString(committed, StandardCharsets.UTF_8), Files.readString(written, StandardCharsets.UTF_8),
				"regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		Path reread = dir.resolve("reread");
		Files.createDirectories(reread.resolve("rigtune"));
		Files.copy(committed, ClientSettings.file(reread));
		ClientSettings back = ClientSettings.load(reread);
		assertTrue(back.modFilesByRigTune);
		assertTrue(back.privacyNoticeShown);
		assertTrue(Files.readString(committed, StandardCharsets.UTF_8).contains("\"modFilesByRigTune\": true"));
	}

	// Its expect.json names the class 0.4.0 reads it with, and what that must do.
	@Test
	void theSetsExpectationsNameTheSettingsFile() throws IOException {
		String expect = Files.readString(RepoFiles.resolve(SET).resolve("expect.json"), StandardCharsets.UTF_8);
		assertTrue(expect.contains("\"set\": \"ws-l1\""), expect);
		assertTrue(expect.contains("\"class\": \"ClientSettings\"") && expect.contains("\"file\": \"settings.json\""), expect);
	}
}
