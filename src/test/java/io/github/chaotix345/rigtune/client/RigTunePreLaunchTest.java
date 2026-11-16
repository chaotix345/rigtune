package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.V010Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md 3e, AC3.5, review B-M1: failed ops in last-apply.json are logged once per helper run (finishedAt).
class RigTunePreLaunchTest {
	@TempDir
	Path game;

	private Path mods() {
		return game.resolve("mods");
	}

	private Path config() {
		return game.resolve("config");
	}

	private void install(String resource) throws IOException {
		V010Fixtures.install(resource, ApplyResult.defaultPath(config()), mods(), config());
	}

	private List<String> warnOnce() {
		List<String> lines = new ArrayList<>();
		RigTunePreLaunch.warnOnce(config(), mods(), ClientState.load(config()), lines::add);
		return lines;
	}

	@Test
	void theRealV010FailureIsLoggedOncePerRun() throws IOException {
		install("real-instance/last-apply.json");

		List<String> first = warnOnce();

		assertEquals(2, first.size(), first.toString());
		assertTrue(first.get(0).contains("DISABLE_FILE fabric-26.2.jar") && first.get(0).contains("attempt 1 of 3")
				&& first.get(0).contains("2026-09-24T23:09:01.530708800Z"), first.get(0));
		assertTrue(first.get(1).contains("ENABLE_FILE distanthorizons") && first.get(1).contains("attempt 1 of 3"), first.get(1));
		assertEquals("2026-09-24T23:09:01.530708800Z", ClientState.load(config()).lastWarnedApply);
		assertTrue(warnOnce().isEmpty(), "the same run isn't logged again");

		install("last-apply.json");
		List<String> next = warnOnce();
		assertEquals(1, next.size(), next.toString());
		assertTrue(next.getFirst().contains("ferritecore"), next.getFirst());
	}

	@Test
	void nothingIsLoggedOrWrittenWithoutFailures() throws IOException {
		assertTrue(warnOnce().isEmpty());
		install("captured/last-apply.json");
		assertTrue(warnOnce().isEmpty());
		assertNull(ClientState.load(config()).lastWarnedApply);
		assertTrue(!Files.exists(ClientState.file(config())));
	}

	@Test
	void anUnreadableResultIsNotAProblem() throws IOException {
		Files.createDirectories(ApplyResult.defaultPath(config()).getParent());
		Files.writeString(ApplyResult.defaultPath(config()), "{not json");
		assertTrue(warnOnce().isEmpty());
	}

	// docs/v0.5/SPEC.md 4d (AC4d.4's unit part): leftover ops in mod-file groups are counted apart, and preLaunch never says
	// they "will be retried at the next exit": where a launcher keeps its own list of mods they are held for the player's
	// choice, which is known only later (the title screen's toast and WARN, HelperToasts).
	@Test
	void leftoverModFileOpsAreCountedApartAndNotCalledRetried() throws IOException {
		Path download = mods().resolve("sodium-0.7.1.jar.rigtune-pending");
		List<Op> update = PendingActions.group(
				Op.disableFile(mods().resolve("sodium-0.7.0.jar")),
				Op.enableFile(download, mods().resolve("sodium-0.7.1.jar")));
		List<Op> ops = new ArrayList<>(update);
		ops.add(Op.patchJson(config().resolve("sodium-options.json"), Map.of("a", "1")));
		PendingActions.create(1, mods(), config(), ops)
				.save(PendingActions.defaultPath(config()));
		List<String> lines = new ArrayList<>();

		RigTunePreLaunch.readState(config(), false, null, null, lines::add);

		assertEquals(3, RigTunePreLaunch.takeLeftoverOps());
		assertEquals(2, RigTunePreLaunch.takeLeftoverFileOps());
		assertEquals(List.of(), lines);
		RigTunePreLaunch.takeUnseenResult();
	}

	@Test
	void leftoverPatchesOnlyKeepTodaysWarn() throws IOException {
		PendingActions.create(1, mods(), config(), List.of(
				Op.patchJson(config().resolve("sodium-options.json"), Map.of("a", "1"))))
				.save(PendingActions.defaultPath(config()));
		List<String> lines = new ArrayList<>();

		RigTunePreLaunch.readState(config(), false, null, null, lines::add);

		assertEquals(1, RigTunePreLaunch.takeLeftoverOps());
		assertEquals(0, RigTunePreLaunch.takeLeftoverFileOps());
		assertEquals(List.of("1 staged RigTune change(s) were not applied; they will be retried at the next exit"), lines);
		RigTunePreLaunch.takeUnseenResult();
	}

	// preLaunch's reads run on a RigTune thread, off the render thread, and a failure there never reaches the game.
	@Test
	void theStartupRecordsAreReadOnADaemonRigTuneThread() {
		AtomicReference<Thread> ranOn = new AtomicReference<>();
		RigTunePreLaunch.startRecords(() -> ranOn.set(Thread.currentThread()));
		RigTunePreLaunch.awaitRecords();
		assertTrue(ranOn.get() != Thread.currentThread() && ranOn.get().getName().startsWith(FootprintStats.THREAD_PREFIX),
				"read on a RigTune thread: " + ranOn.get());
		assertTrue(ranOn.get().isDaemon());

		RigTunePreLaunch.startRecords(() -> {
			throw new IllegalStateException("unreadable");
		});
		RigTunePreLaunch.awaitRecords();
		assertTrue(RigTunePreLaunch.recordsDone());
	}

	// The CLIENT_STARTED handler (afterRecords) stays blocked while the reads are pending, then starts with their results.
	@Test
	void theStartWaitsForTheRecordsAndSeesTheirResults() throws IOException, InterruptedException {
		RigTunePreLaunch.takeLeftoverOps();
		PendingActions.create(1, mods(), config(), List.of(
				Op.patchJson(config().resolve("sodium-options.json"), Map.of("a", "1"))))
				.save(PendingActions.defaultPath(config()));
		CountDownLatch release = new CountDownLatch(1);
		RigTunePreLaunch.startRecords(() -> {
			try {
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			RigTunePreLaunch.readState(config(), false, null, null, line -> {
			});
		});
		AtomicInteger seen = new AtomicInteger(-1);
		Thread renderThread = new Thread(RigTunePreLaunch.afterRecords(() -> seen.set(RigTunePreLaunch.takeLeftoverOps())), "fake render thread");
		renderThread.start();
		renderThread.join(500);
		assertTrue(renderThread.isAlive(), "the start waits while the records are pending");
		assertEquals(Thread.State.WAITING, renderThread.getState());
		assertEquals(-1, seen.get());

		release.countDown();
		renderThread.join(30_000);
		assertFalse(renderThread.isAlive());
		assertEquals(1, seen.get(), "the start sees what the records thread read");
		assertTrue(RigTunePreLaunch.startedAfterRecords());
		RigTunePreLaunch.takeLeftoverFileOps();
		RigTunePreLaunch.takeUnseenResult();
	}

	@Test
	void withoutAThreadTheRecordsAreReadHere() {
		AtomicReference<Thread> ranOn = new AtomicReference<>();
		RigTunePreLaunch.startRecords(() -> ranOn.set(Thread.currentThread()), task -> {
			throw new OutOfMemoryError("unable to create native thread");
		});
		assertTrue(RigTunePreLaunch.recordsDone());
		assertEquals(Thread.currentThread(), ranOn.get());
		RigTunePreLaunch.awaitRecords();
	}

	// The wait for a helper still running stays on preLaunch's own thread (mods read their configs after it), and the lock is
	// released again for the records thread.
	@Test
	void preLaunchItselfWaitsForABusyHelper() throws Exception {
		Path lockFile = ApplyLock.defaultPath(config());
		assertEquals(new RigTunePreLaunch.HelperCheck(false, true), RigTunePreLaunch.checkHelper(lockFile, Duration.ofSeconds(5)));

		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread helper = new Thread(() -> {
			try (ApplyLock lock = ApplyLock.acquire(lockFile, Duration.ZERO)) {
				held.countDown();
				release.await(30, TimeUnit.SECONDS);
			} catch (IOException | InterruptedException e) {
				throw new AssertionError(e);
			}
		}, "fake-apply-helper");
		helper.start();
		assertTrue(held.await(30, TimeUnit.SECONDS));
		long start = System.nanoTime();
		RigTunePreLaunch.HelperCheck stillRunning = RigTunePreLaunch.checkHelper(lockFile, Duration.ofMillis(600));
		long waited = Duration.ofNanos(System.nanoTime() - start).toMillis();
		assertEquals(new RigTunePreLaunch.HelperCheck(true, false), stillRunning);
		assertTrue(waited >= 550, "waited for the helper: " + waited + " ms");

		Thread releaser = new Thread(() -> {
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			release.countDown();
		});
		releaser.start();
		start = System.nanoTime();
		RigTunePreLaunch.HelperCheck finished = RigTunePreLaunch.checkHelper(lockFile, Duration.ofSeconds(10));
		waited = Duration.ofNanos(System.nanoTime() - start).toMillis();
		assertEquals(new RigTunePreLaunch.HelperCheck(true, true), finished);
		assertTrue(waited >= 250 && waited < 10_000, "waited until the helper finished: " + waited + " ms");
		helper.join(30_000);

		AtomicReference<ApplyLock> other = new AtomicReference<>();
		Thread records = new Thread(() -> {
			try {
				other.set(ApplyLock.acquire(lockFile, Duration.ZERO));
				other.get().close();
			} catch (IOException e) {
				throw new AssertionError(e);
			}
		});
		records.start();
		records.join(30_000);
		assertTrue(other.get() != null, "checkHelper released the lock for another thread");
	}
}
