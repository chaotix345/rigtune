package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.V010Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 2o L1 (audit L1): the "Imported from 0.1" entry is made only from a last-apply.json that 0.1.x wrote,
// when history.json is first created: never from a later RigTune's files (history.json deleted, or a first start that
// couldn't lock), and never from an Apply that happens to create history.json.
class HistoryStartupTest {
	@TempDir
	Path game;
	Path mods;
	Path config;
	Path pending;
	Path sodium;
	Journal journal;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(game.resolve("mods"));
		config = Files.createDirectories(game.resolve("config"));
		pending = PendingActions.defaultPath(config);
		sodium = Files.writeString(config.resolve("sodium-options.json"), "{\"performance\":{\"chunk_builder_threads\":0,\"use_entity_culling\":true}}");
		// As the game's journal (ClientJournal).
		journal = new Journal(config, "0.4.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		}, () -> HistoryStartup.legacyEntry(config, "26.2"));
	}

	private List<String> kinds() {
		return journal.entries().stream().map(JournalEntry::kind).toList();
	}

	private Staging staging() {
		return new Staging(config, pending, List.of(StagingTest.sodiumTarget(sodium)), journal, Duration.ofMillis(200));
	}

	private static Op withId(Op op, String id) {
		return new Op(op.type(), op.from(), op.to(), op.path(), op.patches(), id, op.group(), op.modId(), op.attempts());
	}

	// A fresh install whose first start couldn't lock: the first Apply creates history.json. Its ops are its own.
	@Test
	void aFreshInstallWhoseFirstWriteIsAStagingGetsNoLegacyEntry() throws IOException {
		HistoryStartup.run(config, journal, false);
		Op op = Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"));

		assertNotNull(staging().stage(List.of(op), "apply-1"));

		assertEquals(List.of("apply-1"), journal.entries().stream().map(JournalEntry::id).toList());
		List<JournalChange> changes = journal.entries().getFirst().changes();
		assertEquals(List.of(JournalChange.STAGED), changes.stream().map(JournalChange::status).toList());
		assertEquals(op.id(), changes.getFirst().opId());
	}

	// The player deleted history.json after 0.4's helper ran (an undo's re-enable and disable, a staged op left over).
	@Test
	void aHistoryDeletedAfterALaterHelperRunGetsNoLegacyEntry() throws IOException {
		Files.createDirectories(pending.getParent());
		new ApplyResult("2026-09-25T10:00:00Z", List.of(
				new ApplyResult.OpResult(withId(Op.enableFile(mods.resolve("x.jar.disabled"), mods.resolve("x.jar")).withModId("x"), "op-x"),
						ApplyResult.Status.OK, "Enabled x.jar", mods.resolve("x.jar").toString()),
				new ApplyResult.OpResult(withId(Op.disableFile(mods.resolve("y.jar")), "op-y"), ApplyResult.Status.OK, "Disabled y.jar -> y.jar.disabled",
						mods.resolve("y.jar.disabled").toString()))).save(ApplyResult.defaultPath(config));
		PendingActions.create(1, mods, config, List.of(Op.disableFile(mods.resolve("z.jar")))).save(pending);

		HistoryStartup.run(config, journal, true);

		assertTrue(journal.exists());
		assertEquals(List.of(), kinds());
	}

	// A later helper run that only patched config files (0.1.x only ever patched sodium-options.json with PATCH_JSON).
	@Test
	void aLaterRunThatOnlyPatchedGetsNoLegacyEntry() throws IOException {
		Files.createDirectories(pending.getParent());
		new ApplyResult("2026-09-25T10:00:00Z", List.of(new ApplyResult.OpResult(
				Op.patchToml(config.resolve("DistantHorizons.toml"), Map.of("client.advanced.debugging.rendererMode", "DISABLED")), ApplyResult.Status.OK,
				"Patched 1 value(s) in DistantHorizons.toml"))).save(ApplyResult.defaultPath(config));
		PendingActions.create(1, mods, config, List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4")))).save(pending);

		HistoryStartup.run(config, journal, true);

		assertTrue(journal.exists());
		assertEquals(List.of(), kinds());
	}

	// The user's real 0.1.0 state (src/test/resources/v010/real-instance/): one legacy entry, and none again at the next start.
	@Test
	void theReal010StateIsImportedOnce() throws IOException {
		V010Fixtures.install("real-instance/last-apply.json", ApplyResult.defaultPath(config), mods, config);

		HistoryStartup.run(config, journal, true);
		List<JournalEntry> first = journal.entries();
		HistoryStartup.run(config, journal, true);

		assertEquals(List.of(JournalEntry.LEGACY_IMPORT), kinds());
		assertFalse(first.getFirst().changes().isEmpty());
		assertEquals(first, journal.entries());
	}

	// A 0.1.x upgrade whose first start couldn't lock (the 0.1.x helper still ran): the first Apply creates history.json.
	// The legacy entry holds 0.1.x's leftovers only, as pending.json was before this Apply's merge.
	@Test
	void aStagingThatFirstCreatesTheHistoryKeepsItsOpsOutOfTheLegacyEntry() throws IOException {
		V010Fixtures.install("last-apply.json", ApplyResult.defaultPath(config), mods, config);
		V010Fixtures.install("pending.json", pending, mods, config);
		List<String> leftovers = PendingActions.load(pending).ops().stream().map(Op::id).toList();
		HistoryStartup.run(config, journal, false);
		Op op = Op.patchJson(sodium, Map.of("performance.use_entity_culling", "false"));

		assertNotNull(staging().stage(List.of(op), "apply-1"));

		assertEquals(List.of(JournalEntry.LEGACY_IMPORT, JournalEntry.APPLY), kinds());
		List<String> legacyOps = journal.entries().getFirst().changes().stream().filter(c -> JournalChange.STAGED.equals(c.status()))
				.map(JournalChange::opId).toList();
		assertEquals(leftovers, legacyOps);
		List<JournalChange> own = journal.entries().get(1).changes();
		assertEquals(List.of("sodium.performance.use_entity_culling"), own.stream().map(JournalChange::key).toList());
		assertEquals(op.id(), own.getFirst().opId());
	}
}
