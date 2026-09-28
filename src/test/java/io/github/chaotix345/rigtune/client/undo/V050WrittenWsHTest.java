package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The "written by 0.5" set src/test/resources/v050-written/ws-h/ (docs/v0.5/SPEC.md 3b, X11; the set's README): what 0.5's
// first start writes over the user's real instance (src/test/resources/realworld/2026-09-28/), RW-20's relabel: history.json
// with the DH pair ABANDONED and last-apply.json with both results ABANDONED "installed another way", written by
// HistoryStartup.run itself, the instance folder templated back to ${INSTANCE}. By default the test compares with the
// committed files; RIGTUNE_REGENERATE_FIXTURES=1 writes them instead. The pinned 0.3.0 Journal, HistoryModel and ApplyResult
// read them (0.4.0's own classes: compat040 through the set's expect.json, and ws-h.md's local run of the released jar).
class V050WrittenWsHTest {
	private static final String SET = "src/test/resources/v050-written/ws-h/";
	private static final List<String> FILES = List.of("history.json", "last-apply.json");

	@TempDir
	Path dir;

	private String token(Path instance) {
		return instance.toAbsolutePath().toString().replace('\\', '/');
	}

	@Test
	void theCommittedSetIsWhatThe05CodeWrites() throws IOException {
		Path instance = dir.resolve("instance");
		Path rigtune = Files.createDirectories(instance.resolve("config").resolve("rigtune"));
		Files.createDirectories(instance.resolve("mods"));
		for (String name : FILES) {
			Files.writeString(rigtune.resolve(name), Files.readString(RepoFiles.resolve("src/test/resources/realworld/2026-09-28/rigtune/" + name),
					StandardCharsets.UTF_8).replace("${INSTANCE}", token(instance)), StandardCharsets.UTF_8);
		}
		Path config = instance.resolve("config");
		HistoryStartup.run(config, new Journal(config, "0.5.0+mc26.2", "26.2", (m, e) -> {
			throw new AssertionError(m, e);
		}), true);

		Path committed = RepoFiles.resolve(SET);
		for (String name : FILES) {
			String written = Files.readString(rigtune.resolve(name), StandardCharsets.UTF_8).replace(token(instance), "${INSTANCE}").replace("\r\n", "\n");
			if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
				Files.createDirectories(committed);
				Files.writeString(committed.resolve(name), written, StandardCharsets.UTF_8);
			}
			assertEquals(Files.readString(committed.resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n"), written,
					name + ": regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		}
	}

	// The pinned 0.3.0 classes read the set as 0.5 wrote it: the entry, both statuses, both results and their reason.
	@Test
	void v030ReadsTheSet() throws IOException {
		Path config = dir.resolve("reread");
		Path rigtune = Files.createDirectories(config.resolve("rigtune"));
		for (String name : FILES) {
			Files.writeString(rigtune.resolve(name), Files.readString(RepoFiles.resolve(SET + name), StandardCharsets.UTF_8)
					.replace("${INSTANCE}", token(dir.resolve("instance"))), StandardCharsets.UTF_8);
		}
		var old = new io.github.chaotix345.rigtune.v030.core.history.Journal(config, "0.3.0+mc26.2", "26.2", (m, e) -> {
			throw new AssertionError(m, e);
		});
		assertEquals(io.github.chaotix345.rigtune.v030.core.history.Journal.State.OK, old.state());
		assertEquals(1, old.entries().size());
		assertEquals(List.of(JournalChange.ABANDONED, JournalChange.ABANDONED), old.entries().getFirst().changes().stream()
				.map(io.github.chaotix345.rigtune.v030.core.history.JournalChange::status).filter(JournalChange.ABANDONED::equals).toList());
		var lastApply = io.github.chaotix345.rigtune.v030.core.apply.ApplyResult.load(rigtune.resolve("last-apply.json"));
		assertEquals("2026-09-27T01:08:48.292306800Z", lastApply.finishedAt());
		assertEquals(List.of("ABANDONED", "ABANDONED"), lastApply.results().stream().map(r -> r.status().name()).toList());
		assertEquals(List.of("installed another way", "installed another way"), lastApply.results().stream()
				.map(io.github.chaotix345.rigtune.v030.core.apply.ApplyResult.OpResult::message).toList());
		var view = io.github.chaotix345.rigtune.v030.core.history.HistoryModel.build(old.state(), old.entries(), Map.of(),
				io.github.chaotix345.rigtune.v030.core.history.HistoryModel.Labels.RAW);
		assertEquals("rigtune.history.kind.legacy_import", view.entries().getFirst().kindKey());
		assertEquals(ApplyResult.Status.ABANDONED.name(), lastApply.results().getFirst().status().name());
	}
}
