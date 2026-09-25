package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.undo.HistoryStartup;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.V010Fixtures;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC3.3: files 0.1.0 wrote keep working in 0.2 (hand-written from the 0.1.0 record shapes; WS-G adds captured ones).
class MigrationV010Test {
	@TempDir
	Path game;
	Path mods;
	Path config;
	Path pending;
	Path lastApply;
	Journal journal;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(game.resolve("mods"));
		config = Files.createDirectories(game.resolve("config"));
		pending = PendingActions.defaultPath(config);
		lastApply = ApplyResult.defaultPath(config);
		// As the game's journal (ClientJournal): creating history.json runs the legacy import first.
		journal = new Journal(config, "0.2.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		}, () -> HistoryStartup.legacyEntry(config, "26.2"));
	}

	// Review: a malformed 0.1.x file (e.g. a null element) must never stop the game from starting.
	@Test
	void brokenFilesNeverStopTheStartup() throws IOException {
		Files.createDirectories(pending.getParent());
		Files.writeString(lastApply, "{\"finishedAt\":\"x\",\"results\":[null]}");
		Files.writeString(pending, "{\"ops\":[null]}");

		HistoryStartup.run(config, journal, true);
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.a", "1", "2", JournalChange.APPLIED, null)));
		HistoryStartup.run(config, journal, true);
		RigTunePreLaunch.readState(config, false, null);
		RigTunePreLaunch.takeUnseenResult();
		RigTunePreLaunch.takeLeftoverOps();

		assertEquals(List.of("e1"), journal.entries().stream().map(JournalEntry::id).toList());
	}

	// Review: when preLaunch couldn't get the lock (the 0.1.x helper was still running), the import must still happen
	// when something first creates history.json later in the session.
	@Test
	void theImportIsNotLostWhenTheFirstStartCouldNotLock() throws IOException {
		v010Instance();
		install("last-apply.json", lastApply);

		HistoryStartup.run(config, journal, false);
		assertFalse(journal.exists());
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.a", "1", "2", JournalChange.APPLIED, null)));

		assertEquals(List.of(JournalEntry.LEGACY_IMPORT, JournalEntry.APPLY), journal.entries().stream().map(JournalEntry::kind).toList());
	}

	private void install(String fixture, Path target) throws IOException {
		V010Fixtures.install(fixture, target, mods, config);
	}

	// The jars and config the fixtures' ops refer to.
	private void v010Instance() throws IOException {
		TestJars.modJar(mods.resolve("sodium-0.7.0.jar"), "sodium");
		TestJars.modJar(mods.resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX), "sodium");
		TestJars.modJar(mods.resolve("rigtune-0.1.0.jar.disabled"), "rigtune");
		TestJars.modJar(mods.resolve("rigtune-0.2.0+mc26.2.jar"), "rigtune");
		TestJars.modJar(mods.resolve("indium-1.0.36.jar.disabled.1"), "indium");
		TestJars.modJar(mods.resolve("lithium-0.25.4.jar"), "lithium");
		Files.writeString(config.resolve("sodium-options.json"), "{\"performance\":{\"chunk_builder_threads\":0,\"use_entity_culling\":true}}");
	}

	@Test
	void aPendingPlanWithIdsAndGroupsLoads() throws IOException {
		install("pending.json", pending);

		List<Op> ops = PendingActions.load(pending).ops();

		assertEquals(List.of(PendingActions.Type.DISABLE_FILE, PendingActions.Type.ENABLE_FILE, PendingActions.Type.PATCH_JSON), ops.stream().map(Op::type).toList());
		assertEquals(ops.get(0).group(), ops.get(1).group());
		assertEquals("sodium", ops.get(1).modId());
		assertEquals(1, ops.get(1).attempts());
		assertEquals(mods.resolve("sodium-0.7.0.jar").toString(), ops.get(0).path());
	}

	@Test
	void aPendingPlanWrittenBeforeIdsAndGroupsLoads() throws IOException {
		install("pending-pre-groups.json", pending);

		List<Op> ops = PendingActions.load(pending).ops();

		assertEquals(3, ops.size());
		assertTrue(ops.stream().allMatch(op -> op.id() == null && op.group() == null && op.modId() == null && op.attempts() == 0));
	}

	@Test
	void theLastApplyResultLoads() throws IOException {
		install("last-apply.json", lastApply);

		ApplyResult result = ApplyResult.load(lastApply);

		assertEquals(6, result.results().size());
		assertEquals(1, result.failedOps().size());
		assertTrue(result.results().stream().allMatch(r -> r.resultPath() == null));
	}

	@Test
	void rigtuneJsonKeepsTheGoalAndTheLastShownApply() throws IOException {
		install("rigtune.json", ClientState.file(config));

		ClientState state = ClientState.load(config);

		assertEquals(Goal.PERFORMANCE, state.goalOrDefault());
		assertEquals("2026-09-24T18:00:00.000000Z", state.lastShownApply);
	}

	@Test
	void theRulesCacheLoads() throws IOException {
		Path cache = config.resolve("rigtune").resolve("rules-cache.json");
		install("rules-cache.json", cache);

		RulesDocument rules = RulesLoader.loadCache(cache).orElseThrow();

		assertEquals(1, rules.schemaVersion);
		assertFalse(rules.mods.isEmpty());
	}

	@Test
	void preLaunchReportsTheUnseenResultAndTheLeftoverOps() throws IOException {
		install("last-apply.json", lastApply);
		install("pending.json", pending);

		RigTunePreLaunch.readState(config, false, "2026-09-20T00:00:00Z");

		ApplyResult unseen = RigTunePreLaunch.takeUnseenResult();
		assertNotNull(unseen);
		assertEquals("2026-09-24T18:05:00.000000Z", unseen.finishedAt());
		assertEquals(3, RigTunePreLaunch.takeLeftoverOps());

		RigTunePreLaunch.readState(config, false, unseen.finishedAt());
		assertNull(RigTunePreLaunch.takeUnseenResult(), "an apply result already shown isn't shown again");
		RigTunePreLaunch.takeLeftoverOps();
	}

	@Test
	void theV02HelperExecutesAV010Plan() throws IOException {
		v010Instance();
		install("pending.json", pending);

		ApplyResult result = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);

		assertTrue(result.allSucceeded(), result.toString());
		assertTrue(Files.exists(mods.resolve("sodium-0.7.0.jar.disabled")));
		assertTrue(Files.exists(mods.resolve("sodium-0.7.1.jar")));
		assertTrue(Files.readString(config.resolve("sodium-options.json")).contains("\"chunk_builder_threads\": 4"));
		assertFalse(Files.exists(pending));
	}

	@Test
	void theV02HelperExecutesAPlanWrittenBeforeGroups() throws IOException {
		Files.writeString(mods.resolve("starlight-1.1.3.jar"), "s");
		TestJars.modJar(mods.resolve("lithium-0.25.4.jar" + PendingActions.PENDING_SUFFIX), "lithium");
		Files.writeString(config.resolve("sodium-options.json"), "{\"performance\":{\"use_fog_occlusion\":true}}");
		install("pending-pre-groups.json", pending);

		ApplyResult result = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);

		assertTrue(result.allSucceeded(), result.toString());
		assertTrue(Files.exists(mods.resolve("starlight-1.1.3.jar.disabled")));
		assertTrue(Files.exists(mods.resolve("lithium-0.25.4.jar")));
		assertFalse(Files.exists(pending));
	}

	private List<JournalChange> importedChanges() {
		List<JournalEntry> entries = journal.entries();
		assertEquals(1, entries.size(), entries.toString());
		assertEquals(JournalEntry.LEGACY_IMPORT, entries.getFirst().kind());
		return entries.getFirst().changes();
	}

	// AC3.3: the legacy import (review L2).
	@Test
	void theFirstRunImportsTheV010State() throws IOException {
		v010Instance();
		install("last-apply.json", lastApply);
		install("pending.json", pending);

		HistoryStartup.run(config, journal, true);

		List<JournalChange> changes = importedChanges();
		assertTrue(changes.stream().noneMatch(c -> "rigtune".equals(c.modId()) || c.file() != null && c.file().startsWith("rigtune")), changes.toString());
		List<JournalChange> applied = changes.stream().filter(c -> JournalChange.APPLIED.equals(c.status())).toList();
		assertEquals(List.of("indium-1.0.36.jar", "lithium-0.25.4.jar"), applied.stream().map(JournalChange::file).toList());
		assertEquals("indium-1.0.36.jar.disabled.1", applied.getFirst().resultFile());
		assertEquals("indium", applied.getFirst().modId());
		List<JournalChange> staged = changes.stream().filter(c -> JournalChange.STAGED.equals(c.status())).toList();
		assertEquals(PendingActions.load(pending).ops().stream().map(Op::id).toList(), staged.stream().map(JournalChange::opId).toList());
		assertEquals("sodium.performance.chunk_builder_threads", staged.get(2).key());
		assertEquals("0", staged.get(2).before());
		assertEquals("4", staged.get(2).after());
	}

	@Test
	void theImportRunsOnce() throws IOException {
		v010Instance();
		install("last-apply.json", lastApply);

		HistoryStartup.run(config, journal, true);
		HistoryStartup.run(config, journal, true);

		assertEquals(2, importedChanges().size());
	}

	@Test
	void aFirstRunWithNothingToImportStillStartsTheHistory() throws IOException {
		HistoryStartup.run(config, journal, true);

		assertTrue(journal.exists());
		assertTrue(journal.entries().isEmpty());
	}

	// Review L2: while the helper still runs, last-apply.json may be stale; import next time.
	@Test
	void nothingIsImportedWithoutTheLock() throws IOException {
		install("last-apply.json", lastApply);

		HistoryStartup.run(config, journal, false);

		assertFalse(journal.exists());
	}

	// Review H5: a staged change whose op is in neither pending.json nor last-apply.json is lost.
	@Test
	void startupAbandonsLostOpsAndAppliesTheLastResult() throws IOException {
		v010Instance();
		install("pending.json", pending);
		install("last-apply.json", lastApply);
		String keptOp = PendingActions.load(pending).ops().getFirst().id();
		String doneOp = ApplyResult.load(lastApply).results().get(3).op().id();
		journal.record("e1", JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, "sodium", "sodium-0.7.0.jar", JournalChange.STAGED, keptOp, null),
				JournalChange.file(JournalChange.ENABLE, "lithium", "lithium-0.25.4.jar", JournalChange.STAGED, doneOp, null),
				JournalChange.file(JournalChange.ENABLE, "x", "x.jar", JournalChange.STAGED, "lost", null)));

		HistoryStartup.run(config, journal, true);

		assertEquals(List.of(JournalChange.STAGED, JournalChange.APPLIED, JournalChange.ABANDONED),
				journal.entries().stream().filter(e -> e.id().equals("e1")).findFirst().orElseThrow().changes().stream().map(JournalChange::status).toList());
	}

	@Test
	void anUnreadablePendingPlanIsNotReconciled() throws IOException {
		Files.createDirectories(pending.getParent());
		Files.writeString(pending, "{ broken");
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.file(JournalChange.ENABLE, "x", "x.jar", JournalChange.STAGED, "op", null)));

		HistoryStartup.run(config, journal, true);

		assertEquals(JournalChange.STAGED, journal.entries().getFirst().changes().getFirst().status());
	}

	// Files captured from the released v0.1.0 jar by the self-update E2E run (WS-G), when present.
	@Test
	void capturedV010FilesAreReadToo() throws IOException {
		Path captured = V010Fixtures.capturedDir();
		if (captured == null) {
			return;
		}
		for (String name : List.of("pending.json", "last-apply.json", "rigtune.json")) {
			Path source = captured.resolve(name);
			if (!Files.isRegularFile(source)) {
				continue;
			}
			Path target = name.equals("rigtune.json") ? ClientState.file(config) : config.resolve("rigtune").resolve(name);
			Files.createDirectories(target.getParent());
			Files.writeString(target, V010Fixtures.template(Files.readString(source), mods, config));
		}
		if (Files.exists(pending)) {
			PendingActions.load(pending);
		}
		if (Files.exists(lastApply)) {
			ApplyResult.load(lastApply);
		}
		ClientState.load(config);

		HistoryStartup.run(config, journal, true);

		try (Stream<JournalChange> changes = journal.entries().stream().flatMap(e -> e.changes().stream())) {
			assertTrue(changes.noneMatch(c -> "rigtune".equals(c.modId()) || c.file() != null && c.file().startsWith("rigtune")));
		}
	}
}
