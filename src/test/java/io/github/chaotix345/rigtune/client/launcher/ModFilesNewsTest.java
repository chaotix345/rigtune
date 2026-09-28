package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.client.FirstRunService;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.launcher.InstanceEvidence;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeBoard;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4b, AC4b.6 (unit): MOD_FILES_NEWS, "RigTune now leaves this instance's mod files to <launcher>", once
// per instance for a RETURNING player under LAUNCHER, with Settings as its action; never for a NEW player (or before
// FirstRunService knows), never under RIGTUNE or PENDING, and not again once dismissed (awareness.json, by its key).
class ModFilesNewsTest {
	private final AtomicReference<LauncherInfo> launcher = new AtomicReference<>(LauncherInfo.of(Launcher.MODRINTH_APP));
	private final AtomicReference<InstanceEvidence> evidence = new AtomicReference<>(InstanceEvidence.NONE);
	private final AtomicBoolean optIn = new AtomicBoolean();
	private final ModFilesService service = new ModFilesService(null, launcher::get, evidence::get, optIn::get);

	@Test
	void aReturningPlayerUnderLauncherSeesItOnce() {
		Notice notice = service.news(FirstRun.Status.RETURNING);
		assertNotNull(notice);
		assertEquals(ModFilesService.NEWS_KEY, notice.key());
		assertEquals(NoticePriority.MOD_FILES_NEWS, notice.priority());
		assertEquals("RigTune now leaves this instance's mod files to the Modrinth App", notice.message().english());
		assertEquals("Installing, updating and turning off mods now come with the launcher's own steps. Settings → Mod files lets RigTune change them anyway.",
				notice.detail().english());
		assertTrue(notice.dismissible());
		assertEquals(List.of(ModFilesService.NEWS_SETTINGS), notice.actions().stream().map(a -> a.id()).toList());
		assertEquals("Settings…", notice.actions().getFirst().label().english());
		assertEquals(List.of(notice), NoticeBoard.select(List.of(notice), Set.of()).visible());
		assertEquals(List.of(), NoticeBoard.select(List.of(notice), Set.of(ModFilesService.NEWS_KEY)).visible(), "not again once dismissed");
	}

	// Review-11 FEAT-1: a player new to RigTune is RETURNING after their first Apply, but never a 0.4 upgrader: the news
	// stays away in that session (the notice source asks with what load() read; the red run asked with status()).
	@Test
	void aNewPlayersFirstApplyDoesntBringTheNews() {
		FirstRunService firstRun = new FirstRunService(null);
		firstRun.forceStatusForTests(FirstRun.Status.NEW);
		assertNull(service.news(firstRun.loadedStatus()));
		firstRun.applied(null);
		assertEquals(FirstRun.Status.RETURNING, firstRun.status());
		assertNull(service.news(firstRun.loadedStatus()), "a new player's first Apply shows the 0.4-upgrade news");
	}

	@Test
	void neverForANewPlayerOrBeforeItIsKnown() {
		assertNull(service.news(FirstRun.Status.NEW));
		assertNull(service.news(FirstRun.Status.UNKNOWN));
	}

	@Test
	void neverUnderRigTuneOrPending() {
		optIn.set(true);
		assertNull(service.news(FirstRun.Status.RETURNING), "opted in: RIGTUNE");
		optIn.set(false);
		launcher.set(LauncherInfo.of(Launcher.OFFICIAL));
		assertNull(service.news(FirstRun.Status.RETURNING), "RIGTUNE");
		launcher.set(null);
		assertNull(service.news(FirstRun.Status.RETURNING), "PENDING");
	}

	// A packwiz index under an unknown launcher: the launcher isn't named, and no launcher steps are promised (review L9).
	@Test
	void anUnnamedLauncher() {
		launcher.set(LauncherInfo.UNKNOWN);
		evidence.set(new InstanceEvidence(true));
		Notice notice = service.news(FirstRun.Status.RETURNING);
		assertEquals("RigTune now leaves this instance's mod files to your launcher", notice.message().english());
		assertEquals("Installing, updating and turning off mods are now left to your launcher. Settings → Mod files lets RigTune change them anyway.",
				notice.detail().english());
	}
}
