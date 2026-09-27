package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The "written by 0.5" set src/test/resources/v050-written/ws-p/ (docs/v0.5/SPEC.md 3b, X11; the set's README), written
// by 0.5's own code: a Max FPS switch folded into History's baseline (L8: foldedEntryIds, its label kept), then Battery
// taken from the unplug offer while no profile was in effect any more (PF-1: battery.previousProfile = My settings),
// with "My settings" holding a DH LOD radius of 1024 (PF-5). By default the test compares with the committed files;
// RIGTUNE_REGENERATE_FIXTURES=1 writes them instead. The pinned 0.3.0 Journal reads the history (AC2H.3). Its entries
// change keys no v040-written history changes, later than theirs, so composed with those sets (compat030, the downgrade
// E2E) each set's Undo this still reverts its own changes.
class V050WrittenWsPTest {
	private static final String SET = "src/test/resources/v050-written/ws-p/";
	static final String BASELINE_ENTRY = Journal.BASELINE + "7d226e52-56e8-454c-9d4b-9a63a2731c20";
	static final String FOLDED_APPLY = "9f039d32-5456-4972-a851-eee90b25870d";
	static final String FOLDED_MAX_FPS = "ac70e7f0-6812-4bbb-b888-da253724da0b";
	static final String BATTERY_ENTRY = "8ee686e5-f831-4836-80a2-33551c9bbe31";
	static final String MY_SETTINGS = "p-1f1d0f06-4e25-4d0b-a6a3-febe72a9e3c5";
	private static final String DH_RADIUS = "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius";

	@TempDir
	Path dir;

	private static JournalChange change(String id, String key, String before, String after) {
		return new JournalChange(id, JournalChange.SETTING, key, before, after, null, null, null, null, JournalChange.APPLIED, null, null, null);
	}

	// The history as 0.5 leaves it: the baseline the cap folded the Apply and the Max FPS switch into, then the Battery switch.
	static List<JournalEntry> entries() {
		JournalEntry baseline = new JournalEntry(BASELINE_ENTRY, "2026-09-22T09:00:00Z", JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null, List.of(
				change("360052a0-a38c-4bc5-be83-726d580f4ac7", "vanilla.biomeBlendRadius", "2", "1"),
				change("73b9f3c9-e0bc-4de9-9b42-594e7631251f", "vanilla.particles", "0", "2"))).withFoldedEntryIds(List.of(FOLDED_APPLY, FOLDED_MAX_FPS));
		JournalEntry battery = new JournalEntry(BATTERY_ENTRY, "2026-09-23T10:00:00Z", JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null, List.of(
				change("c79c115a-d6ae-4a9c-afa8-f7973b343755", "vanilla.simulationDistance", "12", "8"),
				change("6385d72a-5a85-4134-a52a-342f0deceb1b", "vanilla.renderClouds", "true", "false"),
				change("e469bda9-a192-401a-887d-3db753112418", "vanilla.entityShadows", "true", "false")));
		return List.of(baseline, battery);
	}

	// history.json and profiles.json in configDir/rigtune/, in the order ProfileService writes them.
	static void write(Path configDir) throws IOException {
		Journal journal = new Journal(configDir, "0.5.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		ProfileStore store = ProfileStore.shared(configDir);
		assertTrue(store.saveProfile(new ProfileStore.Profile(MY_SETTINGS, "My settings", null, ProfileStore.SOURCE_BASELINE, "2026-09-22T09:29:59Z",
				"0.5.0+mc26.2", "26.2", ShareCodeTest.ordered("vanilla.simulationDistance", "12", "vanilla.particles", "0", "vanilla.biomeBlendRadius", "1",
						"vanilla.renderClouds", "true", "vanilla.entityShadows", "true", DH_RADIUS, "1024"))));
		assertTrue(store.recordSwitch(new ProfileStore.Switch(FOLDED_MAX_FPS, null, "max_fps", "Max FPS"), null));
		assertTrue(store.setActive("template:max_fps", FOLDED_MAX_FPS));
		assertTrue(journal.update(entries -> entries()));
		// The unplug offer, taken: Max FPS's entry is folded away, so no profile is in effect and PF-1 remembers My settings.
		String inEffect = ActiveProfile.inEffect(store.activeEntry(), journal.state(), journal.entries()) ? store.active() : null;
		assertNull(inEffect);
		assertTrue(store.batteryOffered("2026-09-23T09:59:00Z"));
		assertTrue(store.recordSwitch(new ProfileStore.Switch(BATTERY_ENTRY, null, "battery", "Battery"), Journal.idsWithFolded(journal.entries())));
		assertTrue(store.setActive(BatteryPrompt.BATTERY, BATTERY_ENTRY));
		assertTrue(store.rememberPrevious(BatteryPrompt.previousFor(inEffect, store.baseline().id())));
	}

	@Test
	void theCommittedSetIsWhatThe05CodeWrites() throws IOException {
		write(dir);
		Path committed = RepoFiles.resolve(SET);
		for (String name : List.of("history.json", "profiles.json")) {
			Path written = dir.resolve("rigtune").resolve(name);
			if ("1".equals(System.getenv("RIGTUNE_REGENERATE_FIXTURES"))) {
				Files.createDirectories(committed);
				Files.copy(written, committed.resolve(name), StandardCopyOption.REPLACE_EXISTING);
			}
			assertEquals(Files.readString(committed.resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n"),
					Files.readString(written, StandardCharsets.UTF_8).replace("\r\n", "\n"), name + ": regenerate with RIGTUNE_REGENERATE_FIXTURES=1");
		}
	}

	// 0.5 reads its own set back: the 1024 radius, the way back, both labels (the folded one on the baseline's row).
	@Test
	void the05CodeReadsTheSetBack() throws IOException {
		Path config = copyOfTheSet();
		ProfileStore store = ProfileStore.shared(config);
		assertEquals("1024", store.baseline().settings().get(DH_RADIUS));
		assertEquals(MY_SETTINGS, store.battery().previousProfile());
		assertEquals(BatteryPrompt.BATTERY, store.active());
		assertEquals(Map.of(FOLDED_MAX_FPS, "Max FPS", BATTERY_ENTRY, "Battery"), store.labels());
		Journal journal = new Journal(config, "0.5.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		HistoryModel.View view = HistoryModel.withProfiles(HistoryModel.build(journal.state(), journal.entries(), Map.of(), HistoryModel.Labels.RAW),
				store.labels());
		assertEquals(List.of("Max FPS"), view.entries().getLast().includes());
		assertEquals("Battery", view.entries().getFirst().profile());
		assertEquals(new BatteryPrompt.Decision(BatteryPrompt.Offer.PREVIOUS, MY_SETTINGS),
				BatteryPrompt.onEdge(false, store.battery(), store.active(), false, java.time.Instant.parse("2026-09-24T10:00:00Z")));
	}

	// The pinned 0.3.0 Journal and HistoryModel read the set's history: state OK, the same entries, the baseline an ordinary
	// Apply that Undo this is offered on.
	@Test
	void v030ReadsTheSetsHistory() throws IOException {
		Path config = copyOfTheSet();
		var old = new io.github.chaotix345.rigtune.v030.core.history.Journal(config, "0.3.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertEquals(io.github.chaotix345.rigtune.v030.core.history.Journal.State.OK, old.state());
		assertEquals(List.of(BASELINE_ENTRY, BATTERY_ENTRY), old.entries().stream().map(io.github.chaotix345.rigtune.v030.core.history.JournalEntry::id).toList());
		var view = io.github.chaotix345.rigtune.v030.core.history.HistoryModel.build(old.state(), old.entries(), Map.of(),
				io.github.chaotix345.rigtune.v030.core.history.HistoryModel.Labels.RAW);
		for (var entry : view.entries()) {
			assertEquals("rigtune.history.kind.apply", entry.kindKey(), entry.id());
			assertTrue(entry.undoable(), entry.id());
		}
		assertTrue(Files.readString(Journal.file(config), StandardCharsets.UTF_8).contains("\"foldedEntryIds\""), "the set carries the field");
	}

	private Path copyOfTheSet() throws IOException {
		Path config = dir.resolve("reread");
		Files.createDirectories(config.resolve("rigtune"));
		for (String name : List.of("history.json", "profiles.json")) {
			Files.copy(RepoFiles.resolve(SET + name), config.resolve("rigtune").resolve(name));
		}
		return config;
	}
}
