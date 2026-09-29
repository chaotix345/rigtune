package io.github.chaotix345.rigtune.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupNoticesTest {
	@Test
	void thePrivacyNoticeIsShownOnce(@TempDir Path configDir) {
		ClientSettings settings = ClientSettings.load(configDir);

		assertTrue(StartupNotices.takePrivacyNotice(settings, configDir));
		assertFalse(StartupNotices.takePrivacyNotice(settings, configDir));
		assertTrue(SettingsSaver.shared().flush(2_000));
		assertFalse(StartupNotices.takePrivacyNotice(ClientSettings.load(configDir), configDir), "remembered in settings.json");
	}

	// v0.5 L6 (docs/v0.5/SPEC.md 2R, X8; AC2R.2): taking the notice is a pure state change; the flag is saved through
	// SettingsSaver (its own thread, flushed on quit), so a quick quit keeps it and it can't race a newer save.
	@Test
	void takingTheNoticeIsAPureStateChange(@TempDir Path configDir) {
		ClientSettings settings = ClientSettings.load(configDir);
		assertTrue(StartupNotices.takePrivacyNotice(settings));
		assertTrue(settings.privacyNoticeShown);
		assertFalse(StartupNotices.takePrivacyNotice(settings));
		assertFalse(Files.exists(ClientSettings.file(configDir)), "nothing written");
	}

	@Test
	void theFlagIsSavedThroughSettingsSaver(@TempDir Path configDir) {
		ClientSettings settings = ClientSettings.load(configDir);
		assertTrue(StartupNotices.takePrivacyNotice(settings, configDir));
		assertTrue(SettingsSaver.shared().flush(2_000), "the queued save is SettingsSaver's");
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
		assertFalse(toast.onTitleScreen(false), "not taken yet");
		toast.take(true);
		assertTrue(toast.onTitleScreen(false));
		assertFalse(toast.onTitleScreen(false), "shown once");

		assertTrue(toast.onRigTuneScreen(true), "still on screen when the RigTune screen opens: hide it");
		assertFalse(toast.onRigTuneScreen(false));
		assertTrue(toast.onTitleScreen(false), "shown again on the next title screen");

		assertFalse(toast.onRigTuneScreen(false), "it had run its course");
		assertFalse(toast.onTitleScreen(false));
	}

	// #23: leaving the benchmark world passes a title screen before its results open; the toast waits for a real one.
	@Test
	void thePrivacyToastWaitsOutTheBenchmarkWorldsHandOff() {
		StartupNotices.PrivacyToast toast = new StartupNotices.PrivacyToast();
		toast.take(true);
		assertFalse(toast.onTitleScreen(true), "not over a hand-off title screen");
		assertTrue(toast.onTitleScreen(false));

		assertTrue(toast.onRigTuneScreen(true));
		assertFalse(toast.onTitleScreen(true), "the benchmark world is being left");
		assertTrue(toast.onTitleScreen(false), "shown on the next real title screen");
	}

	@Test
	void thePrivacyToastIsNeverShownWhenItWasntTaken() {
		StartupNotices.PrivacyToast toast = new StartupNotices.PrivacyToast();
		toast.take(false);
		assertFalse(toast.onTitleScreen(false));
		assertFalse(toast.onRigTuneScreen(true));
	}
}
