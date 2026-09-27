package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md X4 (PLAN contracts items 2-3): the lazy holder makes each service once, on first use; the services'
// constructors only store the controller (a null one here: nothing is dereferenced); a resolution on the render thread
// inside the startup window sets FootprintStats' flag, a worker's meanwhile doesn't (AC-X.2, unit). The test's own thread
// stands in for the render thread.
class V05ServicesTest {
	@Test
	void eachServiceIsMadeOnceAndAnswersTheContractDefaults() {
		V05Services services = new V05Services(null);
		assertSame(services.modFiles(), services.modFiles());
		assertSame(services.launcherRepair(), services.launcherRepair());
		assertSame(services.firstRun(), services.firstRun());
		assertSame(services.tryIt(), services.tryIt());
		assertSame(services.serverProfiles(), services.serverProfiles());
		assertSame(services.stutterFixes(), services.stutterFixes());
		assertEquals(ModFilesPolicy.RIGTUNE, services.modFiles().policy());
		assertEquals(FirstRun.Status.UNKNOWN, services.firstRun().status());
		assertEquals(TryItView.EMPTY, services.tryIt().view());
		assertEquals(ServerProfilesView.EMPTY, services.serverProfiles().view());
		assertEquals(List.of(), services.stutterFixes().holds());
		assertEquals(Thread.currentThread().getName(), services.createdOn());
	}

	@Test
	void aWorkerResolvingInsideTheWindowLeavesTheFlagUnset() {
		FootprintStats.clearRenderThreadResolve();
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			FootprintStats.clientStarted(() -> CompletableFuture.runAsync(() -> new V05Services(null).firstRun().load(), worker).join());
		} finally {
			worker.shutdownNow();
		}
		assertNull(FootprintStats.renderThreadResolve());
	}

	@Test
	void theRenderThreadResolvingInsideTheWindowSetsIt() {
		FootprintStats.clearRenderThreadResolve();
		V05Services services = new V05Services(null);
		FootprintStats.clientStarted(() -> services.tryIt().view());
		assertEquals("TryItService during the CLIENT_STARTED handler", FootprintStats.renderThreadResolve());
		FootprintStats.clearRenderThreadResolve();
		FootprintStats.clientStarted(() -> new V05Services(null));
		assertTrue(FootprintStats.renderThreadResolve().startsWith("the v0.5 services during"), "making the holder counts too");
		FootprintStats.clearRenderThreadResolve();
	}

	@Test
	void theRenderThreadAfterTheWindowLeavesItUnset() {
		FootprintStats.clearRenderThreadResolve();
		new V05Services(null).stutterFixes().holds();
		assertNull(FootprintStats.renderThreadResolve());
	}

	// Every hook step runs inside step(...): whatever it throws, errors included, is logged and goes no further.
	@Test
	void aStepThatThrowsIsContained() {
		List<String> ran = new ArrayList<>();
		V05Services.step("throws", () -> {
			throw new IllegalStateException("boom");
		});
		V05Services.step("an error", () -> {
			throw new NoClassDefFoundError("boom");
		});
		V05Services.step("next", () -> ran.add("next"));
		assertEquals(List.of("next"), ran);
	}
}
