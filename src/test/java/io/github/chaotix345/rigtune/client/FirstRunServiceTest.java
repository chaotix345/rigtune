package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.launcher.ModFilesService;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeBoard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 8 (AC8.5, AC8.9, AC8.15): the player's first-run status, in memory only. UNKNOWN until load() (the
// start hook's step, on a worker) has read the disk; every Apply by any path (the after-apply hook) makes RETURNING, and a
// load that finishes after it never turns it back; a failed read never shows the guide or the confirmation.
class FirstRunServiceTest {
	private static V05Hooks.ApplyFacts facts() {
		return new V05Hooks.ApplyFacts("e1", List.of(), Set.of(), 1, 0, 0, false, 0);
	}

	@Test
	void unknownUntilLoaded() {
		FirstRunService service = new FirstRunService(null);
		assertEquals(FirstRun.Status.UNKNOWN, service.status());
		assertFalse(service.firstApplyPending());
		assertNull(service.loadedOn());
	}

	@Test
	void aNewPlayerUntilTheFirstApply(@TempDir Path configDir) {
		FirstRunService service = new FirstRunService(null);
		service.load(() -> FirstRun.isNew(new Journal(configDir, "0.5.0", "26.2", (m, e) -> {
		}), configDir));
		assertEquals(FirstRun.Status.NEW, service.status());
		assertTrue(service.firstApplyPending());

		service.applied(facts());
		assertEquals(FirstRun.Status.RETURNING, service.status());
		assertFalse(service.firstApplyPending(), "the confirmation opens at most once");
	}

	@Test
	void aReturningPlayer(@TempDir Path configDir) throws IOException {
		Files.createDirectories(PendingActions.defaultPath(configDir).getParent());
		Files.writeString(PendingActions.defaultPath(configDir), "{\"ops\": []}");
		FirstRunService service = new FirstRunService(null);
		service.load(() -> FirstRun.isNew(new Journal(configDir, "0.5.0", "26.2", (m, e) -> {
		}), configDir));
		assertEquals(FirstRun.Status.RETURNING, service.status());
		assertFalse(service.firstApplyPending());
	}

	// Review-11 FEAT-1: a NEW read stores MOD_FILES_NEWS's dismissal; after the first Apply loadedStatus() still says NEW,
	// and the next launch (RETURNING from this one's records) keeps the news hidden. A returning read stores nothing.
	@Test
	void aNewPlayerNeverGetsTheModFilesNews(@TempDir Path configDir) {
		FirstRunService first = new FirstRunService(null, key -> FirstRunService.keepDismissed(configDir, key));
		first.load(() -> true);
		assertEquals(FirstRun.Status.NEW, first.loadedStatus());
		assertTrue(AwarenessStore.shared(configDir).dismissed().contains(ModFilesService.NEWS_KEY));
		first.applied(facts());
		assertEquals(FirstRun.Status.RETURNING, first.status());
		assertEquals(FirstRun.Status.NEW, first.loadedStatus(), "the news goes by what load() read");

		FirstRunService next = new FirstRunService(null, key -> FirstRunService.keepDismissed(configDir, key));
		next.load(() -> false);
		assertEquals(FirstRun.Status.RETURNING, next.loadedStatus());
		Notice news = ModFilesService.newsNotice(LauncherInfo.of(Launcher.MODRINTH_APP));
		assertEquals(List.of(), NoticeBoard.select(List.of(news), AwarenessStore.shared(configDir).dismissed()).visible(), "not at the next launch either");
	}

	@Test
	void aReturningReadStoresNoDismissal() {
		List<String> dismissed = new ArrayList<>();
		FirstRunService service = new FirstRunService(null, dismissed::add);
		service.load(() -> false);
		assertEquals(FirstRun.Status.RETURNING, service.loadedStatus());
		service.applied(facts());
		assertEquals(List.of(), dismissed);
	}

	// An Apply before the (slow) load finished: the load mustn't make a new player of them again.
	@Test
	void aLoadAfterAnApplyStaysReturning() {
		FirstRunService service = new FirstRunService(null);
		service.applied(facts());
		service.load(() -> true);
		assertEquals(FirstRun.Status.RETURNING, service.status());
		assertFalse(service.firstApplyPending());
		assertEquals(FirstRun.Status.UNKNOWN, service.loadedStatus(), "no news in a session whose load came after an Apply");
	}

	@Test
	void aFailedReadIsReturning() {
		FirstRunService service = new FirstRunService(null);
		service.load(() -> {
			throw new IllegalStateException("history.json unreadable");
		});
		assertEquals(FirstRun.Status.RETURNING, service.status());
		// The start hook's own load with no controller (V05ServicesTest's null one) fails the same contained way.
		FirstRunService unwired = new FirstRunService(null);
		unwired.load();
		assertEquals(FirstRun.Status.RETURNING, unwired.status());
	}

	@Test
	void loadedOnce() {
		FirstRunService service = new FirstRunService(null);
		service.load(() -> true);
		service.load(() -> false);
		assertEquals(FirstRun.Status.NEW, service.status());
	}

	// AC8.15: load() does its reading on the thread that runs it (the start hook's Probes.EXECUTOR task) and says which.
	@Test
	void theLoadRecordsItsThread() {
		FirstRunService service = new FirstRunService(null);
		ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "RigTune worker (test)"));
		try {
			CompletableFuture.runAsync(() -> service.load(() -> true), worker).join();
		} finally {
			worker.shutdownNow();
		}
		assertEquals("RigTune worker (test)", service.loadedOn());
	}

	// FirstApplyGameTest restores a new player through this when it isn't the first class in its JVM (SPEC C6).
	@Test
	void theTestSeam() {
		FirstRunService service = new FirstRunService(null);
		service.applied(facts());
		service.forceStatusForTests(FirstRun.Status.NEW);
		assertTrue(service.firstApplyPending());
	}
}
