package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Type;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyGroupsTest {
	@TempDir
	Path dir;
	Path mods;
	Path config;
	Path pending;
	Path oldJar;
	Path newPending;
	Path newJar;
	final List<String> moves = new ArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
		pending = PendingActions.defaultPath(config);
		oldJar = Files.writeString(mods.resolve("sodium-0.7.0.jar"), "old");
		newPending = TestJars.modJar(mods.resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX), "sodium");
		newJar = mods.resolve("sodium-0.7.1.jar");
	}

	// Moves like Files.move, except that moves matching `fail` throw, as a locked or vanished file would.
	private ApplyExecutor executor(BiPredicate<Path, Path> fail) {
		return new ApplyExecutor(2, 1, (from, to) -> {
			moves.add(from.getFileName() + " -> " + to.getFileName());
			if (fail.test(from, to)) {
				throw new IOException("The process cannot access the file because it is being used by another process: " + from);
			}
			Files.move(from, to);
		});
	}

	private ApplyResult run(ApplyExecutor executor, List<Op> ops) throws IOException {
		PendingActions plan = PendingActions.create(1, mods, config, ops);
		plan.save(pending);
		return executor.run(plan, pending);
	}

	private List<Op> update() {
		return PendingActions.group(Op.disableFile(oldJar), Op.enableFile(newPending, newJar));
	}

	private static List<Op> failedOnce(List<Op> ops) {
		return ops.stream().map(op -> op.withAttempts(1)).toList();
	}

	private static List<Status> statuses(ApplyResult result) {
		return result.results().stream().map(OpResult::status).toList();
	}

	private List<String> modsListing() throws IOException {
		try (Stream<Path> files = Files.list(mods)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	private List<String> enabledJars() throws IOException {
		return modsListing().stream().filter(n -> n.endsWith(".jar")).toList();
	}

	@Test
	void updateGroupAppliesBoth() throws IOException {
		ApplyResult result = run(executor((a, b) -> false), update());

		assertEquals(List.of(Status.OK, Status.OK), statuses(result));
		assertEquals(List.of("sodium-0.7.1.jar"), enabledJars());
		assertEquals("old", Files.readString(mods.resolve("sodium-0.7.0.jar.disabled")));
		assertFalse(Files.exists(pending));
	}

	@Test
	void enableIsSkippedWhenTheDisableFails() throws IOException {
		List<Op> ops = update();

		ApplyResult result = run(executor((from, to) -> from.equals(oldJar)), ops);

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(result));
		assertTrue(result.results().get(1).message().startsWith("Not applied because disabling sodium-0.7.0.jar failed"),
				result.results().get(1).message());
		assertEquals(List.of("sodium-0.7.0.jar"), enabledJars());
		assertEquals("sodium", ModJars.readModId(newPending));
		assertFalse(moves.stream().anyMatch(m -> m.startsWith(newPending.getFileName().toString())), moves.toString());
		assertEquals(failedOnce(ops), PendingActions.load(pending).ops());
	}

	@Test
	void disableIsRolledBackWhenTheEnableFails() throws IOException {
		Files.writeString(newJar, "someone else's copy");
		List<Op> ops = update();

		ApplyResult result = run(executor((a, b) -> false), ops);

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(result));
		assertTrue(result.results().get(0).message().startsWith("Rolled back because enabling sodium-0.7.1.jar failed"),
				result.results().get(0).message());
		assertEquals("old", Files.readString(oldJar));
		assertEquals("someone else's copy", Files.readString(newJar));
		assertEquals("sodium", ModJars.readModId(newPending));
		assertFalse(Files.exists(mods.resolve("sodium-0.7.0.jar.disabled")));
		assertEquals(failedOnce(ops), PendingActions.load(pending).ops());
	}

	@Test
	void missingPendingJarRollsBackTheDisable() throws IOException {
		Files.delete(newPending);

		ApplyResult result = run(executor((a, b) -> false), update());

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(result));
		assertTrue(result.results().get(1).message().startsWith("Missing"), result.results().get(1).message());
		assertEquals(List.of("sodium-0.7.0.jar"), enabledJars());
		assertEquals(List.of("sodium-0.7.0.jar"), modsListing());
	}

	@Test
	void failedRollbackIsReportedAndARetryFinishesTheUpdate() throws IOException {
		Files.delete(newPending);
		List<Op> ops = update();

		ApplyResult first = run(executor((from, to) -> to.equals(oldJar)), ops);

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(first));
		assertTrue(first.results().get(0).message().startsWith("Rollback failed"), first.results().get(0).message());
		assertEquals(List.of("sodium-0.7.0.jar.disabled"), modsListing());
		assertEquals(failedOnce(ops), PendingActions.load(pending).ops());

		TestJars.modJar(newPending, "sodium");
		ApplyResult retry = executor((a, b) -> false).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.OK), statuses(retry));
		assertEquals(List.of("sodium-0.7.1.jar"), enabledJars());
		assertFalse(Files.exists(pending));
	}

	@Test
	void disableAlreadyDoneLetsTheEnableRun() throws IOException {
		Files.move(oldJar, mods.resolve("sodium-0.7.0.jar.disabled"));

		ApplyResult result = run(executor((a, b) -> false), update());

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.OK), statuses(result));
		assertEquals(List.of("sodium-0.7.1.jar"), enabledJars());
	}

	@Test
	void disablesRunFirstWhateverTheListedOrder() throws IOException {
		Path sameName = Files.writeString(mods.resolve("lithium.jar"), "old lithium");
		Path lithiumPending = TestJars.modJar(mods.resolve("lithium.jar" + PendingActions.PENDING_SUFFIX), "lithium");

		ApplyResult result = run(executor((a, b) -> false),
				PendingActions.group(Op.enableFile(lithiumPending, sameName), Op.disableFile(sameName)));

		assertEquals(List.of(Status.OK, Status.OK), statuses(result));
		assertEquals("lithium", ModJars.readModId(sameName));
		assertEquals("old lithium", Files.readString(mods.resolve("lithium.jar.disabled")));
		assertEquals("lithium.jar -> lithium.jar.disabled", moves.getFirst());
	}

	@Test
	void laterFailureUndoesEveryEarlierRenameInTheGroup() throws IOException {
		Path depPending = TestJars.modJar(mods.resolve("lib.jar" + PendingActions.PENDING_SUFFIX), "lib");
		Path depJar = mods.resolve("lib.jar");
		Path sodiumOptions = Files.writeString(config.resolve("sodium-options.json"), "{ broken");

		ApplyResult result = run(executor((a, b) -> false), PendingActions.group(
				Op.disableFile(oldJar), Op.enableFile(newPending, newJar), Op.enableFile(depPending, depJar),
				Op.patchJson(sodiumOptions, Map.of("a", "1"))));

		assertEquals(List.of(Status.FAILED, Status.FAILED, Status.FAILED, Status.FAILED), statuses(result));
		assertEquals(List.of("lib.jar.rigtune-pending", "sodium-0.7.0.jar", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
		assertEquals("{ broken", Files.readString(sodiumOptions));
	}

	@Test
	void aFailedGroupLeavesOtherOpsAlone() throws IOException {
		Path lithium = Files.writeString(mods.resolve("lithium.jar"), "l");
		List<Op> ops = new ArrayList<>(update());
		ops.add(Op.disableFile(lithium));

		ApplyResult result = run(executor((from, to) -> from.equals(oldJar)), ops);

		assertEquals(List.of(Status.FAILED, Status.FAILED, Status.OK), statuses(result));
		assertTrue(Files.exists(mods.resolve("lithium.jar.disabled")));
		assertEquals(failedOnce(ops.subList(0, 2)), PendingActions.load(pending).ops());
	}

	@Test
	void aRefusedOpRefusesItsWholeGroupBeforeTouchingAnything() throws IOException {
		ApplyResult result = run(executor((a, b) -> false),
				PendingActions.group(Op.disableFile(oldJar), Op.enableFile(newPending, dir.resolve("evil.jar"))));

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(result));
		assertTrue(result.results().get(0).message().startsWith("Not applied: another change in its group was refused"));
		assertTrue(result.results().get(1).message().startsWith("Refused"));
		assertTrue(moves.isEmpty());
		assertTrue(Files.exists(oldJar));
	}

	@Test
	void onlyTheOpsThisRunSucceededAtLeaveThePendingFile() throws IOException {
		Path lithium = Files.writeString(mods.resolve("lithium.jar"), "l");
		List<Op> snapshot = update();
		PendingActions plan = PendingActions.create(1, mods, config, snapshot);
		plan.save(pending);
		Op stagedLater = Op.disableFile(lithium);
		Op legacy = new Op(Type.DISABLE_FILE, null, null, mods.resolve("legacy.jar").toString(), null);
		List<Op> onDisk = new ArrayList<>(snapshot);
		onDisk.add(stagedLater);
		onDisk.add(legacy);
		plan.withOps(onDisk).save(pending);

		ApplyResult result = executor((a, b) -> false).run(plan, pending);

		assertEquals(List.of(Status.OK, Status.OK), statuses(result));
		assertEquals(List.of(stagedLater, legacy), PendingActions.load(pending).ops());
		assertTrue(Files.exists(lithium));
	}

	@Test
	void opsWithoutIdsAreMatchedByValue() throws IOException {
		Op legacy = new Op(Type.DISABLE_FILE, null, null, oldJar.toString(), null);
		Op other = new Op(Type.DISABLE_FILE, null, null, mods.resolve("other.jar").toString(), null);
		PendingActions plan = PendingActions.create(1, mods, config, List.of(legacy));
		plan.withOps(List.of(legacy, other)).save(pending);

		executor((a, b) -> false).run(plan, pending);

		assertEquals(List.of(other), PendingActions.load(pending).ops());
	}

	// Review 2, N3: a change that can never apply is dropped after three helper runs instead of coming back forever.
	@Test
	void aGroupThatKeepsFailingIsAbandonedOnItsThirdRun() throws IOException {
		Files.writeString(newJar, "someone else's copy");
		Path libPending = TestJars.modJar(mods.resolve("lib.jar" + PendingActions.PENDING_SUFFIX), "lib");
		Path lithium = Files.writeString(mods.resolve("lithium.jar"), "l");
		List<Op> group = PendingActions.group(Op.disableFile(oldJar), Op.enableFile(newPending, newJar), Op.enableFile(libPending, mods.resolve("lib.jar")));

		ApplyResult first = run(executor((a, b) -> false), group);
		assertEquals(List.of(Status.FAILED, Status.FAILED, Status.FAILED), statuses(first));
		assertEquals(List.of(1, 1, 1), PendingActions.load(pending).ops().stream().map(Op::attempts).toList());

		// Staged between runs: it has its own count and isn't abandoned with the group.
		PendingActions plan = PendingActions.load(pending);
		List<Op> withLater = new ArrayList<>(plan.ops());
		withLater.add(Op.disableFile(lithium));
		plan.withOps(withLater).save(pending);

		ApplyResult second = executor((from, to) -> from.equals(lithium)).run(PendingActions.load(pending), pending);
		assertEquals(List.of(Status.FAILED, Status.FAILED, Status.FAILED, Status.FAILED), statuses(second));
		assertEquals(List.of(2, 2, 2, 1), PendingActions.load(pending).ops().stream().map(Op::attempts).toList());

		ApplyResult third = executor((from, to) -> from.equals(lithium)).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED, Status.ABANDONED, Status.FAILED), statuses(third));
		assertTrue(third.results().get(1).message().startsWith("Gave up after 3 restarts: "), third.results().get(1).message());
		assertEquals(3, third.abandonedOps().size());
		assertEquals(statuses(third), statuses(ApplyResult.load(ApplyResult.defaultPath(config))));
		assertEquals(List.of(lithium.toString()), PendingActions.load(pending).ops().stream().map(Op::path).toList());
		assertEquals(2, PendingActions.load(pending).ops().getFirst().attempts());
		assertEquals(List.of("lib.jar.rigtune-superseded", "lithium.jar", "sodium-0.7.0.jar", "sodium-0.7.1.jar",
				"sodium-0.7.1.jar.rigtune-superseded"), modsListing());
		assertEquals("someone else's copy", Files.readString(newJar));
	}

	// A helper killed mid-group (PC shutdown, Task Manager): nothing after the throw runs, as in a real kill.
	private static final class Killed extends Error {
	}

	private ApplyExecutor killedAt(Path source) {
		return new ApplyExecutor(2, 1, (from, to) -> {
			if (from.equals(source)) {
				throw new Killed();
			}
			Files.move(from, to);
		});
	}

	private static FileSystemException sharing(Path file) {
		return new FileSystemException(file.toString(), null, "The process cannot access the file because it is being used by another process");
	}

	private boolean oneActiveSodium() throws IOException {
		return enabledJars().stream().filter(n -> n.startsWith("sodium-")).count() == 1;
	}

	private Path unfinished() {
		return HelperLauncher.helperDir(config).resolve("unfinished-groups.json");
	}

	// docs/v0.4 audit H4: once a group's first rename is done, it's never retried in place, so no moment of the retry
	// has neither the old nor the new jar in mods/.
	@Test
	void noMomentWithoutEitherJarWhileTheEnableIsRetried() throws IOException {
		List<List<String>> snapshots = new ArrayList<>();
		AtomicInteger denied = new AtomicInteger();
		ApplyExecutor executor = new ApplyExecutor(2, 1, (from, to) -> {
			if (from.equals(newPending) && denied.incrementAndGet() <= 2) {
				throw sharing(from);
			}
			Files.move(from, to);
		}, millis -> {
			try {
				snapshots.add(enabledJars());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			return true;
		});

		ApplyResult result = run(executor, update());

		assertEquals(List.of(Status.OK, Status.OK), statuses(result));
		assertEquals(List.of("sodium-0.7.1.jar"), enabledJars());
		assertEquals(2, snapshots.size());
		assertTrue(snapshots.stream().allMatch(s -> s.contains("sodium-0.7.0.jar") || s.contains("sodium-0.7.1.jar")), snapshots.toString());
		assertFalse(Files.exists(unfinished()));
	}

	@Test
	void aHelperKilledBetweenTheRenamesIsFinishedByTheNextRun() throws IOException {
		List<Op> ops = update();
		assertThrows(Killed.class, () -> run(killedAt(newPending), ops));
		assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
		assertEquals(ops, PendingActions.load(pending).ops());

		ApplyResult next = executor((a, b) -> false).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.OK), statuses(next));
		assertEquals(List.of("sodium-0.7.1.jar"), enabledJars());
		assertEquals(mods.resolve("sodium-0.7.0.jar.disabled").toString(), next.results().getFirst().resultPath());
		assertFalse(Files.exists(pending));
		assertFalse(Files.exists(unfinished()));
	}

	@Test
	void aHelperKilledBetweenTheRenamesIsRolledBackWhenTheNextRunCantFinish() throws IOException {
		List<Op> ops = update();
		assertThrows(Killed.class, () -> run(killedAt(newPending), ops));

		ApplyResult next = executor((from, to) -> from.equals(newPending)).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(next));
		assertTrue(next.results().getFirst().message().startsWith("Rolled back because enabling sodium-0.7.1.jar failed"),
				next.results().getFirst().message());
		assertTrue(oneActiveSodium(), modsListing().toString());
		assertEquals(List.of("sodium-0.7.0.jar", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
		assertEquals(failedOnce(ops), PendingActions.load(pending).ops());
		assertFalse(Files.exists(unfinished()));
	}

	// An addition group {mod, its library}: a kill after the mod's enable never leaves the mod active without its library.
	@Test
	void anAdditionKilledAfterTheModNeverLeavesItWithoutItsLibrary() throws IOException {
		Path modPending = TestJars.modJar(mods.resolve("iris.jar" + PendingActions.PENDING_SUFFIX), "iris");
		Path libPending = TestJars.modJar(mods.resolve("lib.jar" + PendingActions.PENDING_SUFFIX), "lib");
		List<Op> ops = PendingActions.group(Op.enableFile(modPending, mods.resolve("iris.jar")), Op.enableFile(libPending, mods.resolve("lib.jar")));
		assertThrows(Killed.class, () -> run(killedAt(libPending), ops));
		assertTrue(Files.exists(mods.resolve("iris.jar")));

		executor((from, to) -> from.equals(libPending)).run(PendingActions.load(pending), pending);

		assertTrue(Files.exists(modPending), modsListing().toString());
		assertTrue(Files.exists(libPending), modsListing().toString());
		assertFalse(Files.exists(mods.resolve("iris.jar")), modsListing().toString());
	}

	@Test
	void aFailedRollbackIsRolledBackByTheNextRunWhenItStillCantFinish() throws IOException {
		List<Op> ops = update();
		ApplyResult first = run(executor((from, to) -> from.equals(newPending) || to.equals(oldJar)), ops);
		assertTrue(first.results().getFirst().message().startsWith("Rollback failed"), first.results().getFirst().message());
		assertEquals(mods.resolve("sodium-0.7.0.jar.disabled").toString(), first.results().getFirst().resultPath());
		assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
		assertTrue(Files.exists(unfinished()));

		ApplyResult next = executor((from, to) -> from.equals(newPending)).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(next));
		assertEquals(List.of("sodium-0.7.0.jar", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
		assertFalse(Files.exists(unfinished()));
	}

	@Test
	void aFailedRollbackIsFinishedByTheNextRun() throws IOException {
		run(executor((from, to) -> from.equals(newPending) || to.equals(oldJar)), update());

		ApplyResult next = executor((a, b) -> false).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.OK), statuses(next));
		assertEquals(List.of("sodium-0.7.1.jar"), enabledJars());
		assertEquals(mods.resolve("sodium-0.7.0.jar.disabled").toString(), next.results().getFirst().resultPath());
		assertFalse(Files.exists(unfinished()));
	}

	// A group left half-applied is never abandoned (that would retire the download and leave the mod missing for good):
	// it stays in pending.json, at "try 3 of 3", until a run finishes it or rolls it back; then the usual rule applies.
	@Test
	void aGroupLeftHalfAppliedIsNeverAbandoned() throws IOException {
		List<Op> ops = update().stream().map(op -> op.withAttempts(2)).toList();

		ApplyResult third = run(executor((from, to) -> from.equals(newPending) || to.equals(oldJar)), ops);

		assertEquals(List.of(Status.FAILED, Status.FAILED), statuses(third));
		assertEquals(ops, PendingActions.load(pending).ops());
		assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar.rigtune-pending"), modsListing());

		ApplyResult fourth = executor((from, to) -> from.equals(newPending)).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED), statuses(fourth));
		assertTrue(fourth.results().getFirst().message().startsWith("Gave up after 3 restarts: Rolled back because"),
				fourth.results().getFirst().message());
		assertEquals(List.of("sodium-0.7.0.jar", "sodium-0.7.1.jar.rigtune-superseded"), modsListing());
		assertFalse(Files.exists(pending));
	}

	// An earlier run's rename after the op that fails now (here the new jar, left active by a failed rollback while the
	// old one came back) is rolled back too, never forgotten.
	@Test
	void anEarlierRenameAfterTheFailingOpIsRolledBackToo() throws IOException {
		Path libPending = TestJars.modJar(mods.resolve("lib.jar" + PendingActions.PENDING_SUFFIX), "lib");
		List<Op> ops = PendingActions.group(Op.disableFile(oldJar), Op.enableFile(newPending, newJar), Op.enableFile(libPending, mods.resolve("lib.jar")));
		run(executor((from, to) -> from.equals(libPending) || from.equals(newJar)), ops);
		assertEquals(List.of("lib.jar.rigtune-pending", "sodium-0.7.0.jar", "sodium-0.7.1.jar"), modsListing());

		ApplyResult next = executor((from, to) -> from.equals(oldJar)).run(PendingActions.load(pending), pending);

		assertEquals(List.of(Status.FAILED, Status.FAILED, Status.FAILED), statuses(next));
		assertEquals(List.of("lib.jar.rigtune-pending", "sodium-0.7.0.jar", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
		assertFalse(Files.exists(unfinished()));
	}

	// The record only ever names renames its own ops would do: one outside the mods folder, or for another op, is ignored.
	@Test
	void aRecordThatDoesntMatchItsOpsIsIgnored() throws IOException {
		Path byHand = mods.resolve("sodium-0.7.0.jar.disabled");
		Files.move(oldJar, byHand);
		Path outside = Files.writeString(Files.createDirectories(dir.resolve("outside")).resolve("sodium-0.7.0.jar.disabled"), "elsewhere");
		List<Op> ops = update();
		UnfinishedGroups.load(config).put(ops.getFirst().group(), List.of(
				new UnfinishedGroups.Rename("another op", oldJar.toString(), byHand.toString()),
				new UnfinishedGroups.Rename(ops.getFirst().id(), oldJar.toString(), outside.toString())));

		ApplyResult result = run(executor((from, to) -> from.equals(newPending)), ops);

		assertEquals(List.of(Status.SKIPPED_ALREADY_DONE, Status.FAILED), statuses(result));
		assertEquals(List.of("sodium-0.7.0.jar.disabled", "sodium-0.7.1.jar.rigtune-pending"), modsListing());
		assertEquals("elsewhere", Files.readString(outside));
		assertFalse(Files.exists(unfinished()));
	}

	@Test
	void anUnreadableRecordIsIgnoredAndReplaced() throws IOException {
		Files.createDirectories(unfinished().getParent());
		Files.writeString(unfinished(), "{ broken");

		ApplyResult result = run(executor((a, b) -> false), update());

		assertEquals(List.of(Status.OK, Status.OK), statuses(result));
		assertFalse(Files.exists(unfinished()));
	}

	@Test
	void anOpThatJoinedAGroupLaterIsAbandonedWithIt() {
		Op old = Op.disableFile(oldJar).inGroup("g").withAttempts(2);
		Op joined = Op.enableFile(newPending, newJar).inGroup("g");
		Op alone = Op.disableFile(mods.resolve("x.jar")).withAttempts(1);
		List<OpResult> results = List.of(new OpResult(old, Status.FAILED, "a"), new OpResult(joined, Status.FAILED, "b"),
				new OpResult(alone, Status.FAILED, "c"));

		assertEquals(List.of(Status.ABANDONED, Status.ABANDONED, Status.FAILED),
				ApplyExecutor.giveUpOnRepeatFailures(results).stream().map(OpResult::status).toList());
	}
}
