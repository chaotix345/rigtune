package io.github.chaotix345.rigtune.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupNoticesTest {
	@Test
	void thePrivacyNoticeIsShownOnce(@TempDir Path configDir) {
		ClientSettings settings = ClientSettings.load(configDir);

		assertTrue(StartupNotices.takePrivacyNotice(settings, configDir));
		assertFalse(StartupNotices.takePrivacyNotice(settings, configDir));
		assertFalse(StartupNotices.takePrivacyNotice(ClientSettings.load(configDir), configDir), "remembered in settings.json");
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
