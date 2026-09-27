package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 8 (AC8.2, AC8.5): a player is new only with no history entry of any kind, no last-apply.json and no
// pending.json; a history.json that can't be read counts as returning. Checked on the 0.1.0 fixtures and the files
// 0.4.0 wrote (v040-written).
class FirstRunTest {
	private static final Journal.Log QUIET = (message, error) -> {
	};

	private static JournalEntry entry(String id, String kind) {
		return new JournalEntry(id, "2026-09-20T09:00:00Z", kind, "0.4.0", "26.2", null,
				List.of(JournalChange.setting("vanilla.renderDistance", "16", "12", JournalChange.APPLIED, null)));
	}

	@Test
	void newOnlyWithNothingOnDisk() {
		assertTrue(FirstRun.isNew(Journal.State.MISSING, List.of(), false, false));
		assertTrue(FirstRun.isNew(Journal.State.OK, List.of(), false, false), "a history.json with no entries");
		assertFalse(FirstRun.isNew(Journal.State.MISSING, List.of(), true, false), "a last-apply.json");
		assertFalse(FirstRun.isNew(Journal.State.MISSING, List.of(), false, true), "a pending.json");
		assertFalse(FirstRun.isNew(Journal.State.OK, List.of(), true, true));
	}

	@Test
	void anyEntryMakesAReturningPlayer() {
		for (JournalEntry entry : List.of(entry("a", JournalEntry.APPLY), entry("u", JournalEntry.UNDO), entry("b", JournalEntry.BENCHMARK),
				entry("l", JournalEntry.LEGACY_IMPORT), entry(Journal.BASELINE + "x", JournalEntry.APPLY))) {
			assertFalse(FirstRun.isNew(Journal.State.OK, List.of(entry), false, false), entry.kind() + " " + entry.id());
		}
	}

	@Test
	void anUnreadableHistoryIsReturning() {
		for (Journal.State state : List.of(Journal.State.CORRUPT, Journal.State.NEWER, Journal.State.UNREADABLE)) {
			assertFalse(FirstRun.isNew(state, List.of(), false, false), state.name());
		}
	}

	@Test
	void readFromTheConfigFolder(@TempDir Path configDir) throws IOException {
		Journal journal = new Journal(configDir, "0.5.0", "26.2", QUIET);
		assertTrue(FirstRun.isNew(journal, configDir), "nothing written yet");

		Files.createDirectories(Journal.file(configDir).getParent());
		Files.writeString(Journal.file(configDir), "{\"formatVersion\": 1, \"entries\": []}");
		assertTrue(FirstRun.isNew(journal, configDir), "an empty history");

		Files.writeString(Journal.file(configDir), "{not json");
		assertFalse(FirstRun.isNew(journal, configDir), "a corrupt history");
		Files.writeString(Journal.file(configDir), "{\"formatVersion\": 99, \"entries\": []}");
		assertFalse(FirstRun.isNew(journal, configDir), "a newer history");

		Files.delete(Journal.file(configDir));
		assertTrue(journal.update(entries -> List.of(entry("a", JournalEntry.APPLY))));
		assertFalse(FirstRun.isNew(journal, configDir), "an Apply recorded");
	}

	// 0.1.0 left a last-apply.json or pending.json and, when preLaunch couldn't take the apply lock, no history.json yet.
	@Test
	void aV010InstanceIsReturning(@TempDir Path game) throws IOException {
		Path config = game.resolve("config");
		Path mods = game.resolve("mods");
		Journal journal = new Journal(config, "0.5.0", "26.2", QUIET);
		V010Fixtures.install("last-apply.json", ApplyResult.defaultPath(config), mods, config);
		assertFalse(FirstRun.isNew(journal, config), "0.1.0's last-apply.json");
		Files.delete(ApplyResult.defaultPath(config));
		V010Fixtures.install("pending.json", PendingActions.defaultPath(config), mods, config);
		assertFalse(FirstRun.isNew(journal, config), "0.1.0's pending.json");
		Files.delete(PendingActions.defaultPath(config));
		V010Fixtures.install("real-instance/last-apply.json", ApplyResult.defaultPath(config), mods, config);
		assertFalse(FirstRun.isNew(journal, config), "the real 0.1.0 instance's last-apply.json");
	}

	// Each set 0.4.0 wrote, alone in a config folder: returning exactly when it holds a history entry, a last-apply.json
	// or a pending.json (a set with only benchmarks, settings or awareness is a player who never applied anything).
	@Test
	void theFilesZeroFourZeroWrote(@TempDir Path root) throws IOException {
		Path sets = RepoFiles.resolve("src/test/resources/v040-written");
		int returning = 0;
		try (Stream<Path> dirs = Files.list(sets)) {
			for (Path set : dirs.filter(d -> Files.isDirectory(d) && d.getFileName().toString().startsWith("ws-")).sorted().toList()) {
				Path config = root.resolve(set.getFileName().toString()).resolve("config");
				Path rigtune = config.resolve("rigtune");
				Files.createDirectories(rigtune);
				try (Stream<Path> files = Files.list(set)) {
					for (Path file : files.toList()) {
						Files.copy(file, rigtune.resolve(file.getFileName().toString()));
					}
				}
				Journal journal = new Journal(config, "0.5.0", "26.2", QUIET);
				boolean expectReturning = !journal.entries().isEmpty() || Files.exists(ApplyResult.defaultPath(config))
						|| Files.exists(PendingActions.defaultPath(config));
				assertEquals(!expectReturning, FirstRun.isNew(journal, config), set.getFileName().toString());
				returning += expectReturning ? 1 : 0;
			}
		}
		assertTrue(returning >= 2, "the sets with an Apply (ws-a, ws-p) are returning: " + returning);
	}
}
