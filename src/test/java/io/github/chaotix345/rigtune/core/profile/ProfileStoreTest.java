package io.github.chaotix345.rigtune.core.profile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.profile.ProfileStore.Profile;
import io.github.chaotix345.rigtune.core.profile.ProfileStore.Switch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md AC4.7 (profiles.json on the WS-K state-file helper).
class ProfileStoreTest {
	@TempDir
	Path configDir;

	private ProfileStore store() {
		return ProfileStore.shared(configDir);
	}

	private Path file() {
		return ProfileStore.file(configDir);
	}

	private static Profile profile(String id, String name, String source) {
		return new Profile(id, name, null, source, "2026-09-26T05:00:00Z", "0.4.0-dev+mc26.2", "26.2",
				Map.of("vanilla.renderDistance", "8", "vanilla.maxFps", "60"));
	}

	@Test
	void aMissingFileReadsAsDefaults() {
		assertEquals(List.of(), store().profiles());
		assertNull(store().active());
		assertEquals(new ProfileStore.Battery(true, null, null, false), store().battery());
		assertTrue(store().writable());
		assertFalse(Files.exists(file()));
	}

	@Test
	void profilesRoundTripAndTheBaselineIsUnique() {
		Profile mine = profile(ProfileStore.newProfileId(), "My settings", ProfileStore.SOURCE_BASELINE);
		assertTrue(store().saveProfile(mine));
		Profile again = profile(ProfileStore.newProfileId(), "My settings", ProfileStore.SOURCE_BASELINE);
		assertTrue(store().saveProfile(again));
		Profile saved = profile(ProfileStore.newProfileId(), "Evening\n=#", ProfileStore.SOURCE_SAVED);
		assertTrue(store().saveProfile(saved));
		assertEquals(2, store().profiles().size());
		assertEquals(again.id(), store().baseline().id());
		assertEquals("Evening", store().profile(saved.id()).name());
		assertEquals(saved.settings(), store().profile(saved.id()).settings());
		assertTrue(store().rename(saved.id(), "Late night"));
		assertEquals("Late night", store().profile(saved.id()).name());
		assertTrue(store().setActive(saved.id()));
		assertEquals(saved.id(), store().active());
		assertTrue(store().batteryOffered("2026-09-26T05:10:00Z"));
		assertTrue(store().rememberPrevious(saved.id()));
		assertEquals(saved.id(), store().battery().previousProfile());
		assertEquals("2026-09-26T05:10:00Z", store().battery().lastPromptAt());
		assertTrue(store().delete(saved.id()));
		assertNull(store().profile(saved.id()));
		assertNull(store().active());
		assertNull(store().battery().previousProfile());
		assertTrue(store().setActive("template:battery"));
		assertEquals("template:battery", store().active());
		assertFalse(store().saveProfile(profile("../evil", "x", ProfileStore.SOURCE_SAVED)));
	}

	@Test
	void aCorruptFileIsMovedAsideAndANewerOneIsNeverWritten() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{not json", StandardCharsets.UTF_8);
		assertEquals(List.of(), store().profiles());
		assertTrue(store().saveProfile(profile(ProfileStore.newProfileId(), "A", ProfileStore.SOURCE_SAVED)));
		assertTrue(Files.exists(file().resolveSibling("profiles.json.bad")));
		assertEquals(1, store().profiles().size());

		String newer = "{\"formatVersion\": 2, \"profiles\": [{\"id\": \"p-1\", \"name\": \"Future\", \"settings\": {\"vanilla.renderDistance\": \"9\"}}], "
				+ "\"switches\": [{\"entryId\": \"e1\", \"name\": \"Battery\"}]}";
		Files.writeString(file(), newer, StandardCharsets.UTF_8);
		byte[] before = Files.readAllBytes(file());
		assertFalse(store().writable());
		assertEquals("Future", store().profiles().getFirst().name());
		assertEquals(Map.of("e1", "Battery"), store().labels());
		assertFalse(store().saveProfile(profile(ProfileStore.newProfileId(), "B", ProfileStore.SOURCE_SAVED)));
		assertFalse(store().recordSwitch(new Switch("e2", null, "battery", "Battery"), null));
		assertFalse(store().setActive("template:battery"));
		assertArrayEquals(before, Files.readAllBytes(file()));

		Files.writeString(file(), "{\"formatVersion\": 2, \"battery\": 7}", StandardCharsets.UTF_8);
		assertEquals(List.of(), store().switches());
		assertTrue(store().battery().prompt());
	}

	@Test
	void unknownTopLevelAndPerProfileFieldsSurviveASave() throws IOException {
		String id = ProfileStore.newProfileId();
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"formatVersion\": 1, \"future\": {\"x\": 1}, \"profiles\": [{\"id\": \"" + id + "\", \"name\": \"Old\", "
				+ "\"source\": \"saved\", \"colour\": \"blue\", \"settings\": {\"vanilla.renderDistance\": \"9\", \"future.key\": \"7\"}}], "
				+ "\"battery\": {\"prompt\": false, \"autoSwitch\": true}}", StandardCharsets.UTF_8);
		assertEquals(Map.of("vanilla.renderDistance", "9"), store().profile(id).settings());
		assertFalse(store().battery().prompt());
		assertTrue(store().rename(id, "New"));
		assertTrue(store().saveProfile(new Profile(id, "Newer", null, ProfileStore.SOURCE_SAVED, null, null, null, Map.of("vanilla.maxFps", "60"))));
		assertTrue(store().snoozeBattery(true));
		JsonObject root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(1, root.getAsJsonObject("future").get("x").getAsInt());
		JsonObject saved = root.getAsJsonArray("profiles").get(0).getAsJsonObject();
		assertEquals("blue", saved.get("colour").getAsString());
		assertEquals("Newer", saved.get("name").getAsString());
		assertTrue(root.getAsJsonObject("battery").get("autoSwitch").getAsBoolean());
		assertTrue(root.getAsJsonObject("battery").get("snoozed").getAsBoolean());
		assertEquals(1, root.get("formatVersion").getAsInt());
	}

	@Test
	void capsAreEnforced() {
		for (int i = 0; i < ProfileStore.MAX_PROFILES; i++) {
			assertTrue(store().saveProfile(profile(ProfileStore.newProfileId(), "P" + i, ProfileStore.SOURCE_SAVED)));
		}
		assertFalse(store().saveProfile(profile(ProfileStore.newProfileId(), "One too many", ProfileStore.SOURCE_SAVED)));
		assertEquals(ProfileStore.MAX_PROFILES, store().profiles().size());
		Profile first = store().profiles().getFirst();
		assertTrue(store().saveProfile(new Profile(first.id(), "Replaced", null, first.source(), null, null, null, first.settings())));
		for (int i = 0; i < ProfileStore.MAX_SWITCHES + 5; i++) {
			assertTrue(store().recordSwitch(new Switch("e" + i, null, "battery", "Battery"), null));
		}
		List<Switch> switches = store().switches();
		assertEquals(ProfileStore.MAX_SWITCHES, switches.size());
		assertEquals("e5", switches.getFirst().entryId());
		assertEquals("e" + (ProfileStore.MAX_SWITCHES + 4), switches.getLast().entryId());
	}

	@Test
	void labelsForEntriesNoLongerInTheJournalArePruned() {
		assertTrue(store().recordSwitch(new Switch("gone", null, "battery", "Battery"), null));
		assertTrue(store().recordSwitch(new Switch("kept", "p-1", null, "Evening"), null));
		assertEquals(Map.of("gone", "Battery", "kept", "Evening"), store().labels());
		assertTrue(store().recordSwitch(new Switch("new", null, "max_fps", "Max FPS"), Set.of("kept", "new")));
		Map<String, String> expected = new LinkedHashMap<>();
		expected.put("kept", "Evening");
		expected.put("new", "Max FPS");
		assertEquals(expected, store().labels());
		assertTrue(store().prune(Set.of("new")));
		assertEquals(Map.of("new", "Max FPS"), store().labels());
		assertFalse(store().recordSwitch(new Switch("bad id\n", null, null, "x"), null));
	}
}
