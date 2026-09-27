package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4e, AC4e.1 (unit): the Settings "Mod files" row shows exactly under LAUNCHER or PENDING, or once the
// opt-in is on (then the policy reads RIGTUNE), never for a plain RIGTUNE instance.
class ModFilesRowTest {
	@Test
	void theRowShowsExactlyUnderLauncherPendingOrTheOptIn() {
		assertTrue(RigTuneSettingsScreen.showModFilesRow(ModFilesPolicy.LAUNCHER, false));
		assertTrue(RigTuneSettingsScreen.showModFilesRow(ModFilesPolicy.PENDING, false));
		assertTrue(RigTuneSettingsScreen.showModFilesRow(ModFilesPolicy.RIGTUNE, true));
		assertFalse(RigTuneSettingsScreen.showModFilesRow(ModFilesPolicy.RIGTUNE, false));
	}
}
