package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.HeldLock;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoServiceTest {
	@TempDir
	Path game;
	Path mods;
	Path config;
	Path pending;
	Path sodium;
	Journal journal;
	Staging staging;
	UndoService service;
	final Map<String, String> vanilla = new HashMap<>();
	final List<Map<String, String>> writes = new ArrayList<>();
	boolean vanillaWritesFail;

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(game.resolve("mods"));
		config = Files.createDirectories(game.resolve("config"));
		pending = PendingActions.defaultPath(config);
		sodium = Files.writeString(config.resolve("sodium-options.json"), "{\"performance\":{\"chunk_builder_threads\":0}}");
		journal = new Journal(config, "0.2.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		staging = new Staging(config, pending, List.of(StagingTest.sodiumTarget(sodium)), journal, Duration.ofMillis(200));
		service = new UndoService(staging, journal, this::state, values -> {
			writes.add(values);
			Map<String, Boolean> out = new LinkedHashMap<>();
			values.forEach((key, value) -> {
				if (!vanillaWritesFail) {
					vanilla.put(key, value);
				}
				out.put(key, !vanillaWritesFail);
			});
			return out;
		});
	}

	// Vanilla values from the map, config values from the sodium file, the mods folder from disk.
	private UndoPlanner.State state() {
		Map<String, String> sodiumValues = StagingTest.sodiumTarget(sodium).reader().read(sodium);
		return new UndoPlanner.State() {
			@Override
			public String setting(String key) {
				return key.startsWith("sodium.") ? sodiumValues.get(key.substring("sodium.".length())) : vanilla.get(key);
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
			public UndoPlanner.Folder folder() {
				return new UndoPlanner.Folder() {
					@Override
					public Path dir() {
						return mods;
					}

					@Override
					public Set<String> files() {
						try (Stream<Path> files = Files.list(mods)) {
							return Set.copyOf(files.map(p -> p.getFileName().toString()).toList());
						} catch (IOException e) {
							throw new AssertionError(e);
						}
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

	private List<JournalChange> changesOf(String entryId) {
		return journal.entries().stream().filter(e -> e.id().equals(entryId)).findFirst().orElseThrow().changes();
	}

	private JournalEntry undoEntry() {
		List<JournalEntry> undos = journal.entries().stream().filter(e -> JournalEntry.UNDO.equals(e.kind())).toList();
		assertEquals(1, undos.size(), journal.entries().toString());
		return undos.getFirst();
	}

	private void runHelper() throws IOException {
		new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);
	}

	// AC3.2 / AC3.4: a vanilla change is put back now, a staged Sodium change is dropped from pending.json.
	@Test
	void undoLastPutsVanillaBackNowAndCancelsTheStagedChange() throws IOException {
		JournalChange rd = JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null);
		journal.record("e1", JournalEntry.APPLY, List.of(rd));
		assertTrue(staging.stage(List.of(Op.patchJson(sodium, Map.of("performance.chunk_builder_threads", "4"))), "e1"));
		vanilla.put("vanilla.renderDistance", "16");

		UndoPlan plan = service.plan(false);
		UndoService.Outcome outcome = service.undo(plan);

		assertEquals(List.of(Map.of("vanilla.renderDistance", "12")), writes);
		assertFalse(Files.exists(pending));
		assertEquals(List.of(JournalChange.REVERTED, JournalChange.DISCARDED), changesOf("e1").stream().map(JournalChange::status).toList());
		JournalEntry undo = undoEntry();
		assertEquals("e1", undo.undoOf());
		assertEquals(1, undo.changes().size());
		assertEquals(rd.id(), undo.changes().getFirst().reverts());
		assertEquals(JournalChange.APPLIED, undo.changes().getFirst().status());
		assertEquals(new UndoService.Outcome(false, 1, 0, 1, 0), outcome);
	}

	// AC3.2: mods after a restart, via the post-exit pipeline, and the helper's result marks the originals REVERTED.
	@Test
	void undoingAnUpdateIsStagedAndTheHelperFinishesIt() throws IOException {
		TestJars.modJar(mods.resolve("m-1.jar.disabled"), "m");
		TestJars.modJar(mods.resolve("m-2.jar"), "m");
		JournalChange disabled = JournalChange.file(JournalChange.DISABLE, "m", "m-1.jar", JournalChange.APPLIED, "op1", "g1")
				.withResultFile("m-1.jar.disabled");
		JournalChange enabled = JournalChange.file(JournalChange.ENABLE, "m", "m-2.jar", JournalChange.APPLIED, "op2", "g1");
		journal.record("e1", JournalEntry.APPLY, List.of(disabled, enabled));

		UndoService.Outcome outcome = service.undo(service.plan(true));

		assertEquals(2, outcome.afterRestart());
		List<Op> staged = PendingActions.load(pending).ops();
		assertEquals(2, staged.size());
		assertEquals(1, staged.stream().map(Op::group).distinct().count());
		assertTrue(undoEntry().changes().stream().allMatch(c -> JournalChange.STAGED.equals(c.status())));
		assertEquals(Set.copyOf(staged.stream().map(Op::id).toList()), Set.copyOf(undoEntry().changes().stream().map(JournalChange::opId).toList()));

		runHelper();

		assertTrue(Files.exists(mods.resolve("m-1.jar")));
		assertTrue(Files.exists(mods.resolve("m-2.jar.disabled")));
		assertFalse(Files.exists(mods.resolve("m-2.jar")));
		assertEquals(List.of(JournalChange.REVERTED, JournalChange.REVERTED), changesOf("e1").stream().map(JournalChange::status).toList());
		assertTrue(undoEntry().changes().stream().allMatch(c -> JournalChange.APPLIED.equals(c.status())));
	}

	@Test
	void aConfigSettingIsPutBackAfterARestart() throws IOException {
		Files.writeString(sodium, "{\"performance\":{\"chunk_builder_threads\":4}}");
		JournalChange threads = JournalChange.setting("sodium.performance.chunk_builder_threads", "0", "4", JournalChange.APPLIED, "op1");
		journal.record("e1", JournalEntry.APPLY, List.of(threads));

		service.undo(service.plan(false));

		Op op = PendingActions.load(pending).ops().getFirst();
		assertEquals(Map.of("performance.chunk_builder_threads", "0"), op.patches());
		assertEquals(op.id(), undoEntry().changes().getFirst().opId());

		runHelper();

		assertTrue(Files.readString(sodium).contains("\"chunk_builder_threads\": 0"), Files.readString(sodium));
		assertEquals(JournalChange.REVERTED, changesOf("e1").getFirst().status());
	}

	@Test
	void undoLastAgainTakesTheApplyBefore() throws IOException {
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null)));
		journal.record("e2", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.simulationDistance", "8", "6", JournalChange.APPLIED, null)));
		vanilla.put("vanilla.renderDistance", "16");
		vanilla.put("vanilla.simulationDistance", "6");

		UndoPlan first = service.plan(false);
		service.undo(first);
		UndoPlan second = service.plan(false);

		assertEquals("e2", first.undoOf());
		assertEquals("e1", second.undoOf());
	}

	@Test
	void nothingHappensWhileTheHelperHoldsTheLock() throws Exception {
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null)));
		vanilla.put("vanilla.renderDistance", "16");
		UndoPlan plan = service.plan(false);

		UndoService.Outcome outcome;
		try (HeldLock helper = HeldLock.hold(ApplyLock.defaultPath(config))) {
			outcome = service.undo(plan);
		}

		assertTrue(outcome.busy());
		assertTrue(writes.isEmpty());
		assertEquals(1, journal.entries().size());
	}

	@Test
	void aVanillaWriteThatFailsIsNotRecorded() throws IOException {
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null)));
		vanilla.put("vanilla.renderDistance", "16");
		vanillaWritesFail = true;

		UndoService.Outcome outcome = service.undo(service.plan(false));

		assertEquals(JournalChange.APPLIED, changesOf("e1").getFirst().status());
		assertTrue(undoEntry().changes().isEmpty());
		assertEquals(0, outcome.now());
		assertEquals(1, outcome.skipped());
	}

	// Review: if part of an undo fails, what was done is still journaled.
	@Test
	void aVanillaWriteThatThrowsStillJournalsTheStagedReversal() throws IOException {
		Files.writeString(sodium, "{\"performance\":{\"chunk_builder_threads\":4}}");
		JournalChange rd = JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null);
		JournalChange threads = JournalChange.setting("sodium.performance.chunk_builder_threads", "0", "4", JournalChange.APPLIED, "op1");
		journal.record("e1", JournalEntry.APPLY, List.of(rd, threads));
		vanilla.put("vanilla.renderDistance", "16");
		UndoService throwing = new UndoService(staging, journal, this::state, values -> {
			throw new IllegalStateException("options are busy");
		});

		UndoService.Outcome outcome = throwing.undo(throwing.plan(false));

		assertEquals(1, outcome.afterRestart());
		assertEquals(JournalChange.STAGED, undoEntry().changes().getFirst().status());
		assertEquals(threads.id(), undoEntry().changes().getFirst().reverts());
		assertEquals(JournalChange.APPLIED, changesOf("e1").getFirst().status());
	}

	@Test
	void aStagingFailureLeavesTheVanillaSettingsAlone() throws IOException {
		Files.writeString(sodium, "{\"performance\":{\"chunk_builder_threads\":4}}");
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null),
				JournalChange.setting("sodium.performance.chunk_builder_threads", "0", "4", JournalChange.APPLIED, "op1")));
		vanilla.put("vanilla.renderDistance", "16");
		UndoPlan plan = service.plan(false);
		Files.createDirectories(pending);
		Files.writeString(pending.resolve("blocker"), "x");

		assertThrows(IOException.class, () -> service.undo(plan));

		assertTrue(writes.isEmpty());
		assertTrue(journal.entries().stream().noneMatch(e -> JournalEntry.UNDO.equals(e.kind())));
	}

	@Test
	void itemsShownAsSkippedAreCountedAsSkipped() throws IOException {
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null),
				JournalChange.setting("vanilla.simulationDistance", "8", "6", JournalChange.APPLIED, null)));
		vanilla.put("vanilla.renderDistance", "16");
		vanilla.put("vanilla.simulationDistance", "10");

		UndoService.Outcome outcome = service.undo(service.plan(false));

		assertEquals(new UndoService.Outcome(false, 1, 0, 0, 1), outcome);
	}

	// Review M8: what was shown is re-checked when it's confirmed.
	@Test
	void aChangeTheUserMadeAfterTheListWasShownIsSkipped() throws IOException {
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED, null)));
		vanilla.put("vanilla.renderDistance", "16");
		UndoPlan plan = service.plan(false);
		vanilla.put("vanilla.renderDistance", "8");

		UndoService.Outcome outcome = service.undo(plan);

		assertTrue(writes.isEmpty());
		assertEquals(1, outcome.skipped());
		assertNotNull(undoEntry());
	}
}
