package io.github.chaotix345.rigtune.client.undo;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.HeldLock;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingTest {
	@TempDir
	Path game;
	Path mods;
	Path config;
	Path pending;
	Path sodium;
	Journal journal;
	Staging staging;

	static ConfigTargets.Target sodiumTarget(Path file) {
		return new ConfigTargets.Target("sodium.", file, SodiumConfigPatcher::stage, f -> {
			try {
				return Files.exists(f) ? SodiumConfigPatcher.flatten(JsonParser.parseString(Files.readString(f)).getAsJsonObject(), "") : Map.of();
			} catch (IOException e) {
				return Map.of();
			}
		});
	}

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(game.resolve("mods"));
		config = Files.createDirectories(game.resolve("config"));
		pending = PendingActions.defaultPath(config);
		sodium = Files.writeString(config.resolve("sodium-options.json"), "{\"performance\":{\"chunk_builder_threads\":0}}");
		journal = new Journal(config, "0.2.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		staging = new Staging(config, pending, List.of(sodiumTarget(sodium)), journal, Duration.ofMillis(200));
	}

	private Path pendingJar(String name, String modId) throws IOException {
		return TestJars.modJar(mods.resolve(name + PendingActions.PENDING_SUFFIX), modId);
	}

	private List<Op> update(String oldName, String newName, String modId) throws IOException {
		TestJars.modJar(mods.resolve(oldName), modId);
		return PendingActions.group(Op.disableFile(mods.resolve(oldName)),
				Op.enableFile(pendingJar(newName, modId), mods.resolve(newName)).withModId(modId));
	}

	private List<JournalChange> changesOf(String entryId) {
		return journal.entries().stream().filter(e -> e.id().equals(entryId)).findFirst().orElseThrow().changes();
	}

	@Test
	void stagingRecordsTheOpsAsTheyAreInPendingJson() throws IOException {
		List<Op> ops = new ArrayList<>(update("sodium-0.7.0.jar", "sodium-0.7.1.jar", "sodium"));
		ops.add(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4")));

		assertNotNull(staging.stage(ops, "e1"));

		List<Op> staged = PendingActions.load(pending).ops();
		List<JournalChange> changes = changesOf("e1");
		assertEquals(staged.stream().map(Op::id).toList(), changes.stream().map(JournalChange::opId).toList());
		assertEquals("sodium", changes.get(0).modId(), "a disable's mod id is read from its jar");
		assertEquals(staged.get(0).group(), changes.get(0).group());
		assertEquals("sodium.performance.chunk_builder_threads", changes.get(2).key());
		assertEquals("0", changes.get(2).before());
		assertEquals("4", changes.get(2).after());
		assertTrue(changes.stream().allMatch(c -> JournalChange.STAGED.equals(c.status())));
		assertEquals(JournalEntry.APPLY, journal.entries().getFirst().kind());
	}

	@Test
	void stagingTheSameChangeAgainRecordsNothingNew() throws IOException {
		List<Op> first = update("x-1.jar", "x-2.jar", "x");
		assertNotNull(staging.stage(first, "e1"));
		List<Op> again = PendingActions.group(Op.disableFile(mods.resolve("x-1.jar")),
				Op.enableFile(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("x-2.jar")).withModId("x"));

		assertNotNull(staging.stage(again, "e2"));

		assertEquals(List.of("e1"), journal.entries().stream().map(JournalEntry::id).toList());
		assertEquals(2, PendingActions.load(pending).ops().size());
	}

	@Test
	void aSecondConfigChangeRecordsTheStagedValueAsBefore() {
		assertNotNull(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"))), "e1"));
		assertNotNull(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "6"))), "e2"));

		assertEquals("4", changesOf("e2").getFirst().before());
	}

	@Test
	void aNewerUpdateDiscardsTheReplacedEnable() throws IOException {
		assertNotNull(staging.stage(update("sodium-0.7.0.jar", "sodium-0.7.1.jar", "sodium"), "e1"));
		List<Op> newer = PendingActions.group(Op.disableFile(mods.resolve("sodium-0.7.0.jar")),
				Op.enableFile(pendingJar("sodium-0.7.2.jar", "sodium"), mods.resolve("sodium-0.7.2.jar")).withModId("sodium"));

		assertNotNull(staging.stage(newer, "e2"));

		assertEquals(List.of(JournalChange.STAGED, JournalChange.DISCARDED), changesOf("e1").stream().map(JournalChange::status).toList());
		assertEquals(List.of("sodium-0.7.2.jar"), changesOf("e2").stream().map(JournalChange::file).toList());
		assertTrue(Files.exists(mods.resolve("sodium-0.7.1.jar" + PendingActions.SUPERSEDED_SUFFIX)));
	}

	// Review: a staged re-enable (an undo's) of x.jar.disabled replaced by a newer enable must not rename the user's
	// disabled jar; only downloads (.rigtune-pending) are ever retired.
	@Test
	void onlyDownloadsAreRetiredWhenAnEnableIsReplaced() throws IOException {
		Path disabled = TestJars.modJar(mods.resolve("x-1.jar.disabled"), "x");
		assertNotNull(staging.stage(List.of(Op.enableFile(disabled, mods.resolve("x-1.jar")).withModId("x")), "u1"));

		assertNotNull(staging.stage(List.of(Op.enableFile(pendingJar("x-2.jar", "x"), mods.resolve("x-2.jar")).withModId("x")), "e2"));

		assertTrue(Files.exists(disabled));
		assertFalse(Files.exists(mods.resolve("x-1.jar.disabled" + PendingActions.SUPERSEDED_SUFFIX)));
	}

	@Test
	void stagingWhileTheHelperHoldsTheLockChangesNothing() throws Exception {
		try (HeldLock helper = HeldLock.hold(ApplyLock.defaultPath(config))) {
			assertNull(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"))), "e1"));
		}
		assertFalse(Files.exists(pending));
		assertFalse(journal.exists());
	}

	@Test
	void discardMarksTheStagedChangesDiscarded() throws IOException {
		assertNotNull(staging.stage(update("x-1.jar", "x-2.jar", "x"), "e1"));

		List<Op> dropped = staging.discard();

		assertEquals(2, dropped.size());
		assertFalse(Files.exists(pending));
		assertTrue(changesOf("e1").stream().allMatch(c -> JournalChange.DISCARDED.equals(c.status())));
	}

	@Test
	void discardWhileTheHelperHoldsTheLockReturnsNull() throws Exception {
		assertNotNull(staging.stage(update("x-1.jar", "x-2.jar", "x"), "e1"));
		try (HeldLock helper = HeldLock.hold(ApplyLock.defaultPath(config))) {
			assertNull(staging.discard());
		}
		assertTrue(Files.exists(pending));
	}

	@Test
	void unstagingRemovesTheWholeGroupAndRetiresItsDownloads() throws IOException {
		List<Op> update = update("x-1.jar", "x-2.jar", "x");
		Op other = Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"));
		List<Op> ops = new ArrayList<>(update);
		ops.add(other);
		assertNotNull(staging.stage(ops, "e1"));

		List<Op> removed;
		try (ApplyLock lock = staging.lock()) {
			assertNotNull(lock);
			removed = staging.unstageLocked(List.of(update.get(1).id()));
		}

		assertEquals(update.stream().map(Op::id).toList(), removed.stream().map(Op::id).toList());
		assertEquals(List.of(other.id()), PendingActions.load(pending).ops().stream().map(Op::id).toList());
		assertTrue(Files.exists(mods.resolve("x-2.jar" + PendingActions.SUPERSEDED_SUFFIX)));
		assertEquals(List.of(JournalChange.DISCARDED, JournalChange.DISCARDED, JournalChange.STAGED),
				changesOf("e1").stream().map(JournalChange::status).toList());
	}

	// Re-check of review 4: a staged update of a mod that now has an update of its own waiting in mods/update/ would race
	// that mod's updater at exit, so it is unstaged (whole group, download retired, journal DISCARDED).
	@Test
	void aStagedUpdateOfAModWithAQueuedUpdateOfItsOwnIsUnstaged() throws IOException {
		List<Op> dh = update("dh-1.jar", "dh-2.jar", "distanthorizons");
		List<Op> other = update("y-1.jar", "y-2.jar", "y");
		List<Op> ops = new ArrayList<>(dh);
		ops.addAll(other);
		assertNotNull(staging.stage(ops, "e1"));

		List<Op> dropped = staging.dropQueuedUpdates(Set.of("distanthorizons", "unrelated"), Set.of("distanthorizons", "y", "unrelated"));

		assertEquals(dh.stream().map(Op::id).toList(), dropped.stream().map(Op::id).toList());
		assertEquals(other.stream().map(Op::id).toList(), PendingActions.load(pending).ops().stream().map(Op::id).toList());
		assertTrue(Files.exists(mods.resolve("dh-2.jar" + PendingActions.SUPERSEDED_SUFFIX)));
		assertFalse(Files.exists(mods.resolve("dh-2.jar" + PendingActions.PENDING_SUFFIX)));
		assertTrue(Files.exists(mods.resolve("dh-1.jar")));
		assertEquals(List.of(JournalChange.DISCARDED, JournalChange.DISCARDED, JournalChange.STAGED, JournalChange.STAGED),
				changesOf("e1").stream().map(JournalChange::status).toList());
	}

	// An enable staged without a mod id (by 0.1.0, or an undo of a jar it couldn't read): the id comes from the jar itself,
	// as ApplyExecutor reads it at apply time (review 5, apply-safety-1, defensive).
	@Test
	void anEnableStagedWithoutAModIdIsMatchedByItsJar() throws IOException {
		TestJars.modJar(mods.resolve("dh-1.jar"), "distanthorizons");
		List<Op> dh = PendingActions.group(Op.disableFile(mods.resolve("dh-1.jar")),
				Op.enableFile(pendingJar("dh-2.jar", "distanthorizons"), mods.resolve("dh-2.jar")));
		assertNotNull(staging.stage(dh, "e1"));

		List<Op> dropped = staging.dropQueuedUpdates(Set.of("distanthorizons"), Set.of("distanthorizons"));

		assertEquals(dh.stream().map(Op::id).toList(), dropped.stream().map(Op::id).toList());
		assertFalse(Files.exists(pending));
		// The notice names the mod from the dropped ops (re-check of review 6): the enable carries the id read from its jar.
		assertEquals("distanthorizons", dropped.stream().filter(op -> op.type() == PendingActions.Type.ENABLE_FILE)
				.findFirst().orElseThrow().modId());
	}

	@Test
	void nothingIsUnstagedWithoutAQueuedUpdateOfAStagedMod() throws Exception {
		List<Op> x = update("x-1.jar", "x-2.jar", "x");
		assertNotNull(staging.stage(x, "e1"));
		String before = Files.readString(pending);

		assertEquals(List.of(), staging.dropQueuedUpdates(Set.of(), Set.of("x")));
		assertEquals(List.of(), staging.dropQueuedUpdates(Set.of("y"), Set.of("x", "y")));
		try (HeldLock helper = HeldLock.hold(ApplyLock.defaultPath(config))) {
			assertNull(staging.dropQueuedUpdates(Set.of("x"), Set.of("x")));
		}

		assertEquals(before, Files.readString(pending));
		assertTrue(changesOf("e1").stream().allMatch(c -> JournalChange.STAGED.equals(c.status())));
	}

	// SPEC 3a: only RigTune's change to a LOADED mod whose own update is queued in mods/update/ is dropped; a stale jar
	// there must not cancel an addition or an undo's re-enable of a mod that isn't loaded, on every rebuild.
	@Test
	void anAdditionOfAnUnloadedModWithAStaleQueuedJarStaysStaged() throws IOException {
		assertNotNull(staging.stage(PendingActions.group(Op.enableFile(pendingJar("x-1.jar", "x"), mods.resolve("x-1.jar")).withModId("x")), "e1"));
		String before = Files.readString(pending);

		assertEquals(List.of(), staging.dropQueuedUpdates(Set.of("x"), Set.of("sodium")));

		assertEquals(before, Files.readString(pending));
		assertTrue(Files.exists(mods.resolve("x-1.jar" + PendingActions.PENDING_SUFFIX)));
		assertTrue(changesOf("e1").stream().allMatch(c -> JournalChange.STAGED.equals(c.status())));
	}

	@Test
	void anUndoReEnableOfAnUnloadedModStaysStaged() throws IOException {
		Path disabled = TestJars.modJar(mods.resolve("x-1.jar.disabled"), "x");
		assertNotNull(staging.stage(List.of(Op.enableFile(disabled, mods.resolve("x-1.jar")).withModId("x")), "u1"));

		assertEquals(List.of(), staging.dropQueuedUpdates(Set.of("x"), Set.of()));

		assertEquals(1, PendingActions.load(pending).ops().size());
		assertTrue(Files.exists(disabled));
	}

	// Plan review A-L1: an undo that re-enables a loaded mod with a queued update would race it too, so it goes (the notice
	// says "change", not "update").
	@Test
	void anUndoReEnableOfALoadedModWithAQueuedUpdateIsDropped() throws IOException {
		TestJars.modJar(mods.resolve("x-2.jar"), "x");
		Path disabled = TestJars.modJar(mods.resolve("x-1.jar.disabled"), "x");
		List<Op> undo = PendingActions.group(Op.disableFile(mods.resolve("x-2.jar")), Op.enableFile(disabled, mods.resolve("x-1.jar")).withModId("x"));
		assertNotNull(staging.stage(undo, "u1"));

		List<Op> dropped = staging.dropQueuedUpdates(Set.of("x"), Set.of("x"));

		assertEquals(undo.stream().map(Op::id).toList(), dropped.stream().map(Op::id).toList());
		assertFalse(Files.exists(pending));
		assertTrue(Files.exists(disabled), "only downloads are retired");
	}

	// Plan review A-H1: an addition that relies on an update is in the update's group, so it goes with it.
	@Test
	void anAdditionJoinedToADroppedUpdateIsDroppedWithIt() throws IOException {
		List<Op> update = update("a-1.jar", "a-2.jar", "a");
		Op addition = Op.enableFile(pendingJar("b-1.jar", "b"), mods.resolve("b-1.jar")).withModId("b").inGroup(update.getFirst().group());
		List<Op> ops = new ArrayList<>(update);
		ops.add(addition);
		assertNotNull(staging.stage(ops, "e1"));

		List<Op> dropped = staging.dropQueuedUpdates(Set.of("a"), Set.of("a"));

		assertEquals(ops.stream().map(Op::id).toList(), dropped.stream().map(Op::id).toList());
		assertTrue(Files.exists(mods.resolve("b-1.jar" + PendingActions.SUPERSEDED_SUFFIX)));
	}

	// Plan review A-M1: the client records, per recommendation, the ids its ops have in pending.json after the merge.
	@Test
	void stagingReturnsTheMergeWithTheSurvivingOpIds() throws IOException {
		List<Op> first = update("x-1.jar", "x-2.jar", "x");
		Staging.Merge merge = staging.stage(first, "e1");
		assertNotNull(merge);
		assertEquals(Map.of(first.get(0).id(), first.get(0).id(), first.get(1).id(), first.get(1).id()), merge.merged().survivingIds());

		List<Op> again = PendingActions.group(Op.disableFile(mods.resolve("x-1.jar")),
				Op.enableFile(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("x-2.jar")).withModId("x"));
		Staging.Merge repeat = staging.stage(again, "e2");

		assertNotNull(repeat);
		assertEquals(Map.of(again.get(0).id(), first.get(0).id(), again.get(1).id(), first.get(1).id()), repeat.merged().survivingIds());
	}

	@Test
	void unstagingTheLastOpsDeletesPendingJson() throws IOException {
		List<Op> update = update("x-1.jar", "x-2.jar", "x");
		assertNotNull(staging.stage(update, "e1"));

		try (ApplyLock lock = staging.lock()) {
			staging.unstageLocked(List.of(update.get(0).id()));
		}

		assertFalse(Files.exists(pending));
	}

	// A copied instance: the staged op for the other instance's folders is dropped, so its change is DISCARDED.
	@Test
	void opsDroppedForAnotherInstanceAreDiscarded() throws IOException {
		Op foreign = Op.disableFile(game.resolveSibling("other-instance").resolve("mods").resolve("y.jar"));
		PendingActions.create(1, mods, config, List.of(foreign)).save(pending);
		journal.record("e0", JournalEntry.APPLY, List.of(JournalChange.file(JournalChange.DISABLE, "y", "y.jar", JournalChange.STAGED, foreign.id(), null)));

		assertNotNull(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"))), "e1"));

		assertEquals(JournalChange.DISCARDED, changesOf("e0").getFirst().status());
	}
}
