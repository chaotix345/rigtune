package io.github.chaotix345.rigtune.client.probe;

import io.github.chaotix345.rigtune.core.launcher.InstanceEvidence;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherSignals;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

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

	@Test
	void aStalledDetectorTimesOutAsUnknown() throws Exception {
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
			assertEquals(LauncherInfo.UNKNOWN, info);
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
			assertEquals(LauncherInfo.UNKNOWN, await(LauncherProbe.withTimeout(detection, 100)));
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

	// v0.5 (docs/v0.5/SPEC.md 4a, AC4a.3): a detection that hasn't answered within the cap is NOT_YET (PENDING for the
	// mod-files policy), never the Unknown that would make it RIGTUNE; as a value it still reads as Unknown for the rest.
	@Test
	void aStalledDetectorIsNotYet() throws Exception {
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
		} finally {
			never.countDown();
			executor.shutdownNow();
		}
	}

	// The probe answers once both the detection and the .index/ listing have (the policy needs both); until then the
	// detection's own answer is already there for the policy (evidence first needs the listing, the rest the launcher).
	@Test
	void theProbeWaitsForTheListingToo() throws Exception {
		CompletableFuture<LauncherInfo> detection = CompletableFuture.completedFuture(LauncherInfo.of(Launcher.OFFICIAL));
		CompletableFuture<InstanceEvidence> listing = new CompletableFuture<>();
		LauncherProbe.begin(detection, listing);
		try {
			assertSame(LauncherProbe.NOT_YET, await(LauncherProbe.probe(100)));
			assertEquals(LauncherInfo.of(Launcher.OFFICIAL), LauncherProbe.answer());
			assertNull(LauncherProbe.evidence());
			listing.complete(new InstanceEvidence(true));
			assertEquals(LauncherInfo.of(Launcher.OFFICIAL), await(LauncherProbe.probe(100)));
			assertEquals(new InstanceEvidence(true), LauncherProbe.evidence());
		} finally {
			LauncherProbe.reset();
		}
	}

	@Test
	void beforeAnyProbeNothingHasAnswered() {
		LauncherProbe.reset();
		assertNull(LauncherProbe.answer());
		assertNull(LauncherProbe.evidence());
	}

	// AC4a.3: when the answer comes after a probe's cap, the late-answer listener gets it exactly once per detection,
	// however many probes timed out.
	@Test
	void aLateAnswerIsHandedOnOnce() throws Exception {
		CompletableFuture<LauncherInfo> detection = new CompletableFuture<>();
		List<LauncherInfo> late = new CopyOnWriteArrayList<>();
		LauncherProbe.onLateAnswer(late::add);
		LauncherProbe.begin(detection, CompletableFuture.completedFuture(InstanceEvidence.NONE));
		try {
			assertSame(LauncherProbe.NOT_YET, await(LauncherProbe.probe(50)));
			assertNull(LauncherProbe.answer());
			assertSame(LauncherProbe.NOT_YET, await(LauncherProbe.probe(50)));
			assertEquals(List.of(), late);
			detection.complete(LauncherInfo.of(Launcher.MODRINTH_APP));
			assertEquals(List.of(LauncherInfo.of(Launcher.MODRINTH_APP)), late);
			LauncherProbe.onLateAnswer(late::add);
			assertEquals(1, late.size(), "once per detection");
			assertEquals(LauncherInfo.of(Launcher.MODRINTH_APP), await(LauncherProbe.probe(50)));
		} finally {
			LauncherProbe.onLateAnswer(ignored -> {});
			LauncherProbe.reset();
		}
	}

	// A detection that answers within the cap never calls the listener.
	@Test
	void anAnswerInTimeIsNotLate() throws Exception {
		List<LauncherInfo> late = new ArrayList<>();
		LauncherProbe.onLateAnswer(late::add);
		LauncherProbe.begin(CompletableFuture.completedFuture(LauncherInfo.of(Launcher.ATLAUNCHER)), CompletableFuture.completedFuture(InstanceEvidence.NONE));
		try {
			assertEquals(LauncherInfo.of(Launcher.ATLAUNCHER), await(LauncherProbe.probe(1000)));
			assertEquals(List.of(), late);
		} finally {
			LauncherProbe.onLateAnswer(ignored -> {});
			LauncherProbe.reset();
		}
	}

	// The listener set after the timeout and after the answer came (the report's first rebuild made it): it gets the
	// answer at once.
	@Test
	void aListenerSetAfterTheAnswerCameGetsItAtOnce() throws Exception {
		CompletableFuture<LauncherInfo> detection = new CompletableFuture<>();
		LauncherProbe.onLateAnswer(ignored -> {});
		LauncherProbe.begin(detection, CompletableFuture.completedFuture(InstanceEvidence.NONE));
		try {
			LauncherProbe.onLateAnswer(null);
			assertSame(LauncherProbe.NOT_YET, await(LauncherProbe.probe(10)));
			detection.complete(LauncherInfo.of(Launcher.ATLAUNCHER));
			List<LauncherInfo> late = new ArrayList<>();
			LauncherProbe.onLateAnswer(late::add);
			assertEquals(List.of(LauncherInfo.of(Launcher.ATLAUNCHER)), late);
		} finally {
			LauncherProbe.onLateAnswer(ignored -> {});
			LauncherProbe.reset();
		}
	}

	// A game test's reset() starts a new detection: the old one's answer no longer reaches the listener.
	@Test
	void aResetDropsTheOldDetectionsLateAnswer() throws Exception {
		CompletableFuture<LauncherInfo> old = new CompletableFuture<>();
		List<LauncherInfo> late = new ArrayList<>();
		LauncherProbe.onLateAnswer(late::add);
		LauncherProbe.begin(old, CompletableFuture.completedFuture(InstanceEvidence.NONE));
		assertSame(LauncherProbe.NOT_YET, await(LauncherProbe.probe(10)));
		LauncherProbe.reset();
		old.complete(LauncherInfo.of(Launcher.PRISM));
		assertEquals(List.of(), late);
		LauncherProbe.onLateAnswer(ignored -> {});
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
