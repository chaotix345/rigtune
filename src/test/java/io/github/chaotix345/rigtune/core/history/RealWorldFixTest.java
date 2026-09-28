package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4f, RW-1 (docs/research/v0.5/real-world-2026-09-27.md §2): the user's 0.1.0 -> 0.4.0 instance on
// 2026-09-27, where the Modrinth App had installed DH 3.3.2 under the exact name RigTune 0.1.0 had staged, and the 0.4.0
// helper called that jar RigTune's ("is already enabled"). An enable that only looks done counts as RigTune's only when
// RigTune's own records prove the rename; otherwise the group is dropped as installed another way. 0.1.0's pending.json
// in that instance is WS-L1's templated fixture (src/test/resources/realworld/, RealWorldFixtures.seededPending).
class RealWorldFixTest {
	static final String DH = RealWorldFixtures.DH;
	static final String GROUP = "7b8043de-f5c3-4881-8309-27265240f39e";
	static final String ENABLE_ID = "db7f487d-6371-43c5-944e-c053428ad70d";

	@TempDir
	Path dir;

	private Path mods() throws IOException {
		return Files.createDirectories(dir.resolve("mods"));
	}

	private Path config() throws IOException {
		return Files.createDirectories(dir.resolve("config"));
	}

	private Path writePending(String json) throws IOException {
		Path pending = PendingActions.defaultPath(config());
		Files.createDirectories(pending.getParent());
		Files.writeString(pending, json.replace("<mods>", mods().toString().replace('\\', '/')).replace("<config>", config().toString().replace('\\', '/')),
				StandardCharsets.UTF_8);
		return pending;
	}

	private static final StagedChanges.ConfigKeys NO_KEYS = new StagedChanges.ConfigKeys() {
		@Override
		public String key(Op op, String keyInFile) {
			return null;
		}

		@Override
		public String current(Op op, String keyInFile) {
			return null;
		}
	};

	// history.json as 0.4.0's first start made it: the legacy import with 0.1.0's leftover ops STAGED.
	private Journal legacyJournal(Path pending) throws IOException {
		JournalEntry legacy = LegacyImport.entry(null, PendingActions.load(pending), ModJars::modIdOf, ModJars::nameOf, NO_KEYS, "26.2");
		Journal journal = new Journal(config(), "0.4.0+mc26.2", "26.2", (m, e) -> {
		}, () -> legacy);
		journal.update(entries -> entries);
		return journal;
	}

	private static List<Status> statuses(ApplyResult result) {
		return result.results().stream().map(OpResult::status).toList();
	}

	private static List<String> journalStatuses(Journal journal) {
		return journal.entries().getLast().changes().stream().map(JournalChange::status).toList();
	}

	private List<String> modsListing() throws IOException {
		try (Stream<Path> files = Files.list(mods())) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	// AC4f.1: the real run (helper.log 2026-09-27T01:08:48Z): the app's DH 3.3.2 at the staged name, fabric-26.2.jar and
	// the download gone. 0.4.0 reported both ops SKIPPED_ALREADY_DONE and History called them RigTune's.
	@Test
	void theRealCaseIsDroppedAsInstalledAnotherWay() throws IOException {
		TestJars.modJar(mods().resolve(DH), "distanthorizons", "Distant Horizons");
		Path pending = RealWorldFixtures.seededPending(dir);
		Journal journal = legacyJournal(pending);
		List<String> before = modsListing();

		ApplyResult result = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(result));
		assertTrue(result.results().get(1).message().contains(DH + " is already in the mods folder and RigTune has no record of putting it there,"
				+ " so it was installed another way"), result.results().get(1).message());
		assertEquals(List.of(JournalChange.ABANDONED, JournalChange.ABANDONED), journalStatuses(journal));
		assertTrue(UndoPlanner.undoable(journal.entries()).isEmpty());
		assertEquals(before, modsListing());
		assertFalse(Files.exists(pending));
	}

	// The control: with RigTune's download still there the same instance was dropped already (the duplicate check).
	@Test
	void withTheDownloadStillThereTheGroupIsDroppedAsBefore() throws IOException {
		TestJars.modJar(mods().resolve(DH), "distanthorizons", "Distant Horizons");
		TestJars.modJar(mods().resolve(DH + PendingActions.PENDING_SUFFIX), "distanthorizons", "Distant Horizons");
		Path pending = RealWorldFixtures.seededPending(dir);

		ApplyResult result = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(result));
		assertTrue(result.results().get(1).message().startsWith("Dropped: mod distanthorizons is already installed as " + DH), result.results().get(1).message());
		assertTrue(Files.exists(mods().resolve(DH + PendingActions.SUPERSEDED_SUFFIX)));
	}

	// rw §2.3 variant E: an addition 0.1.0 staged (Ixeris), whose download the player deleted before installing the mod
	// through the app under the same Modrinth file name. 0.4.0 called it RigTune's, and Undo last would disable the app's jar.
	@Test
	void anAdditionInstalledThroughTheAppIsNotClaimed() throws IOException {
		String ixeris = "Ixeris-4.6.8+26.2-fabric.jar";
		TestJars.modJar(mods().resolve(ixeris), "ixeris", "Ixeris");
		Path pending = writePending("""
				{"createdAt":"2026-09-24T23:08:50Z","gamePid":1,"modsDir":"<mods>","configDir":"<config>","ops":[
				{"type":"ENABLE_FILE","from":"<mods>/Ixeris-4.6.8+26.2-fabric.jar.rigtune-pending","to":"<mods>/Ixeris-4.6.8+26.2-fabric.jar","id":"a1","group":"g1","modId":"ixeris","attempts":1}]}
				""");
		Journal journal = legacyJournal(pending);

		ApplyResult result = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);
		UndoPlanner.Result last = UndoPlanner.plan(journal.entries(), List.of(), new UndoPlannerTest.FakeState().jar(ixeris, "ixeris"), false);

		assertEquals(List.of(Status.ABANDONED), statuses(result));
		assertEquals(List.of(JournalChange.ABANDONED), journalStatuses(journal));
		assertTrue(last.script().fileOps().isEmpty(), last.script().fileOps().toString());
		assertTrue(Files.exists(mods().resolve(ixeris)));
	}

	// A 0.4+ helper killed after its rename: the record in unfinished-groups.json proves it, so it stays done.
	@Test
	void aRecordedRenameStaysDone() throws IOException {
		TestJars.modJar(mods().resolve(DH), "distanthorizons", "Distant Horizons");
		Path pending = RealWorldFixtures.seededPending(dir);
		Path record = config().resolve("rigtune").resolve("unfinished-groups.json");
		String modsPath = mods().toString().replace('\\', '/');
		Files.writeString(record, "{\"groups\":[{\"group\":\"" + GROUP + "\",\"renames\":[{\"op\":\"" + ENABLE_ID + "\",\"from\":\"" + modsPath + "/" + DH
				+ ".rigtune-pending\",\"to\":\"" + modsPath + "/" + DH + "\"}]}]}", StandardCharsets.UTF_8);

		ApplyResult result = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.SKIPPED_ALREADY_DONE), statuses(result));
		assertTrue(result.results().get(1).message().startsWith("Already done earlier"), result.results().get(1).message());
	}

	// The redo 0.4 must keep: the last run did the renames itself (OK in last-apply.json) and died before pending.json.
	@Test
	void aRedoOfTheLastRunsOwnRenamesStaysDone() throws IOException {
		Path pending = RealWorldFixtures.seededPending(dir);
		TestJars.modJar(mods().resolve("fabric-26.2.jar"), "distanthorizons");
		TestJars.modJar(mods().resolve(DH + PendingActions.PENDING_SUFFIX), "distanthorizons");
		String saved = Files.readString(pending);
		ApplyResult first = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);
		Files.writeString(pending, saved);

		ApplyResult redo = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.OK, Status.OK), statuses(first));
		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.SKIPPED_ALREADY_DONE), statuses(redo));
	}
}
