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

		assertTrue(staging.stage(ops, "e1"));

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
		assertTrue(staging.stage(first, "e1"));
		List<Op> again = PendingActions.group(Op.disableFile(mods.resolve("x-1.jar")),
				Op.enableFile(mods.resolve("x-2.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("x-2.jar")).withModId("x"));

		assertTrue(staging.stage(again, "e2"));

		assertEquals(List.of("e1"), journal.entries().stream().map(JournalEntry::id).toList());
		assertEquals(2, PendingActions.load(pending).ops().size());
	}

	@Test
	void aSecondConfigChangeRecordsTheStagedValueAsBefore() {
		assertTrue(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"))), "e1"));
		assertTrue(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "6"))), "e2"));

		assertEquals("4", changesOf("e2").getFirst().before());
	}

	@Test
	void aNewerUpdateDiscardsTheReplacedEnable() throws IOException {
		assertTrue(staging.stage(update("sodium-0.7.0.jar", "sodium-0.7.1.jar", "sodium"), "e1"));
		List<Op> newer = PendingActions.group(Op.disableFile(mods.resolve("sodium-0.7.0.jar")),
				Op.enableFile(pendingJar("sodium-0.7.2.jar", "sodium"), mods.resolve("sodium-0.7.2.jar")).withModId("sodium"));

		assertTrue(staging.stage(newer, "e2"));

		assertEquals(List.of(JournalChange.STAGED, JournalChange.DISCARDED), changesOf("e1").stream().map(JournalChange::status).toList());
		assertEquals(List.of("sodium-0.7.2.jar"), changesOf("e2").stream().map(JournalChange::file).toList());
		assertTrue(Files.exists(mods.resolve("sodium-0.7.1.jar" + PendingActions.SUPERSEDED_SUFFIX)));
	}

	@Test
	void stagingWhileTheHelperHoldsTheLockChangesNothing() throws Exception {
		try (HeldLock helper = HeldLock.hold(ApplyLock.defaultPath(config))) {
			assertFalse(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"))), "e1"));
		}
		assertFalse(Files.exists(pending));
		assertFalse(journal.exists());
	}

	@Test
	void discardMarksTheStagedChangesDiscarded() throws IOException {
		assertTrue(staging.stage(update("x-1.jar", "x-2.jar", "x"), "e1"));

		List<Op> dropped = staging.discard();

		assertEquals(2, dropped.size());
		assertFalse(Files.exists(pending));
		assertTrue(changesOf("e1").stream().allMatch(c -> JournalChange.DISCARDED.equals(c.status())));
	}

	@Test
	void discardWhileTheHelperHoldsTheLockReturnsNull() throws Exception {
		assertTrue(staging.stage(update("x-1.jar", "x-2.jar", "x"), "e1"));
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
		assertTrue(staging.stage(ops, "e1"));

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

	@Test
	void unstagingTheLastOpsDeletesPendingJson() throws IOException {
		List<Op> update = update("x-1.jar", "x-2.jar", "x");
		assertTrue(staging.stage(update, "e1"));

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

		assertTrue(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"))), "e1"));

		assertEquals(JournalChange.DISCARDED, changesOf("e0").getFirst().status());
	}
}
