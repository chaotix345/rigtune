package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 5 (AC5.13), 3b and X11: the "written by 0.5" set src/test/resources/v050-written/ws-s2/: an applied
// stutter fix (render distance 12 -> 10, measuring) and a staged one (Sodium's Chunk Updates -> Deferred), their two
// ordinary `apply` entries in history.json, the staged PATCH_JSON op in pending.json, and stutter-fixes.json, each written
// by this version's own Journal, PendingActions and FixStore. Its expect.json has compat040 check that 0.4.0 reads the
// history and the plan and never opens stutter-fixes.json. RIGTUNE_REGENERATE_FIXTURES=1 rewrites the set; otherwise the
// committed files must be exactly what the code writes now.
class V050WrittenWsS2Test {
	private static final String SET = "src/test/resources/v050-written/ws-s2/";
	private static final List<String> FILES = List.of("history.json", "pending.json", FixStore.FILE_NAME);
	static final String APPLIED_ENTRY = "5c20f1a0-7d3e-4b2a-9c61-0000000000a1";
	static final String STAGED_ENTRY = "5c20f1a0-7d3e-4b2a-9c61-0000000000a2";
	private static final String STAGED_OP = "5c20f1a0-7d3e-4b2a-9c61-0000000000b2";
	private static final String STAGED_GROUP = "5c20f1a0-7d3e-4b2a-9c61-0000000000c2";
	private static final String RD = "vanilla.renderDistance";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";

	@TempDir
	Path dir;

	private static JournalEntry apply(String id, String at, JournalChange change) {
		return new JournalEntry(id, at, JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null, List.of(change));
	}

	private static FixConditions conditions(String key, String value) {
		Map<String, String> settings = new LinkedHashMap<>();
		settings.put(RD, "12");
		settings.put("vanilla.simulationDistance", "10");
		settings.put(DEFER, "ZERO_FRAMES");
		settings.put(key, value);
		return new FixConditions("26.2", "3a91c0e4d2b7", 4096, "g1", 1920, 1080, true, "SINGLEPLAYER", true, true, settings);
	}

	// history.json, pending.json and stutter-fixes.json in configDir/rigtune/, as StutterFixService's Apply leaves them.
	static void write(Path configDir) throws IOException {
		Journal journal = new Journal(configDir, "0.5.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		JournalChange applied = new JournalChange("5c20f1a0-7d3e-4b2a-9c61-0000000000d1", JournalChange.SETTING, RD, "12", "10", null, null, null, null,
				JournalChange.APPLIED, null, null, null);
		JournalChange staged = new JournalChange("5c20f1a0-7d3e-4b2a-9c61-0000000000d2", JournalChange.SETTING, DEFER, "ZERO_FRAMES", "ALWAYS", null, null,
				null, null, JournalChange.STAGED, STAGED_OP, null, null);
		assertTrue(journal.update(entries -> List.of(apply(APPLIED_ENTRY, "2026-09-20T18:00:00Z", applied),
				apply(STAGED_ENTRY, "2026-09-21T18:00:00Z", staged))));
		PendingActions.Op op = new PendingActions.Op(PendingActions.Type.PATCH_JSON, null, null, "${INSTANCE}/config/sodium-options.json",
				Map.of("performance.chunk_build_defer_mode", "ALWAYS"), STAGED_OP, STAGED_GROUP, null, 0);
		new PendingActions("2026-09-21T18:00:01Z", 4242, "${INSTANCE}/mods", "${INSTANCE}/config", List.of(op)).save(PendingActions.defaultPath(configDir));
		FixStore store = FixStore.shared(configDir);
		SessionOutcome before = new SessionOutcome(1, 612.5, 41, 3120.5, 11, 41 / 11.0, 6.1);
		SessionOutcome after = new SessionOutcome(1, 250.0, 6, 410.0, 5, 1.2, 0.7);
		assertTrue(store.add(new FixTracker.Record(APPLIED_ENTRY, "stutter-chunk-loading", RD, "12", "10", Instant.parse("2026-09-20T18:00:00Z"), 17, true,
				FixTracker.State.MEASURING, before, conditions(RD, "12"), after, 1, new FixTracker.Skip("short", List.of()), null, false)));
		assertTrue(store.add(new FixTracker.Record(STAGED_ENTRY, "stutter-sodium-defer", DEFER, "ZERO_FRAMES", "ALWAYS", Instant.parse("2026-09-21T18:00:00Z"),
				17, false, FixTracker.State.STAGED, before, conditions(DEFER, "ZERO_FRAMES"), null, 0, null, null, false)));
	}

	@Test
	void theCommittedSetIsWhatThe05CodeWrites() throws IOException {
		write(dir);
		Path committed = RepoFiles.resolve(SET);
		for (String name : FILES) {
			String written = Files.readString(dir.resolve("rigtune").resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n");
			if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
				Files.createDirectories(committed);
				Files.writeString(committed.resolve(name), written, StandardCharsets.UTF_8);
			}
			assertEquals(Files.readString(committed.resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n"), written,
					name + ": regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		}
		try (var files = Files.list(committed)) {
			assertEquals(FILES.size() + 1, files.count(), "the set holds its three files and expect.json only");
		}
		assertTrue(Files.readString(committed.resolve("expect.json"), StandardCharsets.UTF_8).contains("\"set\": \"ws-s2\""));
	}

	// 0.5 reads its own set back: both records follow their entries (the applied one measuring, the staged one waiting).
	@Test
	void the05CodeReadsTheSetBack() throws IOException {
		Path config = dir.resolve("config");
		Files.createDirectories(config.resolve("rigtune"));
		for (String name : FILES) {
			Files.copy(RepoFiles.resolve(SET + name), config.resolve("rigtune").resolve(name));
		}
		Journal journal = new Journal(config, "0.5.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertEquals(Journal.State.OK, journal.state());
		List<FixTracker.Record> records = FixStore.shared(config).records();
		assertEquals(List.of(FixTracker.State.MEASURING, FixTracker.State.STAGED), records.stream().map(FixTracker.Record::state).toList());
		for (FixTracker.Record r : records) {
			assertEquals(r, FixTracker.advance(r, journal.state(), journal.entries(), null, Instant.parse("2026-09-22T00:00:00Z")), r.entryId());
		}
		assertEquals(1, PendingActions.load(PendingActions.defaultPath(config)).ops().size());
	}
}
