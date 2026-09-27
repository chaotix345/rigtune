package io.github.chaotix345.rigtune.core.launcher;

import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 4b: the one source of launcher wording for other features (C02's guide joins it). Contracts stub
// (WS-K): null everywhere until WS-L1 fills it in (the LAUNCHER sentence, the opted-in sentence; null for plain RIGTUNE
// and for PENDING, AC4b.5).
public final class LauncherModText {
	private LauncherModText() {
	}

	// optedIn: settings.json modFilesByRigTune.
	public static @Nullable Text guideLine(ModFilesPolicy policy, @Nullable LauncherInfo launcher, boolean optedIn) {
		return null;
	}
}
