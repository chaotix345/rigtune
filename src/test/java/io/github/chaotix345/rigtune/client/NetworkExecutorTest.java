package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.Fixtures;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthProject;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import io.github.chaotix345.rigtune.core.modrinth.OnlineDataFetcher;
import io.github.chaotix345.rigtune.core.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Phase 5 P5A-F4: a Modrinth lookup waits up to 10 s to connect and 20 s per request. It runs on its own network threads,
// so the worker pool's History loads, Undo plans, report rebuilds and saves never queue behind a slow Modrinth.
class NetworkExecutorTest {
	private final HardwareProfile hw = Fixtures.userRig().build();

	// A Modrinth that answers only once released, as one that is slow to connect.
	private static final class StuckModrinth implements ModrinthClient {
		final CountDownLatch inside;
		final CountDownLatch release = new CountDownLatch(1);
		volatile String thread;

		StuckModrinth(int callers) {
			inside = new CountDownLatch(callers);
		}

		private void stall() throws IOException {
			thread = Thread.currentThread().getName();
			inside.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException(e);
			}
		}

		@Override
		public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) throws IOException {
			stall();
			return Map.of();
		}

		@Override
		public Map<String, ModrinthVersion> latestVersionsByHashes(Collection<String> sha1s, String loader, String gameVersion) throws IOException {
			return Map.of();
		}

		@Override
		public List<ModrinthProject> projects(Collection<String> idsOrSlugs) throws IOException {
			stall();
			return List.of();
		}

		@Override
		public Optional<ModrinthVersion> latestVersion(String idOrSlug, String loader, String gameVersion) throws IOException {
			return Optional.empty();
		}

		@Override
		public void download(ModFile file, Path target) throws IOException {
			stall();
		}
	}

	private OnlineLookupGate.Lookup lookup() {
		OnlineLookupGate gate = new OnlineLookupGate(() -> null);
		return gate.next(Fixtures.mods("sodium", "lithium"), RulesLoader.parse("{\"schemaVersion\":2,\"revision\":1,\"mods\":[{\"slug\":\"sodium\",\"modIds\":[\"sodium\"]}]}"), hw);
	}

	@Test
	void aHistoryLoadCompletesWhileEveryNetworkThreadWaitsOnModrinth() throws Exception {
		ExecutorService worker = Executors.newFixedThreadPool(2);
		ExecutorService network = Executors.newFixedThreadPool(2, r -> new Thread(r, "test network"));
		StuckModrinth modrinth = new StuckModrinth(2);
		try {
			CompletableFuture<OnlineDataFetcher.Result> first = lookup().start(modrinth, network);
			CompletableFuture<OnlineDataFetcher.Result> second = lookup().start(modrinth, network);
			assertTrue(modrinth.inside.await(10, TimeUnit.SECONDS), "both lookups are waiting on Modrinth");

			assertEquals("history", CompletableFuture.supplyAsync(() -> "history", worker).get(5, TimeUnit.SECONDS));
			assertEquals("test network", modrinth.thread);

			modrinth.release.countDown();
			assertNotNull(first.get(10, TimeUnit.SECONDS));
			assertNotNull(second.get(10, TimeUnit.SECONDS));
		} finally {
			modrinth.release.countDown();
			worker.shutdownNow();
			network.shutdownNow();
		}
	}

	@Test
	void theNetworkThreadsAreTheirOwnDaemonPool() throws Exception {
		assertNotSame(Probes.EXECUTOR, Probes.NETWORK);
		Thread thread = CompletableFuture.supplyAsync(Thread::currentThread, Probes.NETWORK).get(10, TimeUnit.SECONDS);
		assertEquals("RigTune network", thread.getName());
		assertTrue(thread.isDaemon());
	}
}
