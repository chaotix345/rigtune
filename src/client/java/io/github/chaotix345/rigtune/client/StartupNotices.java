package io.github.chaotix345.rigtune.client;

import java.nio.file.Path;
import java.util.concurrent.Executor;

// The title-screen toasts that depend on the settings (docs/v0.2/SPEC.md item 8).
public final class StartupNotices {
	private StartupNotices() {
	}

	// The one-time "RigTune uses the network" toast: true the first time only. The flag is set at once and
	// written to settings.json on the given executor, off the render thread.
	public static boolean takePrivacyNotice(ClientSettings settings, Path configDir, Executor io) {
		synchronized (settings) {
			if (settings.privacyNoticeShown) {
				return false;
			}
			settings.privacyNoticeShown = true;
		}
		io.execute(() -> settings.save(configDir));
		return true;
	}

	// The "RigTune: N suggestions" toast. Apply results and warnings are shown whatever this says.
	public static boolean showSuggestionsToast(ClientSettings settings, long important) {
		return settings.startupToast && important > 0;
	}
}
