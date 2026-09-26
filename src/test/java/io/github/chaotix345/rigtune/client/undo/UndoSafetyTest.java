package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.TestExecutors;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 2o (WS-G2): Undo through the real stack (stagers, Staging's merge and journal records, UndoService,
// the helper), for what the planner alone can't show: the order the helper applies staged ops in, and what a failed
// rename leaves.
class UndoSafetyTest {
	private static final String THREADS = "sodium.performance.chunk_builder_threads";
	private static final String IN_FILE = "performance.chunk_builder_threads";

	@TempDir
	Path game;
	Path mods;
	Path config;
	Path pending;
	Path sodium;
	List<ConfigTargets.Target> targets;
	Journal journal;
	Staging staging;
	UndoService service;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(game.resolve("mods"));
		config = Files.createDirectories(game.resolve("config"));
		pending = PendingActions.defaultPath(config);
		sodium = Files.writeString(config.resolve("sodium-options.json"), "{\"performance\":{\"chunk_builder_threads\":0}}");
		targets = List.of(StagingTest.sodiumTarget(sodium));
		journal = new Journal(config, "0.4.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		staging = new Staging(config, pending, targets, journal, Duration.ofMillis(200));
		service = new UndoService(staging, journal, this::state, values -> {
			throw new AssertionError("no vanilla values here: " + values);
		});
	}

	// Config values from the Sodium file, staged keys mapped as GameState maps them, the mods folder from disk.
	private UndoPlanner.State state() {
		Map<String, String> sodiumValues = targets.getFirst().reader().read(sodium);
		return new UndoPlanner.State() {
			@Override
			public String setting(String key) {
				return key.startsWith("sodium.") ? sodiumValues.get(key.substring("sodium.".length())) : null;
			}

			@Override
			public boolean immediate(String key) {
				return key.startsWith("vanilla.");
			}

			@Override
			public boolean changeable(String key) {
				return true;
			}

			@Override
			public String keyOf(Op op, String keyInFile) {
				ConfigTargets.Target target = Staging.targetOf(targets, op);
				return target == null ? null : target.prefix() + keyInFile;
			}

			@Override
			public UndoPlanner.Folder folder() {
				return new UndoPlanner.Folder() {
					@Override
					public Path dir() {
						return mods;
					}

					@Override
					public Set<String> files() {
						return Set.copyOf(listing());
					}

					@Override
					public JarInfo jar(String fileName) {
						return JarInfo.read(mods.resolve(fileName));
					}

					@Override
					public Set<String> providedElsewhere() {
						return Set.of();
					}
				};
			}
		};
	}

	private List<String> listing() {
		try (Stream<Path> files = Files.list(mods)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private String threadsInFile() {
		return targets.getFirst().reader().read(sodium).get(IN_FILE);
	}

	// A config Apply as RealController.apply stages it: the file's stager, then Staging (merge + journal record).
	private void applyThreads(String value, String entryId) {
		assertNotNull(staging.stage(SodiumConfigPatcher.stage(sodium, Map.of(IN_FILE, value)).ops(), entryId));
	}

	private void helperRuns() throws IOException {
		new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);
	}

	private void undoLast(String expectedTarget) throws IOException {
		UndoPlan plan = service.plan(false);
		assertEquals(expectedTarget, plan.undoOf(), plan.toString());
		assertTrue(plan.items().stream().noneMatch(i -> i.action() == UndoPlan.Action.SKIP), plan.toString());
		assertFalse(service.undo(plan).busy());
	}

	// --- audit M1 x 2n (docs/v0.4/audit-verification.md M1, "Interaction with 2n"): three alternating Undo last in one start

	@Test
	void threeAlternatingUndoLastInOneStartEndAtTheValueBeforeTheFirstApply() throws IOException {
		applyThreads("4", "e1");
		helperRuns();
		applyThreads("0", "e2");
		helperRuns();
		applyThreads("4", "e3");
		helperRuns();

		undoLastThreeTimesEndsAtTheFirstValue();
	}

	// The same history staged before one restart (profile switches: A -> B -> A before the helper runs).
	@Test
	void threeAlternatingUndoLastAfterThreeStagedAppliesEndAtTheValueBeforeTheFirstApply() throws IOException {
		applyThreads("4", "e1");
		applyThreads("0", "e2");
		applyThreads("4", "e3");
		assertEquals(3, PendingActions.load(pending).ops().size());
		helperRuns();

		undoLastThreeTimesEndsAtTheFirstValue();
	}

	private void undoLastThreeTimesEndsAtTheFirstValue() throws IOException {
		assertEquals("4", threadsInFile());
		assertFalse(Files.exists(pending));
		assertTrue(journal.entries().stream().allMatch(e -> e.changes().stream().allMatch(c -> JournalChange.APPLIED.equals(c.status()))));

		// The next start: Undo last three times before a restart. Each compares against what the previous one staged (2n),
		// and each repeat of an older staged value stays a new op (M1), so the helper ends on the last one.
		undoLast("e3");
		undoLast("e2");
		undoLast("e1");

		List<Op> staged = PendingActions.load(pending).ops();
		assertEquals(List.of("0", "4", "0"), staged.stream().map(op -> op.patches().get(IN_FILE)).toList());
		List<String> undoOpIds = journal.entries().stream().filter(e -> JournalEntry.UNDO.equals(e.kind()))
				.map(e -> e.changes().getFirst().opId()).toList();
		assertEquals(staged.stream().map(Op::id).toList(), undoOpIds, "each undo records its own op");

		helperRuns();

		assertEquals("0", threadsInFile());
		assertTrue(journal.entries().stream().filter(e -> JournalEntry.APPLY.equals(e.kind()))
				.allMatch(e -> e.changes().stream().allMatch(c -> JournalChange.REVERTED.equals(c.status()))), journal.entries().toString());
	}

	// --- audit H5: a mod and the library it depends on are never split into separate undo groups

	private static Path modJar(Path jar, String id, String... depends) throws IOException {
		StringBuilder deps = new StringBuilder();
		for (String dep : depends) {
			deps.append(deps.isEmpty() ? "" : ",").append('"').append(dep).append("\":\"*\"");
		}
		String json = "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\",\"depends\":{" + deps + "}}";
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(json.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	// Apply 1 added the library, Apply 2 the mod that needs it (Sodium, then Sodium Extra), both applied at a restart.
	private void libraryThenDependant() throws IOException {
		modJar(mods.resolve("lib.jar"), "lib");
		modJar(mods.resolve("app.jar"), "app", "lib");
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.file(JournalChange.ENABLE, "lib", "lib.jar", JournalChange.APPLIED, "op1", "g1")));
		journal.record("e2", JournalEntry.APPLY, List.of(JournalChange.file(JournalChange.ENABLE, "app", "app.jar", JournalChange.APPLIED, "op2", "g2")));
	}

	// Never a jar active without a mod it depends on: app.jar only with lib.jar.
	private void neverTheDependantAlone() {
		List<String> files = listing();
		assertFalse(files.contains("app.jar") && !files.contains("lib.jar"), files.toString());
	}

	@Test
	void undoAllOfALibraryAndItsDependantIsOneGroupSoAFailedRenameLeavesBoth() throws IOException {
		libraryThenDependant();

		UndoPlan plan = service.plan(true);
		service.undo(plan);
		assertEquals(1, PendingActions.load(pending).ops().stream().map(Op::group).distinct().count(), PendingActions.load(pending).ops().toString());

		TestExecutors.failingMovesOf(p -> p.getFileName().toString().equals("app.jar")).run(PendingActions.load(pending), pending);

		neverTheDependantAlone();
		assertEquals(List.of("app.jar", "lib.jar"), listing());
	}

	// One Apply that added a mod and its library in two groups (a dependency staged by an earlier Apply and joined, a
	// 0.2.0 entry): Undo last, and the dependant's rename fails.
	@Test
	void undoLastOfOneApplyThatAddedAModAndItsLibraryLeavesBothWhenARenameFails() throws IOException {
		modJar(mods.resolve("lib.jar"), "lib");
		modJar(mods.resolve("app.jar"), "app", "lib");
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.file(JournalChange.ENABLE, "lib", "lib.jar", JournalChange.APPLIED, "op1", "g1"),
				JournalChange.file(JournalChange.ENABLE, "app", "app.jar", JournalChange.APPLIED, "op2", "g2")));

		undoLast("e1");
		TestExecutors.failingMovesOf(p -> p.getFileName().toString().equals("app.jar")).run(PendingActions.load(pending), pending);

		neverTheDependantAlone();
		assertEquals(List.of("app.jar", "lib.jar"), listing());
	}

	@Test
	void undoLastTwiceInOneStartJoinsTheFirstUndosGroupSoAFailedRenameLeavesBoth() throws IOException {
		libraryThenDependant();

		undoLast("e2");
		undoLast("e1");
		List<Op> staged = PendingActions.load(pending).ops();
		assertEquals(2, staged.size());
		assertEquals(1, staged.stream().map(Op::group).distinct().count(), staged.toString());

		TestExecutors.failingMovesOf(p -> p.getFileName().toString().equals("app.jar")).run(PendingActions.load(pending), pending);

		neverTheDependantAlone();
		assertEquals(List.of("app.jar", "lib.jar"), listing());
	}

	// --- audit H1-B and M5: the RigTune screen's "Disable X" is checked before it's staged

	@Test
	void aDisableOfALibraryAnInstalledModNeedsIsRefused() throws IOException {
		modJar(mods.resolve("lib.jar"), "lib");
		modJar(mods.resolve("app.jar"), "app", "lib");

		Text refusal = DisableGuard.refusal(pending, state().folder(), mods.resolve("lib.jar"));

		assertEquals("The game wouldn't start without it: app would be missing lib", refusal == null ? null : refusal.english());
		assertNull(DisableGuard.refusal(pending, state().folder(), mods.resolve("app.jar")));
	}

	// Without the check the disable is merged away as a repeat of the update's own disable, and the update still brings
	// the mod back at the next exit.
	@Test
	void aDisableOfAModWhoseUpdateIsStagedIsRefused() throws IOException {
		modJar(mods.resolve("x-1.jar"), "x");
		modJar(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), "x");
		List<Op> update = PendingActions.group(Op.disableFile(mods.resolve("x-1.jar")),
				Op.enableFile(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("x-2.jar")).withModId("x"));
		assertNotNull(staging.stage(update, "e1"));
		assertNotNull(staging.stage(List.of(Op.disableFile(mods.resolve("x-1.jar"))), "e2"));
		assertEquals(update.stream().map(Op::id).toList(), PendingActions.load(pending).ops().stream().map(Op::id).toList(),
				"the disable alone is absorbed into the update");

		Text refusal = DisableGuard.refusal(pending, state().folder(), mods.resolve("x-1.jar"));

		assertEquals("Another change of it is staged; cancel that first (Undo last or Discard pending)", refusal == null ? null : refusal.english());
	}

	// --- audit M2: Discard pending while an update is half done (the helper disabled x-1.jar, then its enable of x-2.jar
	// failed and so did the rollback) keeps that group, so the next exit finishes it instead of leaving X disabled.

	@Test
	void discardKeepsAnUpdateTheHelperLeftHalfDone() throws IOException {
		modJar(mods.resolve("x-1.jar"), "x");
		modJar(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), "x");
		List<Op> update = PendingActions.group(Op.disableFile(mods.resolve("x-1.jar")),
				Op.enableFile(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("x-2.jar")).withModId("x"));
		assertNotNull(staging.stage(update, "e1"));
		applyThreads("4", "e2");
		Files.move(mods.resolve("x-1.jar"), mods.resolve("x-1.jar.disabled"));

		List<Op> dropped = staging.discard();

		assertEquals(1, dropped.size(), dropped.toString());
		assertEquals(update.stream().map(Op::id).toList(), PendingActions.load(pending).ops().stream().map(Op::id).toList());
		assertTrue(journal.entries().stream().filter(e -> e.id().equals("e1")).allMatch(e -> e.changes().stream()
				.allMatch(c -> JournalChange.STAGED.equals(c.status()))), journal.entries().toString());
		assertTrue(journal.entries().stream().filter(e -> e.id().equals("e2")).allMatch(e -> e.changes().stream()
				.allMatch(c -> JournalChange.DISCARDED.equals(c.status()))), journal.entries().toString());

		helperRuns();

		assertEquals(List.of("x-1.jar.disabled", "x-2.jar"), listing());
		assertEquals("0", threadsInFile());
	}

	@Test
	void discardStillDropsAnUpdateTheHelperHasNotStarted() throws IOException {
		modJar(mods.resolve("x-1.jar"), "x");
		modJar(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), "x");
		assertNotNull(staging.stage(PendingActions.group(Op.disableFile(mods.resolve("x-1.jar")),
				Op.enableFile(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("x-2.jar")).withModId("x")), "e1"));

		assertEquals(2, staging.discard().size());
		assertFalse(Files.exists(pending));
		assertEquals(List.of("x-1.jar", "x-2.jar" + PendingActions.SUPERSEDED_SUFFIX), listing());
	}

	@Test
	void undoLastTwiceInOneStartStillDisablesBothWhenNothingFails() throws IOException {
		libraryThenDependant();

		undoLast("e2");
		undoLast("e1");
		helperRuns();

		assertEquals(List.of("app.jar.disabled", "lib.jar.disabled"), listing());
		assertTrue(journal.entries().stream().filter(e -> JournalEntry.APPLY.equals(e.kind()))
				.allMatch(e -> e.changes().stream().allMatch(c -> JournalChange.REVERTED.equals(c.status()))), journal.entries().toString());
	}
}
