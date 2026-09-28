package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.launcher.InstanceEvidence;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// C-M1: the probe runs off the render thread with a timeout, and any failure is Unknown; only the named signals are read.
class LauncherProbeTest {
	private static LauncherInfo await(java.util.concurrent.CompletableFuture<LauncherInfo> future) throws Exception {
		return future.get(5, TimeUnit.SECONDS);
	}

	@Test
	void theDetectorsResult() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			LauncherInfo modrinth = LauncherInfo.of(Launcher.MODRINTH_APP);
			assertEquals(modrinth, await(LauncherProbe.probeAsync(() -> modrinth, executor, 1000)));
			assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.probeAsync(() -> null, executor, 1000)));
		} finally {
			executor.shutdownNow();
		}
	}

	// v0.5 (docs/v0.5/SPEC.md 4a, AC4a.3; review L13): a detection that hasn't answered within the cap is NOT_YET: PENDING
	// for the mod-files policy, never the Unknown that would make it RIGTUNE; as a value it still reads as Unknown.
	@Test
	void aStalledDetectorTimesOutAsNotYetWhichIsPending() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch never = new CountDownLatch(1);
		try {
			LauncherInfo info = await(LauncherProbe.probeAsync(() -> {
				try {
					never.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return LauncherInfo.of(Launcher.PRISM);
			}, executor, 100));
			assertSame(LauncherProbe.NOT_YET, info);
			assertEquals(LauncherInfo.UNKNOWN, info);
			assertNotSame(LauncherInfo.UNKNOWN, LauncherProbe.NOT_YET);
			assertEquals(ModFilesPolicy.PENDING, ModFilesPolicy.of(LauncherProbe.detected(info), InstanceEvidence.NONE, false));
			assertEquals(ModFilesPolicy.RIGTUNE, ModFilesPolicy.of(LauncherProbe.detected(LauncherInfo.UNKNOWN), InstanceEvidence.NONE, false));
		} finally {
			never.countDown();
			executor.shutdownNow();
		}
	}

	// Review finding 1: a detection that outlives one caller's timeout is still used by the next one, and a finished
	// detection answers at once (it isn't queued behind other work again).
	@Test
	void aLateDetectionIsKeptForTheNextProbe() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		CountDownLatch release = new CountDownLatch(1);
		try {
			java.util.concurrent.CompletableFuture<LauncherInfo> detection = LauncherProbe.start(() -> {
				try {
					release.await();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				return LauncherInfo.of(Launcher.PRISM);
			}, executor);
			assertSame(LauncherProbe.NOT_YET, await(LauncherProbe.withTimeout(detection, 100)));
			release.countDown();
			assertEquals(LauncherInfo.of(Launcher.PRISM), detection.get(5, TimeUnit.SECONDS));
			assertEquals(LauncherInfo.of(Launcher.PRISM), await(LauncherProbe.withTimeout(detection, 1)));
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}
	
	@Test
	void theGameProbeDetectsOncePerSession() throws Exception {
		LauncherProbe.reset();
		try {
			Path gameDir = Path.of("game");
			java.util.concurrent.CompletableFuture<LauncherInfo> first = LauncherProbe.probeAsync(gameDir);
			await(first);
			assertEquals(LauncherProbe.detection(), LauncherProbe.detection(), "memoized");
			java.util.concurrent.CompletableFuture<LauncherInfo> before = LauncherProbe.detection();
			LauncherProbe.reset();
			await(LauncherProbe.probeAsync(gameDir));
			assertNotSame(before, LauncherProbe.detection(), "reset detects again");
		} finally {
			LauncherProbe.reset();
		}
	}
	
	@Test
	void anyFailureIsUnknown() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.probeAsync(() -> {
				throw new IllegalStateException("boom");
			}, executor, 1000)));
			assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.probeAsync(() -> {
				throw new StackOverflowError();
			}, executor, 1000)));
		} finally {
			executor.shutdownNow();
		}
		assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.probeAsync(() -> LauncherInfo.of(Launcher.PRISM), command -> {
			throw new RejectedExecutionException("shut down");
		}, 1000)));
	}

	// A controller as RealController.launcherDetected records probe answers (WS-L1's approved lines): recorded() keeps an
	// answer already in over a stale NOT_YET, and the late answer is recorded with one rebuild; rebuild() here builds the
	// report's policy as the post-step does.
	static final class Recorder {
		volatile LauncherInfo launcher = LauncherInfo.UNKNOWN;
		final List<ModFilesPolicy> reports = new CopyOnWriteArrayList<>();
		final List<String> threads = new CopyOnWriteArrayList<>();

		void detected(LauncherInfo detected) {
			launcher = LauncherProbe.record(detected);
			LauncherProbe.onLateAnswer(late -> {
				threads.add(Thread.currentThread().getName());
				detected(late);
				rebuild();
			});
		}

		void rebuild() {
			assertSame(LauncherProbe.detected(launcher), LauncherProbe.recorded(), "one launcher source");
			reports.add(ModFilesPolicy.of(LauncherProbe.recorded(), LauncherProbe.evidence(), false));
		}
	}

	private static void lateOnThisThread() {
		LauncherProbe.lateExecutor(Runnable::run);
	}

	private static void restore() {
		LauncherProbe.onLateAnswer(null);
		LauncherProbe.lateExecutor(null);
		LauncherProbe.reset();
	}

	// Review L6: a probe waits for the detection alone, so a slow .index/ listing never turns an answered detection into
	// NOT_YET (0.4's memory and Java-arguments advice keep their launcher); the listing's answer then comes late.
	@Test
	void theProbeWaitsForTheDetectionAlone() throws Exception {
		CompletableFuture<InstanceEvidence> listing = new CompletableFuture<>();
		LauncherProbe.begin(CompletableFuture.completedFuture(LauncherInfo.of(Launcher.OFFICIAL)), listing);
		try {
			assertEquals(LauncherInfo.of(Launcher.OFFICIAL), await(LauncherProbe.probe(100)));
			assertEquals(LauncherInfo.of(Launcher.OFFICIAL), LauncherProbe.answer());
			assertNull(LauncherProbe.evidence());
			listing.complete(new InstanceEvidence(true));
			assertEquals(new InstanceEvidence(true), LauncherProbe.evidence());
		} finally {
			restore();
		}
	}

	@Test
	void beforeAnyProbeNothingHasAnswered() {
		LauncherProbe.reset();
		assertNull(LauncherProbe.answer());
		assertNull(LauncherProbe.evidence());
		assertSame(LauncherProbe.NOT_YET, LauncherProbe.record(LauncherProbe.NOT_YET));
		assertNull(LauncherProbe.recorded());
	}

	// AC4a.3 / review H1: a slow detector; the probe answers NOT_YET, the report is PENDING; when the answer comes it is
	// recorded with exactly one rebuild, off the caller's thread, and the final policy lands in the report.
	@Test
	void aLateAnswerIsRecordedWithOneRebuild() throws Exception {
		CompletableFuture<LauncherInfo> detection = new CompletableFuture<>();
		ExecutorService late = Executors.newSingleThreadExecutor(r -> new Thread(r, "late-answer"));
		LauncherProbe.lateExecutor(late);
		LauncherProbe.begin(detection, CompletableFuture.completedFuture(InstanceEvidence.NONE));
		Recorder recorder = new Recorder();
		try {
			recorder.detected(await(LauncherProbe.probe(50)));
			recorder.rebuild();
			assertSame(LauncherProbe.NOT_YET, recorder.launcher);
			assertEquals(List.of(ModFilesPolicy.PENDING), recorder.reports);
			recorder.detected(await(LauncherProbe.probe(50)));
			detection.complete(LauncherInfo.of(Launcher.MODRINTH_APP));
			late.shutdown();
			assertTrue(late.awaitTermination(5, TimeUnit.SECONDS));
			assertEquals(List.of(ModFilesPolicy.PENDING, ModFilesPolicy.LAUNCHER), recorder.reports, "exactly one rebuild, with the final policy");
			assertEquals(LauncherInfo.of(Launcher.MODRINTH_APP), recorder.launcher);
			assertEquals(List.of("late-answer"), recorder.threads, "on the late-answer executor, never the caller's thread");
		} finally {
			late.shutdownNow();
			restore();
		}
	}

	// The race seen in the local game test: the late answer is recorded, then a stale chain hands over the NOT_YET it got
	// earlier; the answer stays, and no second rebuild follows.
	@Test
	void aStaleNotYetAfterTheLateAnswerKeepsTheAnswer() throws Exception {
		CompletableFuture<LauncherInfo> detection = new CompletableFuture<>();
		lateOnThisThread();
		LauncherProbe.begin(detection, CompletableFuture.completedFuture(InstanceEvidence.NONE));
		Recorder recorder = new Recorder();
		try {
			LauncherInfo stale = await(LauncherProbe.probe(20));
			assertSame(LauncherProbe.NOT_YET, stale);
			recorder.detected(stale);
			detection.complete(LauncherInfo.of(Launcher.ATLAUNCHER));
			assertEquals(LauncherInfo.of(Launcher.ATLAUNCHER), recorder.launcher);
			recorder.detected(stale);
			assertEquals(LauncherInfo.of(Launcher.ATLAUNCHER), recorder.launcher, "a stale NOT_YET never overwrites the answer");
			assertEquals(LauncherInfo.of(Launcher.ATLAUNCHER), LauncherProbe.recorded());
			assertEquals(List.of(ModFilesPolicy.LAUNCHER), recorder.reports);
		} finally {
			restore();
		}
	}

	// The late listing: the detection answered in time, the .index/ listing after the probe; the rebuild comes when the
	// listing does, and the policy follows the evidence.
	@Test
	void aLateListingRebuildsOnce() throws Exception {
		CompletableFuture<InstanceEvidence> listing = new CompletableFuture<>();
		lateOnThisThread();
		LauncherProbe.begin(CompletableFuture.completedFuture(LauncherInfo.UNKNOWN), listing);
		Recorder recorder = new Recorder();
		try {
			recorder.detected(await(LauncherProbe.probe(50)));
			recorder.rebuild();
			assertEquals(List.of(ModFilesPolicy.PENDING), recorder.reports, "a folder launcher waits for the listing");
			listing.complete(new InstanceEvidence(true));
			assertEquals(List.of(ModFilesPolicy.PENDING, ModFilesPolicy.LAUNCHER), recorder.reports);
		} finally {
			restore();
		}
	}

	// A detection whose probe had everything in never calls the listener.
	@Test
	void anAnswerInTimeIsNotLate() throws Exception {
		lateOnThisThread();
		LauncherProbe.begin(CompletableFuture.completedFuture(LauncherInfo.of(Launcher.ATLAUNCHER)), CompletableFuture.completedFuture(InstanceEvidence.NONE));
		Recorder recorder = new Recorder();
		try {
			recorder.detected(await(LauncherProbe.probe(1000)));
			assertEquals(LauncherInfo.of(Launcher.ATLAUNCHER), recorder.launcher);
			assertEquals(List.of(), recorder.reports);
		} finally {
			restore();
		}
	}

	// The listener set after the answer came (the timed-out chain recorded later than the answer): it gets it at once.
	@Test
	void aListenerSetAfterTheAnswerCameGetsItAtOnce() throws Exception {
		CompletableFuture<LauncherInfo> detection = new CompletableFuture<>();
		lateOnThisThread();
		LauncherProbe.begin(detection, CompletableFuture.completedFuture(InstanceEvidence.NONE));
		try {
			LauncherInfo stale = await(LauncherProbe.probe(10));
			detection.complete(LauncherInfo.of(Launcher.ATLAUNCHER));
			Recorder recorder = new Recorder();
			recorder.detected(stale);
			assertEquals(LauncherInfo.of(Launcher.ATLAUNCHER), recorder.launcher);
			assertEquals(List.of(ModFilesPolicy.LAUNCHER), recorder.reports);
		} finally {
			restore();
		}
	}

	// A game test's reset() starts a new detection: the old one's answer no longer reaches the listener.
	@Test
	void aResetDropsTheOldDetectionsLateAnswer() throws Exception {
		CompletableFuture<LauncherInfo> old = new CompletableFuture<>();
		lateOnThisThread();
		List<LauncherInfo> late = new ArrayList<>();
		LauncherProbe.onLateAnswer(late::add);
		LauncherProbe.begin(old, CompletableFuture.completedFuture(InstanceEvidence.NONE));
		try {
			assertSame(LauncherProbe.NOT_YET, await(LauncherProbe.probe(10)));
			LauncherProbe.reset();
			old.complete(LauncherInfo.of(Launcher.PRISM));
			assertEquals(List.of(), late);
		} finally {
			restore();
		}
	}

	// AC4a.2 (review L7, L14): the whole listing is one task on the executor it's given (the real path), never the caller's
	// thread; an executor that refuses it is no evidence.
	@Test
	void theListingRunsOnTheGivenExecutor(@TempDir Path dir) throws Exception {
		Path index = Files.createDirectories(dir.resolve("mods").resolve(".index"));
		Files.writeString(index.resolve("sodium.pw.toml"), "");
		Queue<Runnable> queued = new ArrayDeque<>();
		String[] resolvedOn = new String[1];
		CompletableFuture<InstanceEvidence> listing = LauncherProbe.startListing(() -> {
			resolvedOn[0] = Thread.currentThread().getName();
			return dir.resolve("mods");
		}, queued::add);
		assertFalse(listing.isDone());
		assertEquals(1, queued.size());
		assertNull(resolvedOn[0], "not even the mods folder on the caller's thread");
		Thread worker = new Thread(() -> queued.poll().run(), "evidence-worker");
		worker.start();
		worker.join();
		assertEquals("evidence-worker", resolvedOn[0]);
		assertTrue(listing.get().packwizIndex());
		assertEquals(InstanceEvidence.NONE, LauncherProbe.startListing(() -> dir, r -> {
			throw new RejectedExecutionException("full");
		}).get());
		assertEquals(InstanceEvidence.NONE, LauncherProbe.startListing(() -> {
			throw new IllegalStateException("boom");
		}, Runnable::run).get());
	}

	@Test
	void onlyTheNamedPropertiesAndVariablesAreRead() {
		List<String> askedProperties = new ArrayList<>();
		List<String> askedEnv = new ArrayList<>();
		Path gameDir = Path.of("game");
		LauncherSignals signals = LauncherProbe.signals(name -> {
			askedProperties.add(name);
			return name.equals(LauncherSignals.BRAND) ? "theseus" : null;
		}, name -> {
			askedEnv.add(name);
			return name.equals(LauncherSignals.INST_NAME) ? "Pack" : null;
		}, gameDir);
		assertEquals(LauncherSignals.PROPERTIES, askedProperties);
		assertEquals(LauncherSignals.ENV, askedEnv);
		assertEquals(Map.of(LauncherSignals.BRAND, "theseus"), signals.properties());
		assertEquals(Map.of(LauncherSignals.INST_NAME, "Pack"), signals.env());
		assertEquals(gameDir, signals.gameDir());
	}
}
