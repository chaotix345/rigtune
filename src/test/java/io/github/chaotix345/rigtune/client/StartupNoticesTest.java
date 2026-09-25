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

	@Test
	void thePrivacyToastIsOnlyShownOverTheTitleScreen() {
		StartupNotices.PrivacyToast toast = new StartupNotices.PrivacyToast();
		assertFalse(toast.onTitleScreen(), "not taken yet");
		toast.take(true);
		assertTrue(toast.onTitleScreen());
		assertFalse(toast.onTitleScreen(), "shown once");

		assertTrue(toast.onRigTuneScreen(true), "still on screen when the RigTune screen opens: hide it");
		assertFalse(toast.onRigTuneScreen(false));
		assertTrue(toast.onTitleScreen(), "shown again on the next title screen");

		assertFalse(toast.onRigTuneScreen(false), "it had run its course");
		assertFalse(toast.onTitleScreen());
	}

	@Test
	void thePrivacyToastIsNeverShownWhenItWasntTaken() {
		StartupNotices.PrivacyToast toast = new StartupNotices.PrivacyToast();
		toast.take(false);
		assertFalse(toast.onTitleScreen());
		assertFalse(toast.onRigTuneScreen(true));
	}
}
