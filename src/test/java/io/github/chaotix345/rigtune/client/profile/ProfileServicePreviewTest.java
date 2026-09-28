package io.github.chaotix345.rigtune.client.profile;

import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2H L5: a profile's Preview (and an imported code's) is rebuilt from Apply's preview with the switch's
// own staged values (ProfileService.effective); it keeps whether every listed download's fabric.mod.json was checked, so
// the disclosure line shows exactly when it would for an ordinary Apply.
class ProfileServicePreviewTest {
	@TempDir
	Path config;

	private ApplyPreview effective(ApplyPreview preview) {
		return new ProfileService(null, config).effective(preview, List.<Recommendation>of());
	}

	@Test
	void aCheckedPreviewStaysChecked() {
		assertTrue(effective(new ApplyPreview(List.of(), List.of(), List.of(), List.of(), List.of(), true, List.of(), true)).downloadsChecked());
		assertFalse(effective(new ApplyPreview(List.of(), List.of(), List.of(), List.of(), List.of(), true, List.of(), false)).downloadsChecked());
	}
}
