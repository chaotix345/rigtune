package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4d (AC4d.1): in an instance whose launcher keeps its own list of mods, the 0.5 helper runs with
// holdFileOps: groups with a mod-file op wait in pending.json for the player's choice (attempts unchanged, out of
// last-apply.json, no new status); config patches apply. A group RigTune's own records show half done or done already
// is finished, as in 0.4 (ws-l2.md "Decisions").
class ApplyExecutorHoldTest {
	@TempDir
	Path dir;
	Path mods;
	Path config;
	Path pending;
	Path sodiumOptions;
	Path oldJar;
	Path download;
	Path newJar;

	// A kill right after renaming `source`.
	private static final class Killed extends Error {
	}

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
		pending = PendingActions.defaultPath(config);
		sodiumOptions = Files.writeString(config.resolve("sodium-options.json"), "{\"performance\":{\"chunk_builder_threads\":0}}");
		oldJar = TestJars.modJar(mods.resolve("sodium-0.7.0.jar"), "sodium");
		download = TestJars.modJar(mods.resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX), "sodium");
		newJar = mods.resolve("sodium-0.7.1.jar");
	}

	private List<Op> update() {
		return PendingActions.group(Op.disableFile(oldJar), Op.enableFile(download, newJar).withModId("sodium"));
	}

	private Op patch() {
		return Op.patchJson(sodiumOptions, Map.of("performance.chunk_builder_threads", "4"));
	}

	private PendingActions plan(List<Op> ops) throws IOException {
		PendingActions plan = PendingActions.create(1, mods, config, ops);
		plan.save(pending);
		return plan;
	}

	private ApplyResult hold(PendingActions plan) throws IOException {
		return new ApplyExecutor(2, 1).run(plan, pending, true);
	}

	private List<String> modsListing() throws IOException {
		try (Stream<Path> files = Files.list(mods)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private static List<Status> statuses(ApplyResult result) {
		return result.results().stream().map(OpResult::status).toList();
	}

	@Test
	void fileGroupsWaitAndPatchesApply() throws IOException {
		List<Op> update = update().stream().map(op -> op.withAttempts(1)).toList();
		Op patch = patch();
		List<Op> ops = new ArrayList<>(update);
		ops.add(patch);
		List<String> before = modsListing();

		ApplyResult result = hold(plan(ops));

		assertEquals(List.of(patch.id()), result.results().stream().map(r -> r.op().id()).toList());
		assertEquals(List.of(Status.OK), statuses(result));
		assertEquals(result, ApplyResult.load(ApplyResult.defaultPath(config)));
		assertEquals(update, PendingActions.load(pending).ops());
		assertEquals(before, modsListing());
		assertTrue(Files.readString(sodiumOptions).contains("\"chunk_builder_threads\": 4"));
	}

	// Nothing ran: no last-apply.json is written (an empty one would read "RigTune applied 0 change(s)" at the next start),
	// and an earlier one stays as it was.
	@Test
	void aRunThatHoldsEverythingWritesNoResult() throws IOException {
		Path lastApply = ApplyResult.defaultPath(config);
		new ApplyResult("2026-09-20T10:00:00Z", List.of()).save(lastApply);
		String earlier = Files.readString(lastApply);
		List<Op> ops = update();

		ApplyResult result = hold(plan(ops));

		assertTrue(result.results().isEmpty());
		assertEquals(earlier, Files.readString(lastApply));
		assertEquals(ops, PendingActions.load(pending).ops());
	}

	@Test
	void anUngroupedFileOpAndAGroupWithAPatchAreHeldWhole() throws IOException {
		Files.writeString(mods.resolve("indium.jar"), "indium");
		Op lone = Op.disableFile(mods.resolve("indium.jar"));
		List<Op> mixed = PendingActions.group(Op.enableFile(download, newJar), patch());
		List<Op> ops = new ArrayList<>(mixed);
		ops.add(lone);

		ApplyResult result = hold(plan(ops));

		assertTrue(result.results().isEmpty());
		assertEquals(ops, PendingActions.load(pending).ops());
		assertFalse(Files.readString(sodiumOptions).contains("\"chunk_builder_threads\": 4"));
	}

	// A group a killed helper left half done (its record in unfinished-groups.json): finished, never held.
	@Test
	void aHalfDoneGroupIsFinished() throws IOException {
		List<Op> ops = update();
		// Killed between the two renames: the disable done and recorded as done, the enable not reached.
		ApplyExecutor killed = new ApplyExecutor(2, 1, (from, to) -> {
			if (from.equals(download)) {
				throw new Killed();
			}
			Files.move(from, to);
		});
		assertThrows(Killed.class, () -> killed.run(plan(ops), pending));

		ApplyResult result = hold(PendingActions.load(pending));

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.OK), statuses(result));
		assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar"), modsListing());
		assertFalse(Files.exists(pending));
	}

	// A group the last run did and whose pending.json rewrite didn't happen (last-apply.json proves it): finished.
	@Test
	void aGroupTheLastRunDidIsFinished() throws IOException {
		List<Op> ops = update();
		PendingActions plan = plan(ops);
		String saved = Files.readString(pending);
		new ApplyExecutor(2, 1).run(plan, pending);
		Files.writeString(pending, saved);

		ApplyResult result = hold(PendingActions.load(pending));

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.SKIPPED_ALREADY_DONE), statuses(result));
		assertFalse(Files.exists(pending));
	}

	// review-11 APPLY-5: what only looks like a failed rollback of a 0.1.0-0.3.0 helper (the old jar disabled after an
	// attempt, the download still there) is no record of RigTune's: the launcher's own disable leaves the same files. Held.
	@Test
	void aFailedRollbackOfAnOldHelperIsHeld() throws IOException {
		List<Op> ops = update().stream().map(op -> op.withAttempts(1)).toList();
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));

		ApplyResult result = hold(plan(ops));

		assertTrue(result.results().isEmpty(), result.toString());
		assertEquals(ops, PendingActions.load(pending).ops());
		assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
	}

	// A helper killed during its retries: its disable was rolled back, the enable never ran. The record still names the
	// disable's rename, so when the launcher itself later disables that jar (the same .disabled name), the files look like
	// RigTune's half-done group. The record says the rename was undone.
	private List<Op> killedDuringTheRetries() throws IOException {
		List<Op> ops = update();
		ApplyExecutor retrying = new ApplyExecutor(2, 1, (from, to) -> {
			if (from.equals(download)) {
				throw new IOException("locked");
			}
			Files.move(from, to);
		}, millis -> {
			throw new Killed();
		});
		assertThrows(Killed.class, () -> retrying.run(plan(ops), pending));
		assertEquals(List.of("sodium-0.7.0.jar", "sodium-0.7.1.jar.rigtune-pending"), modsListing(), "rolled back before the kill");
		assertTrue(Files.exists(UnfinishedGroups.file(config)), "the record is left");
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));
		return ops;
	}

	@Test
	void aRolledBackRenameAndTheLaunchersOwnDisableStayHeld() throws IOException {
		List<Op> ops = killedDuringTheRetries();

		ApplyResult result = hold(PendingActions.load(pending));

		assertTrue(result.results().isEmpty(), result.toString());
		assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
		assertEquals(ops.stream().map(Op::id).toList(), PendingActions.load(pending).ops().stream().map(Op::id).toList());
	}

	// Without the hold the group runs, but the launcher's disable is never reported as RigTune's own ("already done
	// earlier" with where the file went).
	@Test
	void aRolledBackRenameIsNeverClaimedAsDoneEarlier() throws IOException {
		killedDuringTheRetries();

		ApplyResult result = new ApplyExecutor(2, 1).run(PendingActions.load(pending), pending, false);

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.OK), statuses(result));
		assertEquals(null, result.results().getFirst().resultPath(), result.results().getFirst().message());
		assertFalse(result.results().getFirst().message().startsWith("Already done earlier"), result.results().getFirst().message());
	}

	// A record 0.4 wrote (no mark of which renames happened) proves nothing under the hold: held.
	@Test
	void a04RecordIsNoProofUnderTheHold() throws IOException {
		List<Op> ops = update();
		plan(ops);
		com.google.gson.JsonObject rename = new com.google.gson.JsonObject();
		rename.addProperty("op", ops.getFirst().id());
		rename.addProperty("from", oldJar.toString());
		rename.addProperty("to", mods.resolve("sodium-0.7.0.jar.disabled").toString());
		com.google.gson.JsonArray renames = new com.google.gson.JsonArray();
		renames.add(rename);
		com.google.gson.JsonObject group = new com.google.gson.JsonObject();
		group.addProperty("group", ops.getFirst().group());
		group.add("renames", renames);
		com.google.gson.JsonArray groups = new com.google.gson.JsonArray();
		groups.add(group);
		com.google.gson.JsonObject doc = new com.google.gson.JsonObject();
		doc.add("groups", groups);
		Files.createDirectories(UnfinishedGroups.file(config).getParent());
		Files.writeString(UnfinishedGroups.file(config), doc.toString());
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));

		ApplyResult result = hold(PendingActions.load(pending));

		assertTrue(result.results().isEmpty(), result.toString());
		assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
	}

	@Test
	void withoutTheHoldEverythingRunsAsIn04() throws IOException {
		List<Op> ops = new ArrayList<>(update());
		ops.add(patch());

		ApplyResult result = new ApplyExecutor(2, 1).run(plan(ops), pending, false);

		assertEquals(List.of(Status.OK, Status.OK, Status.OK), statuses(result));
		assertFalse(Files.exists(pending));
	}

	// The rule the notice and the counts use is the helper's own.
	@Test
	void theHeldOpsAreTheOnesTheHelperLeaves() throws IOException {
		List<Op> update = update();
		Op patch = patch();
		List<Op> ops = new ArrayList<>(update);
		ops.add(patch);
		PendingActions plan = plan(ops);

		assertEquals(update, ApplyExecutor.held(plan, pending));
		assertEquals(update, ApplyExecutor.fileGroupOps(ops));
	}
}
