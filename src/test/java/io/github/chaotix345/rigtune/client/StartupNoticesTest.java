package io.github.chaotix345.rigtune.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupNoticesTest {
	@Test
	void thePrivacyNoticeIsShownOnce(@TempDir Path configDir) {
		ClientSettings settings = ClientSettings.load(configDir);

		assertTrue(StartupNotices.takePrivacyNotice(settings, configDir, Runnable::run));
		assertFalse(StartupNotices.takePrivacyNotice(settings, configDir, Runnable::run));
		assertFalse(StartupNotices.takePrivacyNotice(ClientSettings.load(configDir), configDir, Runnable::run), "remembered in settings.json");
	}

	@Test
	void theNoticeIsTakenAtOnceAndSavedOnTheGivenExecutor(@TempDir Path configDir) {
		ClientSettings settings = ClientSettings.load(configDir);
		List<Runnable> queued = new ArrayList<>();

		assertTrue(StartupNotices.takePrivacyNotice(settings, configDir, queued::add));
		assertFalse(StartupNotices.takePrivacyNotice(settings, configDir, queued::add));
		assertFalse(Files.exists(ClientSettings.file(configDir)), "nothing written on the calling thread");
		queued.forEach(Runnable::run);
		assertTrue(ClientSettings.load(configDir).privacyNoticeShown);
	}

	@Test
	void theSuggestionsToastFollowsTheSwitch() {
		ClientSettings settings = new ClientSettings();
		assertTrue(StartupNotices.showSuggestionsToast(settings, 3));
		assertFalse(StartupNotices.showSuggestionsToast(settings, 0));

		settings.startupToast = false;
		assertFalse(StartupNotices.showSuggestionsToast(settings, 3));
	}
}
