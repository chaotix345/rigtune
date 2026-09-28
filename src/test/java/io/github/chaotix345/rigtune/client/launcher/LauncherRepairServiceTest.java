package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.client.HelperToasts;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.history.LauncherRepair;
import io.github.chaotix345.rigtune.core.history.UndoPlanner;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4d (HELD_MOD_CHANGES, the leftover toasts that wait for the policy) and 4g (LAUNCHER_REPAIR): the
// service's unit half, with its worker run in place. LauncherManagedGameTest.heldAndRepair is the game half.
class LauncherRepairServiceTest {
	@TempDir
	Path dir;
	Path mods;
	Path config;
	Path pending;
	Path download;
	List<Op> update;
	Op patch;
	final AtomicReference<ModFilesPolicy> policy = new AtomicReference<>(ModFilesPolicy.LAUNCHER);
	final AtomicReference<Launcher> launcher = new AtomicReference<>(Launcher.MODRINTH_APP);
	final AtomicInteger rebuilds = new AtomicInteger();
	final AtomicInteger optIns = new AtomicInteger();
	final AtomicInteger stagedChanges = new AtomicInteger();
	// The worker: run in place, or queued until the test runs it (a real worker's timing).
	final java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();
	volatile boolean queue;
	final List<String> clipboard = new ArrayList<>();
	final List<HelperToasts.Toast> toasts = new ArrayList<>();
	final List<String> warnings = new ArrayList<>();
	Journal journal;
	LauncherRepairService service;

	// The mods folder as it is on disk, each jar's fabric.mod.json read when asked.
	private UndoPlanner.Folder folder(Path modsDir) {
		return new UndoPlanner.Folder() {
			@Override
			public Path dir() {
				return modsDir;
			}

			@Override
			public Set<String> files() {
				try (Stream<Path> files = Files.list(modsDir)) {
					return files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
				} catch (IOException e) {
					return Set.of();
				}
			}

			@Override
			public JarInfo jar(String fileName) {
				return JarInfo.read(modsDir.resolve(fileName));
			}

			@Override
			public Set<String> providedElsewhere() {
				return Set.of();
			}
		};
	}

	@BeforeEach
	void setUp() throws IOException {
		mods = Files.createDirectories(dir.resolve("mods"));
		config = Files.createDirectories(dir.resolve("config"));
		pending = PendingActions.defaultPath(config);
		TestJars.modJar(mods.resolve("sodium-0.7.0.jar"), "sodium");
		download = TestJars.modJar(mods.resolve("sodium-0.7.1.jar" + PendingActions.PENDING_SUFFIX), "sodium");
		update = PendingActions.group(Op.disableFile(mods.resolve("sodium-0.7.0.jar")), Op.enableFile(download, mods.resolve("sodium-0.7.1.jar")).withModId("sodium"));
		patch = Op.patchJson(config.resolve("sodium-options.json"), Map.of("performance.chunk_builder_threads", "4"));
		journal = new Journal(config, "0.4.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		service = new LauncherRepairService(new LauncherRepairService.Env(config, policy::get, launcher::get, () -> journal, this::folder,
				rebuilds::incrementAndGet, stagedChanges::incrementAndGet, optIns::incrementAndGet, task -> {
					if (queue) {
						queued.add(task);
					} else {
						task.run();
					}
				}, clipboard::add, toasts::addAll, warnings::add));
	}

	// What a 0.4 Apply staged: the update and a Sodium patch, journaled STAGED.
	private void staged() throws IOException {
		List<Op> ops = new ArrayList<>(update);
		ops.add(patch);
		PendingActions.create(1, mods, config, ops).save(pending);
		journal.record("e1", JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, "sodium", "sodium-0.7.0.jar", JournalChange.STAGED, update.get(0).id(), update.get(0).group()),
				JournalChange.file(JournalChange.ENABLE, "sodium", "sodium-0.7.1.jar", JournalChange.STAGED, update.get(1).id(), update.get(1).group())));
	}

	// The first ask starts the read (a worker in the game) and asks for a rebuild once there's something to show.
	private Notice held() {
		service.heldNotice();
		return service.heldNotice();
	}

	@Test
	void theHeldChangesWaitForAChoiceUnderLauncher() throws IOException {
		staged();

		assertNull(service.heldNotice());
		assertEquals(1, rebuilds.get());
		Notice notice = service.heldNotice();

		assertNotNull(notice);
		assertEquals(LauncherRepairService.HELD_KEY, notice.key());
		assertEquals(NoticePriority.HELD_MOD_CHANGES, notice.priority());
		assertEquals("1 mod change(s) from an earlier Apply are waiting: Modrinth App manages this instance's mods.", notice.message().english());
		assertEquals(List.of("cancel", "apply"), notice.actions().stream().map(NoticeAction::id).toList());
		assertEquals(List.of("Cancel them", "Let RigTune apply them"), notice.actions().stream().map(a -> a.label().english()).toList());
		assertFalse(notice.dismissible());
	}

	@Test
	void whileTheLauncherIsntKnownTheNoticeNamesNone() throws IOException {
		staged();
		policy.set(ModFilesPolicy.PENDING);

		assertEquals("1 mod change(s) from an earlier Apply are waiting while RigTune checks which launcher manages this instance's mods.",
				held().message().english());
	}

	@Test
	void noHeldNoticeUnderRigTune() throws IOException {
		staged();
		policy.set(ModFilesPolicy.RIGTUNE);

		assertNull(held());
		assertEquals(0, rebuilds.get());
	}

	// AC4d.2's unit half: Cancel them leaves no file op in pending.json, retires the download and marks the journal DISCARDED.
	@Test
	void cancelThemUnstagesTheHeldGroupOnly() throws IOException {
		staged();
		assertNotNull(held());

		service.heldAction(LauncherRepairService.CANCEL);

		assertEquals(List.of(patch), PendingActions.load(pending).ops());
		assertTrue(Files.exists(mods.resolve("sodium-0.7.1.jar" + PendingActions.SUPERSEDED_SUFFIX)));
		assertFalse(Files.exists(download));
		assertEquals(List.of(JournalChange.DISCARDED, JournalChange.DISCARDED),
				journal.entries().getFirst().changes().stream().map(JournalChange::status).toList());
		assertEquals(1, stagedChanges.get(), "the controller recounts its staged changes");
		assertNull(held());
	}

	// A group RigTune's records prove started (here a killed helper's record: the disable done, the enable not reached)
	// isn't held, so Cancel them leaves it for the next exit to finish; an op staged without an id (0.1.0's first builds)
	// is cancelled all the same (sameOp).
	@Test
	void cancelSkipsAStartedGroupAndMatchesOpsWithoutIds() throws IOException {
		Path started = TestJars.modJar(mods.resolve("lithium-1.jar"), "lithium");
		Path startedDownload = TestJars.modJar(mods.resolve("lithium-2.jar" + PendingActions.PENDING_SUFFIX), "lithium");
		List<Op> startedGroup = PendingActions.group(Op.disableFile(started), Op.enableFile(startedDownload, mods.resolve("lithium-2.jar")));
		Files.move(started, mods.resolve("lithium-1.jar.disabled"));
		record(startedGroup.getFirst(), started, mods.resolve("lithium-1.jar.disabled"), true);
		Path idless = TestJars.modJar(mods.resolve("iris.jar" + PendingActions.PENDING_SUFFIX), "iris");
		Op noId = new Op(PendingActions.Type.ENABLE_FILE, idless.toString(), mods.resolve("iris.jar").toString(), null, null);
		List<Op> ops = new ArrayList<>(update);
		ops.addAll(startedGroup);
		ops.add(noId);
		ops.add(patch);
		PendingActions.create(1, mods, config, ops).save(pending);
		assertNotNull(held());
		assertEquals("2 mod change(s) from an earlier Apply are waiting: Modrinth App manages this instance's mods.", held().message().english());

		service.heldAction(LauncherRepairService.CANCEL);

		List<Op> left = PendingActions.load(pending).ops();
		assertEquals(startedGroup.size() + 1, left.size(), left.toString());
		assertTrue(left.containsAll(startedGroup) && left.contains(patch), left.toString());
		assertTrue(Files.exists(startedDownload), "the started group's download stays");
		assertTrue(Files.exists(mods.resolve("iris.jar" + PendingActions.SUPERSEDED_SUFFIX)));
	}

	// unfinished-groups.json as a 0.5 helper writes it: one rename of `op`, done or not.
	private void record(Op op, Path from, Path to, boolean done) throws IOException {
		com.google.gson.JsonObject rename = new com.google.gson.JsonObject();
		rename.addProperty("op", op.id());
		rename.addProperty("from", from.toString());
		rename.addProperty("to", to.toString());
		rename.addProperty("done", done);
		com.google.gson.JsonArray renames = new com.google.gson.JsonArray();
		renames.add(rename);
		com.google.gson.JsonObject group = new com.google.gson.JsonObject();
		group.addProperty("group", op.group());
		group.add("renames", renames);
		com.google.gson.JsonArray groups = new com.google.gson.JsonArray();
		groups.add(group);
		com.google.gson.JsonObject doc = new com.google.gson.JsonObject();
		doc.add("groups", groups);
		Files.createDirectories(config.resolve("rigtune"));
		Files.writeString(config.resolve("rigtune").resolve("unfinished-groups.json"), doc.toString());
	}

	// review-11 APPLY-6: a notice shown under PENDING and clicked after the policy answered RIGTUNE cancels nothing.
	@Test
	void cancelUnderAPolicyThatNoLongerHoldsCancelsNothing() throws IOException {
		staged();
		assertNotNull(held());
		policy.set(ModFilesPolicy.RIGTUNE);

		service.heldAction(LauncherRepairService.CANCEL);

		assertEquals(update.size() + 1, PendingActions.load(pending).ops().size());
		assertTrue(Files.exists(download));
	}

	// review-11 APPLY-6: Cancel them cancels what the notice counted; a group held since stays.
	@Test
	void cancelTakesOnlyWhatTheNoticeCounted() throws IOException {
		staged();
		assertNotNull(held());
		Path later = TestJars.modJar(mods.resolve("lithium-2.jar" + PendingActions.PENDING_SUFFIX), "lithium");
		List<Op> laterGroup = PendingActions.group(Op.enableFile(later, mods.resolve("lithium-2.jar")));
		List<Op> ops = new ArrayList<>(PendingActions.load(pending).ops());
		ops.addAll(laterGroup);
		PendingActions.create(1, mods, config, ops).save(pending);

		service.heldAction(LauncherRepairService.CANCEL);

		assertEquals(List.of(patch, laterGroup.getFirst()), PendingActions.load(pending).ops());
		assertTrue(Files.exists(later));
	}

	// review-11 APPLY-6: with the apply lock busy (the last session's helper still running) nothing changes, and a toast
	// says so.
	@Test
	void cancelWithTheLockBusySaysSo() throws Exception {
		staged();
		assertNotNull(held());

		try (io.github.chaotix345.rigtune.core.apply.HeldLock ignored = io.github.chaotix345.rigtune.core.apply.HeldLock.hold(
				io.github.chaotix345.rigtune.core.apply.ApplyLock.defaultPath(config))) {
			service.heldAction(LauncherRepairService.CANCEL);
		}

		assertEquals(update.size() + 1, PendingActions.load(pending).ops().size());
		assertEquals(List.of("rigtune.toast.held.busy.title[]"), toasts.stream().map(LauncherRepairServiceTest::key).toList());
	}

	// review-11 APPLY-3: the ops a restart never applies (held under LAUNCHER or PENDING), for RealController's count.
	@Test
	void theHeldOpsAreTheOnesARestartNeverApplies() throws IOException {
		staged();
		assertEquals(List.of(), service.heldOps(), "none before the first read");
		assertNotNull(held());

		assertEquals(update, service.heldOps());
		assertEquals(1, service.heldChanges());
		policy.set(ModFilesPolicy.PENDING);
		assertEquals(update, service.heldOps());
		policy.set(ModFilesPolicy.RIGTUNE);
		assertEquals(List.of(), service.heldOps());
		assertEquals(0, service.heldChanges());
	}

	@Test
	void letRigTuneApplyThemTurnsTheOptInOn() throws IOException {
		staged();
		assertNotNull(held());

		service.heldAction(LauncherRepairService.APPLY);

		assertEquals(1, optIns.get());
		assertEquals(update.size() + 1, PendingActions.load(pending).ops().size());
	}

	// Discard pending (or an Undo) changed pending.json since: the old count isn't shown.
	@Test
	void aChangedPendingJsonIsReadAgain() throws IOException {
		staged();
		assertNotNull(held());

		Files.delete(pending);

		assertNull(held());
	}

	private void appliedPair() throws IOException {
		Files.move(mods.resolve("sodium-0.7.0.jar"), mods.resolve("sodium-0.7.0.jar.disabled"));
		Files.move(download, mods.resolve("sodium-0.7.1.jar"));
		TestJars.modJar(mods.resolve("fastquit.jar"), "fastquit");
		journal.record("e1", JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, "sodium", "sodium-0.7.0.jar", JournalChange.APPLIED, "d", "g").withResultFile("sodium-0.7.0.jar.disabled"),
				JournalChange.file(JournalChange.ENABLE, "sodium", "sodium-0.7.1.jar", JournalChange.APPLIED, "e", "g"),
				JournalChange.file(JournalChange.ENABLE, "fastquit", "fastquit.jar", JournalChange.APPLIED, "f", "g2")));
	}

	// An applied update of another mod still in effect, next to what staged() holds.
	private void anotherAppliedPair() throws IOException {
		TestJars.modJar(mods.resolve("lithium-1.jar.disabled"), "lithium");
		TestJars.modJar(mods.resolve("lithium-2.jar"), "lithium");
		journal.record("e2", JournalEntry.APPLY, List.of(
				JournalChange.file(JournalChange.DISABLE, "lithium", "lithium-1.jar", JournalChange.APPLIED, "ld", "lg").withResultFile("lithium-1.jar.disabled"),
				JournalChange.file(JournalChange.ENABLE, "lithium", "lithium-2.jar", JournalChange.APPLIED, "le", "lg")));
	}

	// AC4g.2's unit half: only under LAUNCHER and with a finding; Copy list puts the file names only on the clipboard.
	@Test
	void theRepairNoticeUnderLauncher() throws IOException {
		appliedPair();

		assertNull(service.repairNotice());
		Notice notice = service.repairNotice();

		assertNotNull(notice);
		assertEquals(NoticePriority.LAUNCHER_REPAIR, notice.priority());
		assertTrue(notice.key().startsWith(LauncherRepair.KEY_PREFIX), notice.key());
		assertEquals("Mod changes from an older RigTune: help Modrinth App catch up", notice.message().english());
		assertTrue(notice.detail().english().contains("Delete: sodium-0.7.0.jar.disabled."), notice.detail().english());
		assertTrue(notice.detail().english().contains("RigTune also added: fastquit.jar."), notice.detail().english());
		assertTrue(notice.dismissible());
		assertEquals(List.of("Copy list"), notice.actions().stream().map(a -> a.label().english()).toList());

		service.repairAction(LauncherRepairService.COPY);

		assertEquals(List.of("sodium-0.7.0.jar.disabled"), clipboard);
	}

	@Test
	void noRepairNoticeUnderRigTuneOrWithoutAStepToTake() throws IOException {
		appliedPair();
		policy.set(ModFilesPolicy.RIGTUNE);
		service.repairNotice();
		assertNull(service.repairNotice());
		policy.set(ModFilesPolicy.PENDING);
		service.repairNotice();
		assertNull(service.repairNotice());

		policy.set(ModFilesPolicy.LAUNCHER);
		launcher.set(Launcher.ATLAUNCHER);
		service.repairNotice();
		assertNull(service.repairNotice());
	}

	private static String key(HelperToasts.Toast toast) {
		return ((TranslatableContents) toast.title().getContents()).getKey() + List.of(((TranslatableContents) toast.title().getContents()).getArgs());
	}

	// The first screen asks for the held notice first and the repair notice next, with the read on a real worker: the
	// findings are read by that first read, and the rebuild it asks for shows the repair notice (review item 6).
	@Test
	void theFirstAskReadsTheFindingsWithAWorkerThatRunsLater() throws IOException {
		staged();
		anotherAppliedPair();
		queue = true;

		assertNull(service.heldNotice());
		assertNull(service.repairNotice());
		assertEquals(1, queued.size(), "one read at a time");
		queued.poll().run();

		assertEquals(1, rebuilds.get());
		assertNotNull(service.heldNotice());
		assertNotNull(service.repairNotice());
		assertTrue(queued.isEmpty(), "nothing stale, nothing read again: " + queued.size());
	}

	// Findings not read under PENDING are read once the policy is LAUNCHER, and the rebuild shows the notice.
	@Test
	void findingsNotReadUnderPendingAreReadUnderLauncher() throws IOException {
		staged();
		anotherAppliedPair();
		queue = true;
		policy.set(ModFilesPolicy.PENDING);
		service.heldNotice();
		queued.poll().run();
		assertNotNull(service.heldNotice());

		policy.set(ModFilesPolicy.LAUNCHER);
		assertNull(service.repairNotice());
		queued.poll().run();

		assertEquals(2, rebuilds.get());
		assertNotNull(service.repairNotice());
	}

	// A jar added, renamed or removed in the mods folder (the player in the launcher) makes the findings stale.
	@Test
	void aChangedModsFolderIsReadAgain() throws IOException {
		appliedPair();
		assertNull(service.repairNotice());
		assertNotNull(service.repairNotice());

		Files.delete(mods.resolve("sodium-0.7.0.jar.disabled"));
		Files.setLastModifiedTime(mods, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(mods).toMillis() + 5_000));
		service.repairNotice();

		assertNull(service.repairNotice());
	}

	// AC4d.4's unit half: the leftover toast and WARN wait for the policy; under LAUNCHER the held mod changes get their own.
	@Test
	void theLeftoverToastWaitsForThePolicy() throws IOException {
		staged();
		policy.set(ModFilesPolicy.PENDING);
		service.waitForPolicy(3);

		service.tickLeftover();
		assertTrue(toasts.isEmpty());

		policy.set(ModFilesPolicy.LAUNCHER);
		service.tickLeftover();
		service.tickLeftover();

		assertEquals(List.of("rigtune.toast.leftover.title[1]", "rigtune.toast.held.title[1]"), toasts.stream().map(LauncherRepairServiceTest::key).toList());
		assertEquals(2, warnings.size());
		assertTrue(warnings.get(1).contains("waiting for your choice"), warnings.get(1));
		assertTrue(warnings.stream().noneMatch(w -> w.contains("mod change") && w.contains("retried")), warnings.toString());
	}

	@Test
	void underRigTuneTheLeftoverToastIsTodays() throws IOException {
		staged();
		policy.set(ModFilesPolicy.RIGTUNE);
		service.waitForPolicy(3);

		service.tickLeftover();

		assertEquals(List.of("rigtune.toast.leftover.title[3]"), toasts.stream().map(LauncherRepairServiceTest::key).toList());
		assertEquals(List.of("3 staged RigTune change(s) were not applied; they will be retried at the next exit"), warnings);
	}

	// X4.4: the listener's tick while nothing waits is O(1) and allocates nothing. The fewest bytes of several runs, as
	// FrameHookBudgetTest does: a JIT compile inside one run allocates a little on this thread by itself, an allocation in
	// the tick would show in every run.
	@Test
	void anIdleTickAllocatesNothing() {
		com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		for (int i = 0; i < 20_000; i++) {
			service.tickLeftover();
		}
		long fewest = Long.MAX_VALUE;
		for (int run = 0; run < 5; run++) {
			long before = mx.getCurrentThreadAllocatedBytes();
			for (int i = 0; i < 100_000; i++) {
				service.tickLeftover();
			}
			fewest = Math.min(fewest, mx.getCurrentThreadAllocatedBytes() - before);
		}

		assertEquals(0, fewest);
	}
}
