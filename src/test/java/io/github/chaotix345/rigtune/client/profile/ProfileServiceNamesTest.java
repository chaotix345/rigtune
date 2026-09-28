package io.github.chaotix345.rigtune.client.profile;

import io.github.chaotix345.rigtune.core.profile.ProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (C16), review M1: the per-server offer's notice, lookup and screen read profiles.json once per call:
// names() answers every id (and the active profile) from that one read, whatever happens to the file after it.
class ProfileServiceNamesTest {
	@TempDir
	Path config;

	@Test
	void namesComeFromOneReadOfProfilesJson() throws IOException {
		String evening = ProfileStore.newProfileId();
		assertTrue(ProfileStore.shared(config).saveProfile(new ProfileStore.Profile(evening, "Evening", null, ProfileStore.SOURCE_SAVED,
				"2026-09-20T10:00:00Z", null, null, Map.of("vanilla.renderDistance", "12"))));
		ProfileService.Names names = new ProfileService(null, config).names();
		Files.delete(ProfileStore.file(config));
		assertEquals("Evening", names.name(evening).english(), "read before the file went");
		assertEquals("Max FPS", names.name("template:max_fps").english());
		assertNull(names.name("p-gone"), "a deleted profile");
		assertNull(names.name("template:future_mode"), "a template this version doesn't know");
		assertNull(names.active(), "nothing switched to");
		assertNull(new ProfileService(null, config).names().name(evening), "the next call reads the file again");
	}
}
