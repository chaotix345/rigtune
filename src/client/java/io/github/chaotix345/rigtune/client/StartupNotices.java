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

	// When the one-time privacy toast is on screen. Opened over the RigTune screen it would cover the tier text and the
	// Settings button (Phase 5 finding 5), so it is only shown over the title screen; if the RigTune screen opens while
	// it's still showing, it's hidden there and shown again on the next title screen.
	public static final class PrivacyToast {
		private boolean due;
		private boolean showing;

		public void take(boolean firstTime) {
			due |= firstTime;
		}

		// On the title screen: true when the toast should be shown now.
		public boolean onTitleScreen() {
			if (!due) {
				return false;
			}
			due = false;
			showing = true;
			return true;
		}

		// On the RigTune screen, with whether the toast is still on screen: true when it should be hidden now.
		public boolean onRigTuneScreen(boolean stillShown) {
			if (!showing) {
				return false;
			}
			showing = false;
			due = stillShown;
			return stillShown;
		}
	}

	// The "RigTune: N suggestions" toast. Apply results and warnings are shown whatever this says.
	public static boolean showSuggestionsToast(ClientSettings settings, long important) {
		return settings.startupToast && important > 0;
	}
}
