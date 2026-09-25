package io.github.chaotix345.rigtune.client;

import java.nio.file.Path;

// The title-screen toasts that depend on the settings (docs/v0.2/SPEC.md item 8).
public final class StartupNotices {
	private StartupNotices() {
	}

	// The one-time "RigTune can work offline" toast: true the first time only, remembered in settings.json.
	public static boolean takePrivacyNotice(ClientSettings settings, Path configDir) {
		synchronized (settings) {
			if (settings.privacyNoticeShown) {
				return false;
			}
			settings.privacyNoticeShown = true;
			settings.save(configDir);
			return true;
		}
	}

	// The "RigTune: N suggestions" toast. Apply results and warnings are shown whatever this says.
	public static boolean showSuggestionsToast(ClientSettings settings, long important) {
		return settings.startupToast && important > 0;
	}
}
