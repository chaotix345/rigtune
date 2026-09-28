package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.undo.Staging;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4f, AC4f.2 (coordinator decision): RW-1's rule (an enable that only looks done is RigTune's only when
// its own records prove the rename) against every point a helper can die at. A 0.4+ helper writes, per run: the group's
// record in unfinished-groups.json before its first rename, the renames, pending.json minus the dropped ops, last-apply.json,
// the record's prune, pending.json minus the done ops, the journal (DESIGN "Apply pipeline"). 0.1.0-0.3.x wrote no record
// and pending.json before last-apply.json. Between two runs the game starts and preLaunch reconciles the journal. The only
// wrong outcome left must be the safe one: a jar RigTune did put there, in place and enabled, that History calls "Not
// applied" and Undo won't offer.
class ApplyExecutorCrashReplayTest {
	@TempDir
	Path dir;
	Path mods;
	Path config;
	Path pending;
	Path oldJar;
	Path download;
	Path newJar;
	Op disable;
	Op enable;

	// A kill (PC shutdown, Task Manager): nothing after the throw runs.
	private static final class Killed extends Error {
	}

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
		pending = PendingActions.defaultPath(config);
		oldJar = TestJars.modJar(mods.resolve("sodium-0.7.0.jar"), "sodium");
		download = TestJars.modJar(mods.resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX), "sodium");
		newJar = mods.resolve("sodium-0.7.1.jar");
		List<Op> group = PendingActions.group(Op.disableFile(oldJar), Op.enableFile(download, newJar).withModId("sodium"));
		disable = group.get(0);
		enable = group.get(1);
	}

	private Journal journal() {
		return new Journal(config, "0.4.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	// What an Apply staged: the plan and its journal entry, both changes STAGED.
	private void staged() throws IOException {
		PendingActions.create(1, mods, config, List.of(disable, enable)).save(pending);
		journal().record("e1", JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, "sodium", oldJar.getFileName().toString(), JournalChange.STAGED, disable.id(), disable.group()),
				JournalChange.file(JournalChange.ENABLE, "sodium", newJar.getFileName().toString(), JournalChange.STAGED, enable.id(), enable.group())));
	}

	// Moves, and dies once the record says `op`'s rename happened (review-11 APPLY-5: each rename is marked done in
	// unfinished-groups.json right after it).
	private static ApplyExecutor killedAfter(Op op) {
		return new ApplyExecutor(2, 1, Files::move, millis -> true, ModJars::readModId, (file, content) -> {
			UnfinishedGroups.DURABLE.write(file, content);
			if (marked(content, op.id())) {
				throw new Killed();
			}
		});
	}

	private static boolean marked(String record, String opId) {
		for (JsonElement group : JsonParser.parseString(record).getAsJsonObject().getAsJsonArray("groups")) {
			for (JsonElement rename : group.getAsJsonObject().getAsJsonArray("renames")) {
				JsonObject r = rename.getAsJsonObject();
				if (opId.equals(r.get("op").getAsString()) && r.has("done") && r.get("done").getAsBoolean()) {
					return true;
				}
			}
		}
		return false;
	}

	// Dies right after renaming `source`, before the record can say so.
	private static ApplyExecutor killedRightAfterTheMove(Path source) {
		return new ApplyExecutor(2, 1, (from, to) -> {
			Files.move(from, to);
			if (from.equals(source)) {
				throw new Killed();
			}
		});
	}

	private static ApplyExecutor executor() {
		return new ApplyExecutor(2, 1);
	}

	private ApplyResult run(ApplyExecutor executor) throws IOException {
		return executor.run(PendingActions.load(pending), pending);
	}

	// The next start's preLaunch (HistoryStartup.run): the journal reconciled with pending.json and last-apply.json; then
	// its first rebuild's Staging.dropStale (RW-3), with sodium loaded from the jars the folder has (review 11 APPLY-1).
	private void nextStart() throws IOException {
		Set<String> pendingIds = new HashSet<>();
		if (Files.isRegularFile(pending)) {
			PendingActions.load(pending).ops().stream().map(Op::id).filter(Objects::nonNull).forEach(pendingIds::add);
		}
		Path lastApply = ApplyResult.defaultPath(config);
		List<OpResult> results = Files.isRegularFile(lastApply) ? ApplyResult.load(lastApply).results() : List.of();
		journal().updateExisting(entries -> HistoryUpdates.reconcile(entries, pendingIds, results));
		Set<String> loaded = Set.copyOf(modsListing().stream().filter(name -> name.endsWith(".jar")).toList());
		assertEquals(Staging.StaleDrop.NONE, new Staging(config, pending, List.of(), journal()).dropStale(Map.of("sodium", loaded)));
	}

	private List<String> journalStatuses() {
		return journal().entries().getLast().changes().stream().map(JournalChange::status).toList();
	}

	private List<String> modsListing() throws IOException {
		try (Stream<Path> files = Files.list(mods)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private static List<Status> statuses(ApplyResult result) {
		return result.results().stream().map(OpResult::status).toList();
	}

	private Path record() {
		return UnfinishedGroups.file(config);
	}

	private static final List<String> UPDATED = List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar");

	// What the dead run's last-apply.json said: both renames OK, with where each file went.
	private void lastApplyOfTheDeadRun() throws IOException {
		new ApplyResult(Instant.parse("2026-09-26T10:00:00Z").toString(), List.of(
				new OpResult(disable, Status.OK, "Disabled sodium-0.7.0.jar -> sodium-0.7.0.jar.disabled", mods.resolve("sodium-0.7.0.jar.disabled").toString()),
				new OpResult(enable, Status.OK, "Enabled sodium-0.7.1.jar", newJar.toString()))).save(ApplyResult.defaultPath(config));
	}

	private void assertTheGroupIsDoneAsRigTunes(ApplyResult next) throws IOException {
		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.SKIPPED_ALREADY_DONE), statuses(next));
		assertEquals(mods.resolve("sodium-0.7.0.jar.disabled").toString(), next.results().get(0).resultPath());
		assertEquals(newJar.toString(), next.results().get(1).resultPath());
		assertEquals(UPDATED, modsListing());
		assertFalse(Files.exists(pending));
		assertFalse(Files.exists(record()));
		assertEquals(List.of(JournalChange.APPLIED, JournalChange.APPLIED), journalStatuses());
		assertEquals("sodium-0.7.0.jar.disabled", journal().entries().getLast().changes().getFirst().resultFile());
		assertEquals(Set.of("e1"), UndoPlanner.undoable(journal().entries()));
	}

	// (1) A 0.4+ helper killed after its renames, before last-apply.json: its record completes the group.
	@Test
	void killedAfterTheRenamesBeforeTheResult() throws IOException {
		staged();
		assertThrows(Killed.class, () -> run(killedAfter(enable)));
		assertEquals(UPDATED, modsListing());
		assertTrue(Files.exists(record()));
		assertFalse(Files.exists(ApplyResult.defaultPath(config)));
		nextStart();
		assertEquals(List.of(JournalChange.STAGED, JournalChange.STAGED), journalStatuses());

		ApplyResult next = run(executor());

		assertTheGroupIsDoneAsRigTunes(next);
		assertTrue(next.results().get(1).message().startsWith("Already done earlier"), next.results().get(1).message());
	}

	// (1), then the player presses Discard pending at the next start (review 11, APPLY-1's cause in Discard): the group the
	// record shows started is kept (its renames are done) and the next exit reports it done, instead of History saying
	// DISCARDED over renamed files and an orphaned record.
	@Test
	void aDiscardAtTheNextStartKeepsAStartedGroup() throws IOException {
		staged();
		assertThrows(Killed.class, () -> run(killedAfter(enable)));
		nextStart();

		Staging.Discard discard = new Staging(config, pending, List.of(), journal()).discardPending();

		assertEquals(new Staging.Discard(List.of(), true), discard);
		assertEquals(List.of(JournalChange.STAGED, JournalChange.STAGED), journalStatuses());
		assertTheGroupIsDoneAsRigTunes(run(executor()));
	}

	// review 12 R12APPLY-2: an ungrouped "Disable Foo" the helper did before it was killed in a later group ("op:<id>" in
	// startedGroups) survives Discard pending while the rest is dropped, and the next exit reports it done earlier.
	@Test
	void aDiscardKeepsAnUngroupedDisableTheHelperDid() throws IOException {
		Path foo = TestJars.modJar(mods.resolve("foo-1.0.jar"), "foo");
		Op disableFoo = Op.disableFile(foo);
		PendingActions.create(1, mods, config, List.of(disableFoo, disable, enable)).save(pending);
		journal().record("e1", JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, "foo", "foo-1.0.jar", JournalChange.STAGED, disableFoo.id(), null),
				JournalChange.file(JournalChange.DISABLE, "sodium", oldJar.getFileName().toString(), JournalChange.STAGED, disable.id(), disable.group()),
				JournalChange.file(JournalChange.ENABLE, "sodium", newJar.getFileName().toString(), JournalChange.STAGED, enable.id(), enable.group())));
		assertThrows(TestExecutors.Killed.class, () -> run(TestExecutors.killedAt(oldJar::equals)));
		assertTrue(Files.exists(mods.resolve("foo-1.0.jar.disabled")));

		Staging.Discard discard = new Staging(config, pending, List.of(), journal()).discardPending();

		assertEquals(List.of(disable.id(), enable.id()), discard.dropped().stream().map(Op::id).toList());
		assertTrue(discard.keptGroup());
		assertEquals(List.of(disableFoo.id()), PendingActions.load(pending).ops().stream().map(Op::id).toList());
		ApplyResult next = run(executor());
		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE), statuses(next));
		assertEquals(mods.resolve("foo-1.0.jar.disabled").toString(), next.results().getFirst().resultPath());
		assertEquals(List.of(JournalChange.APPLIED, JournalChange.DISCARDED, JournalChange.DISCARDED), journalStatuses());
	}

	// (2) Killed after last-apply.json, before the record's prune: both records prove it.
	@Test
	void killedAfterTheResultBeforeThePrune() throws IOException {
		staged();
		assertThrows(Killed.class, () -> run(killedAfter(enable)));
		lastApplyOfTheDeadRun();
		nextStart();
		assertEquals(List.of(JournalChange.APPLIED, JournalChange.APPLIED), journalStatuses());

		assertTheGroupIsDoneAsRigTunes(run(executor()));
	}

	// (1b) Killed right after the enable's rename, before the record marks it done: nothing of RigTune's proves that
	// rename, so the group is dropped as installed another way. The folder is right; History says the enable wasn't
	// applied (the safe side: RigTune never claims a jar it can't prove). The disable, marked done, stays done.
	@Test
	void killedBetweenARenameAndItsMarkErrsSafe() throws IOException {
		staged();
		assertThrows(Killed.class, () -> run(killedRightAfterTheMove(download)));
		nextStart();

		ApplyResult next = run(executor());

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.ABANDONED), statuses(next));
		assertEquals(UPDATED, modsListing());
		assertFalse(Files.exists(pending));
	}

	// (3) Killed after the prune, before pending.json lost the done ops: only last-apply.json proves it.
	@Test
	void killedAfterThePruneBeforePendingJson() throws IOException {
		staged();
		assertThrows(Killed.class, () -> run(killedAfter(enable)));
		lastApplyOfTheDeadRun();
		Files.delete(record());
		nextStart();

		ApplyResult next = run(executor());

		assertTheGroupIsDoneAsRigTunes(next);
		assertTrue(next.results().get(1).message().startsWith("Already done earlier"), next.results().get(1).message());
	}

	// (3) twice: the redo dies in the same window. Its own last-apply.json still proves both renames.
	@Test
	void killedTwiceInTheSameWindow() throws IOException {
		staged();
		String plan = Files.readString(pending);
		assertThrows(Killed.class, () -> run(killedAfter(enable)));
		lastApplyOfTheDeadRun();
		Files.delete(record());
		nextStart();
		run(executor());
		Files.writeString(pending, plan);
		nextStart();

		assertTheGroupIsDoneAsRigTunes(run(executor()));
	}

	// The real run, as a 0.4+ helper does it end to end, then a rerun of the same plan (pending.json put back): done.
	@Test
	void aRerunOfAFinishedRunIsDone() throws IOException {
		staged();
		String plan = Files.readString(pending);
		assertEquals(List.of(Status.OK, Status.OK), statuses(run(executor())));
		Files.writeString(pending, plan);

		assertTheGroupIsDoneAsRigTunes(run(executor()));
	}

	// (5) 0.1.0-0.3.x: an old helper killed after its renames and before its pending.json rewrite, with no
	// unfinished-groups.json and no last-apply.json of that run. RigTune has no record of the rename, so the group is
	// dropped as installed another way: the folder stays right (the new jar enabled), History says "Not applied" and Undo
	// won't offer it. The documented residual, and the safe side.
	@Test
	void anOldHelperKilledAfterItsRenamesErrsSafe() throws IOException {
		staged();
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));
		Files.move(download, newJar);

		ApplyResult next = run(executor());

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(next));
		assertTrue(next.results().get(1).message().contains("sodium-0.7.1.jar is already in the mods folder and RigTune has no record of putting it there"),
				next.results().get(1).message());
		assertEquals(UPDATED, modsListing());
		assertFalse(Files.exists(pending));
		assertEquals(List.of(JournalChange.ABANDONED, JournalChange.ABANDONED), journalStatuses());
		assertTrue(UndoPlanner.undoable(journal().entries()).isEmpty());
	}

	// (5) with a last-apply.json of an earlier run (other op ids): no proof either.
	@Test
	void anEarlierRunsResultProvesNothing() throws IOException {
		staged();
		Op other = Op.enableFile(mods.resolve("lithium.jar" + PendingActions.PENDING_SUFFIX), mods.resolve("lithium.jar"));
		new ApplyResult("2026-09-20T10:00:00Z", List.of(new OpResult(other, Status.OK, "Enabled lithium.jar", mods.resolve("lithium.jar").toString())))
				.save(ApplyResult.defaultPath(config));
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));
		Files.move(download, newJar);

		ApplyResult next = run(executor());

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(next));
		assertEquals(UPDATED, modsListing());
	}

	// A bare "already done" of the last run (SKIPPED without a resultPath) is no proof either.
	@Test
	void aBareAlreadyDoneProvesNothing() throws IOException {
		staged();
		new ApplyResult("2026-09-26T10:00:00Z", List.of(new OpResult(disable, Status.SKIPPED_ALREADY_DONE, "sodium-0.7.0.jar is already gone"),
				new OpResult(enable, Status.SKIPPED_ALREADY_DONE, "sodium-0.7.1.jar is already enabled"))).save(ApplyResult.defaultPath(config));
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));
		Files.move(download, newJar);

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(run(executor())));
		assertEquals(UPDATED, modsListing());
	}

	// (6) 0.1.0-0.3.x killed between the two renames: the download is still RigTune's own, so the group completes.
	@Test
	void anOldHelperKilledMidGroupStillCompletes() throws IOException {
		staged();
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));

		ApplyResult next = run(executor());

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.OK), statuses(next));
		assertEquals(UPDATED, modsListing());
		assertFalse(Files.exists(pending));
	}

	// (7) An unreadable last-apply.json proves nothing: the safe side.
	@Test
	void anUnreadableResultProvesNothing() throws IOException {
		staged();
		assertThrows(Killed.class, () -> run(killedAfter(enable)));
		Files.delete(record());
		Files.writeString(ApplyResult.defaultPath(config), "{not json", StandardCharsets.UTF_8);

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(run(executor())));
		assertEquals(UPDATED, modsListing());
	}

	// A 0.1.0-shaped pending.json (no attempts field, no projectId) from the user's instance, the old helper killed after
	// its renames: dropped as installed another way, nothing renamed.
	@Test
	void aV010ShapedPlanKilledAfterItsRenamesErrsSafe() throws IOException {
		String modsPath = mods.toString().replace('\\', '/');
		Files.createDirectories(pending.getParent());
		Files.writeString(pending, """
				{"createdAt":"2026-09-24T23:08:50Z","gamePid":12228,"modsDir":"<mods>","configDir":"<config>","ops":[
				{"type":"DISABLE_FILE","path":"<mods>/sodium-0.7.0.jar","id":"d1","group":"g1"},
				{"type":"ENABLE_FILE","from":"<mods>/sodium-0.7.1.jar.rigtune-pending","to":"<mods>/sodium-0.7.1.jar","id":"e1","group":"g1","modId":"sodium"}]}
				""".replace("<mods>", modsPath).replace("<config>", config.toString().replace('\\', '/')), StandardCharsets.UTF_8);
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));
		Files.move(download, newJar);

		ApplyResult next = run(executor());

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(next));
		assertEquals(UPDATED, modsListing());
		assertFalse(Files.exists(pending));
	}
}
