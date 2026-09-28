package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.client.ui.HistoryScreen;
import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.ApplyFailures;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 4f, real world RW-20, through preLaunch's own path (HistoryStartup.run, holding the apply lock): the
// user's instance as 0.5 finds it at its first start. The DH pair becomes ABANDONED in history.json and in last-apply.json,
// so History reads "Not applied: installed another way"; the next start changes no byte; without the lock nothing is done.
class HistoryStartupRw20Test {
	@TempDir
	Path instance;

	private Path install() throws IOException {
		Path rigtune = Files.createDirectories(instance.resolve("config").resolve("rigtune"));
		Files.createDirectories(instance.resolve("mods"));
		for (String name : List.of("history.json", "last-apply.json")) {
			String text = Files.readString(RepoFiles.resolve("src/test/resources/realworld/2026-09-28/rigtune/" + name), StandardCharsets.UTF_8)
					.replace("${INSTANCE}", instance.toAbsolutePath().toString().replace('\\', '/'));
			Files.writeString(rigtune.resolve(name), text, StandardCharsets.UTF_8);
		}
		return instance.resolve("config");
	}

	private static Journal journal(Path config) {
		return new Journal(config, "0.5.0+mc26.2", "26.2", (m, e) -> {
			throw new AssertionError(m, e);
		});
	}

	@Test
	void theFirstStartRelabelsTheClaimAndTheNextChangesNothing() throws IOException {
		Path config = install();
		Journal journal = journal(config);

		HistoryStartup.run(config, journal, true);

		ApplyResult lastApply = ApplyResult.load(ApplyResult.defaultPath(config));
		HistoryModel.View view = HistoryModel.build(journal.state(), journal.entries(), ApplyFailures.byOpId(lastApply, List.of(instance.resolve("mods"), config)),
				HistoryModel.Labels.RAW);
		List<HistoryModel.Change> abandoned = view.entries().getFirst().changes().stream().filter(c -> JournalChange.ABANDONED.equals(c.status())).toList();
		assertEquals(List.of("fabric-26.2.jar", "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar"), abandoned.stream().map(HistoryModel.Change::file).toList());
		for (HistoryModel.Change change : abandoned) {
			assertEquals("installed another way", change.failure().reason());
			TranslatableContents text = (TranslatableContents) HistoryScreen.failureText(change).getContents();
			assertEquals("rigtune.history.not_applied", text.getKey());
			assertEquals(List.of("installed another way"), List.of(text.getArgs()));
		}
		assertEquals(15, view.entries().getFirst().changes().stream().filter(c -> JournalChange.APPLIED.equals(c.status()))
				.mapToInt(c -> c.changeIds().size()).sum());
		assertEquals("2026-09-27T01:08:48.292306800Z", lastApply.finishedAt());

		byte[] history = Files.readAllBytes(Journal.file(config));
		byte[] last = Files.readAllBytes(ApplyResult.defaultPath(config));
		HistoryStartup.run(config, journal(config), true);

		assertArrayEquals(history, Files.readAllBytes(Journal.file(config)), "a second start writes nothing");
		assertArrayEquals(last, Files.readAllBytes(ApplyResult.defaultPath(config)));
	}

	// review 11 PERF-1: preLaunch runs on the render thread, and last-apply.json stays until the next helper run, so every
	// start reads history.json once (the reconcile's), whether or not it relabels a claim.
	@Test
	void everyStartReadsTheJournalOnce() throws IOException {
		Path config = install();
		Journal first = journal(config);
		HistoryStartup.run(config, first, true);
		assertEquals(1, first.reads());

		Journal next = journal(config);
		HistoryStartup.run(config, next, true);
		assertEquals(1, next.reads());
	}

	// review 11 APPLY-2: 0.5's own helper reports a disable whose jar the player removed by hand as a bare SKIPPED "already
	// gone"; the starts after it leave that change APPLIED (not "Not applied: installed another way") and its result as is.
	@Test
	void aV050DisableOfAJarAlreadyGoneStaysApplied() throws IOException {
		Path mods = Files.createDirectories(instance.resolve("mods"));
		Path config = Files.createDirectories(instance.resolve("config"));
		Path pending = PendingActions.defaultPath(config);
		Op disable = Op.disableFile(mods.resolve("foo-1.0.jar"));
		PendingActions.create(1, mods, config, List.of(disable)).save(pending);
		journal(config).record("e1", JournalEntry.APPLY,
				List.of(JournalChange.file(JournalChange.DISABLE, "foo", "foo-1.0.jar", JournalChange.STAGED, disable.id(), disable.group())));
		ApplyResult run = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);
		assertEquals(ApplyResult.Status.SKIPPED_ALREADY_DONE, run.results().getFirst().status());
		assertEquals(null, run.results().getFirst().resultPath());
		assertEquals(JournalChange.APPLIED, journal(config).entries().getLast().changes().getFirst().status());

		HistoryStartup.run(config, journal(config), true);
		HistoryStartup.run(config, journal(config), true);

		assertEquals(JournalChange.APPLIED, journal(config).entries().getLast().changes().getFirst().status());
		assertEquals(ApplyResult.Status.SKIPPED_ALREADY_DONE, ApplyResult.load(ApplyResult.defaultPath(config)).results().getFirst().status());
	}

	// preLaunch without the lock (the helper may still be running): nothing is done.
	@Test
	void withoutTheLockNothingIsDone() throws IOException {
		Path config = install();
		Map<Path, byte[]> before = Map.of(Journal.file(config), Files.readAllBytes(Journal.file(config)), ApplyResult.defaultPath(config),
				Files.readAllBytes(ApplyResult.defaultPath(config)));

		HistoryStartup.run(config, journal(config), false);

		before.forEach((file, bytes) -> {
			try {
				assertArrayEquals(bytes, Files.readAllBytes(file), file.toString());
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
	}
}
