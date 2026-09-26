package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.v030.core.history.JarInfo;
import io.github.chaotix345.rigtune.v030.core.history.UndoPlan;
import io.github.chaotix345.rigtune.v030.core.history.UndoPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// docs/v0.4/SPEC.md AC4.12: a history.json written after profile switches, read by the pinned 0.3.0 Journal, HistoryModel
// and UndoPlanner (src/test/java/.../v030/, verbatim v0.3.0); a 0.3.0 rewrite keeps the entry ids, so 0.4's labels in
// profiles.json come back. Also writes the "written by 0.4" fixture set src/test/resources/v040-written/ws-p/ (plan review
// H-M1): a Battery switch applied at a restart, labelled in profiles.json, with the entry, change and profile ids of WS-H's
// placeholder set (tools/e2e's tests and the released-jar harness name them).
class V030CompatTest {
	static final String BATTERY_ENTRY = "0e5a4b01-7c2d-4e8f-9a10-b0a77e2b0001";
	static final String MAX_FPS_ENTRY = "0e5a4b01-7c2d-4e8f-9a10-b0a77e2b0002";
	static final String BASELINE_ID = "p-0e5a4b01-7c2d-4e8f-9a10-b0a77e2b00b1";
	// The fixture set's ids (WS-H's placeholder ws-p set: src/test/resources/v040-written/placeholder/ on test/e2e-v04).
	static final String FIXTURE_ENTRY = "c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17";
	static final String FIXTURE_BASELINE = "p-2b6d9e41-0c3f-4a15-b8e2-6f1d7c9a3e20";
	private static final String FIXTURE = "/v040-written/ws-p/";

	@TempDir
	Path dir;

	// The switch entries as 0.4 journals them: kind apply, vanilla changes applied now, Sodium changes staged and then
	// applied by the helper at the restart (their op ids kept).
	static List<JournalEntry> switches() {
		JournalEntry battery = new JournalEntry(BATTERY_ENTRY, "2026-09-21T10:00:00Z", JournalEntry.APPLY, "0.4.0+mc26.2", "26.2", null, List.of(
				change("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b1001", "vanilla.renderDistance", "12", "8", null),
				change("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b1002", "vanilla.maxFps", "170", "60", null),
				change("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b1003", "vanilla.enableVsync", "false", "true", null),
				change("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b1004", "sodium.performance.chunk_build_defer_mode", "ONE_FRAME", "ALWAYS",
						"0e5a4b01-7c2d-4e8f-9a10-b0a77e2b2001")));
		JournalEntry maxFps = new JournalEntry(MAX_FPS_ENTRY, "2026-09-21T10:05:00Z", JournalEntry.APPLY, "0.4.0+mc26.2", "26.2", null, List.of(
				change("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b1011", "vanilla.maxFps", "60", "260", null),
				change("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b1012", "vanilla.enableVsync", "true", "false", null),
				change("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b1013", "sodium.performance.chunk_build_defer_mode", "ALWAYS", "ONE_FRAME",
						"0e5a4b01-7c2d-4e8f-9a10-b0a77e2b2002")));
		return List.of(battery, maxFps);
	}

	private static JournalChange change(String id, String key, String before, String after, String opId) {
		return new JournalChange(id, JournalChange.SETTING, key, before, after, null, null, null, null, JournalChange.APPLIED, opId, null, null);
	}

	// Writes history.json and profiles.json into configDir/rigtune/ with the 0.4 code, as the switches above leave them.
	static void write(Path configDir) throws IOException {
		Journal journal = new Journal(configDir, "0.4.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertTrue(journal.update(entries -> switches()));
		ProfileStore store = ProfileStore.shared(configDir);
		assertTrue(store.saveProfile(new ProfileStore.Profile(BASELINE_ID, "My settings", null, ProfileStore.SOURCE_BASELINE, "2026-09-21T09:59:59Z",
				"0.4.0+mc26.2", "26.2", ShareCodeTest.ordered("vanilla.renderDistance", "12", "vanilla.maxFps", "170", "vanilla.enableVsync", "false",
						"sodium.performance.chunk_build_defer_mode", "ONE_FRAME"))));
		assertTrue(store.recordSwitch(new ProfileStore.Switch(BATTERY_ENTRY, null, "battery", "Battery"), null));
		assertTrue(store.rememberPrevious(BASELINE_ID));
		assertTrue(store.batteryOffered("2026-09-21T09:59:00Z"));
		assertTrue(store.recordSwitch(new ProfileStore.Switch(MAX_FPS_ENTRY, null, "max_fps", "Max FPS"), null));
		assertTrue(store.setActive("template:max_fps"));
	}

	// The fixture set: what 0.4 leaves after a Battery switch and a restart (its staged change applied by the helper).
	static void writeFixture(Path configDir) throws IOException {
		Journal journal = new Journal(configDir, "0.4.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertTrue(journal.update(entries -> List.of(new JournalEntry(FIXTURE_ENTRY, "2026-09-21T10:00:00Z", JournalEntry.APPLY, "0.4.0+mc26.2", "26.2",
				null, List.of(change("d4f8b207-9e1c-4f30-8c4d-806f7e45ad18", "vanilla.renderDistance", "12", "8", null),
						change("e5a9c318-af2d-4041-9d5e-917a8f56be19", "vanilla.maxFps", "120", "60", null),
						change("f6b0d429-a03e-4152-8e6f-a28b9067cf1a", "sodium.performance.chunk_builder_threads", "0", "2", null))))));
		ProfileStore store = ProfileStore.shared(configDir);
		assertTrue(store.saveProfile(new ProfileStore.Profile(FIXTURE_BASELINE, "My settings", null, ProfileStore.SOURCE_BASELINE, "2026-09-21T09:59:59Z",
				"0.4.0+mc26.2", "26.2", ShareCodeTest.ordered("vanilla.renderDistance", "12", "vanilla.maxFps", "120",
						"sodium.performance.chunk_builder_threads", "0"))));
		assertTrue(store.batteryOffered("2026-09-21T09:59:00Z"));
		assertTrue(store.rememberPrevious(FIXTURE_BASELINE));
		assertTrue(store.recordSwitch(new ProfileStore.Switch(FIXTURE_ENTRY, null, "battery", "Battery"), null));
		assertTrue(store.setActive("template:battery", FIXTURE_ENTRY));
	}

	@Test
	void theCommittedFixtureIsWhatThe04CodeWrites() throws IOException {
		writeFixture(dir);
		List<String> stale = new ArrayList<>();
		for (String name : List.of("history.json", "profiles.json")) {
			String written = Files.readString(dir.resolve("rigtune").resolve(name), StandardCharsets.UTF_8).replace("\r\n", "\n");
			String committed;
			try (InputStream in = V030CompatTest.class.getResourceAsStream(FIXTURE + name)) {
				committed = in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
			}
			if (!written.equals(committed)) {
				Path out = Path.of("v040-written-ws-p-" + name).toAbsolutePath();
				Files.writeString(out, written, StandardCharsets.UTF_8);
				stale.add("src/test/resources" + FIXTURE + name + " differs from what the 0.4 code writes; the fresh copy is " + out);
			}
		}
		if (!stale.isEmpty()) {
			fail(String.join(System.lineSeparator(), stale));
		}
	}

	@Test
	void v030ReadsAHistoryWrittenAfterProfileSwitches() throws IOException {
		write(dir);
		var old = journal030();
		assertEquals(io.github.chaotix345.rigtune.v030.core.history.Journal.State.OK, old.state());
		assertFalse(old.readOnly());
		assertEquals(List.of(BATTERY_ENTRY, MAX_FPS_ENTRY), old.entries().stream().map(io.github.chaotix345.rigtune.v030.core.history.JournalEntry::id).toList());
		var view = io.github.chaotix345.rigtune.v030.core.history.HistoryModel.build(old.state(), old.entries(), Map.of(),
				io.github.chaotix345.rigtune.v030.core.history.HistoryModel.Labels.RAW);
		assertEquals(2, view.entries().size());
		for (var entry : view.entries()) {
			assertEquals("apply", entry.kind());
			assertEquals("rigtune.history.kind.apply", entry.kindKey(), "0.3.0 shows a switch as an Apply");
			assertTrue(entry.undoable(), entry.id());
		}
	}

	@Test
	void v030PlansUndoThisAndUndoLastOnSwitchEntries() throws IOException {
		write(dir);
		var old = journal030();
		Map<String, String> now = new HashMap<>(Map.of("vanilla.renderDistance", "8", "vanilla.maxFps", "260", "vanilla.enableVsync", "false",
				"sodium.performance.chunk_build_defer_mode", "ONE_FRAME"));
		UndoPlanner.State state = state(now);
		var last = UndoPlanner.plan(old.entries(), List.of(), state, false);
		assertNull(last.plan().problem());
		assertEquals(MAX_FPS_ENTRY, last.plan().undoOf());
		assertTrue(last.plan().items().stream().allMatch(i -> i.action() == UndoPlan.Action.REVERT), last.plan().items().toString());
		assertEquals(Map.of("vanilla.maxFps", "60", "vanilla.enableVsync", "true"), last.script().immediate());
		assertEquals(Map.of("sodium.performance.chunk_build_defer_mode", "ALWAYS"), last.script().staged());

		// Undo this on the older (Battery) switch: render distance reverts; the keys Max FPS changed again are skipped.
		var battery = UndoPlanner.planEntry(old.entries(), List.of(), state, BATTERY_ENTRY);
		assertNull(battery.plan().problem());
		assertEquals(Map.of("vanilla.renderDistance", "12"), battery.script().immediate());
		assertTrue(battery.plan().items().stream().anyMatch(i -> i.action() == UndoPlan.Action.SKIP));
	}

	@Test
	void aV030RewriteKeepsTheEntryIdsSoTheLabelsComeBack() throws IOException {
		write(dir);
		var old = journal030();
		// 0.3.0 journals an Apply of its own: it rewrites history.json (dropping any field it doesn't know).
		assertTrue(old.update(entries -> {
			List<io.github.chaotix345.rigtune.v030.core.history.JournalEntry> out = new ArrayList<>(entries);
			out.add(new io.github.chaotix345.rigtune.v030.core.history.JournalEntry("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b0003", "2026-09-22T08:00:00Z",
					"apply", "0.3.0+mc26.2", "26.2", null, List.of(new io.github.chaotix345.rigtune.v030.core.history.JournalChange(
							"0e5a4b01-7c2d-4e8f-9a10-b0a77e2b1021", "setting", "vanilla.particles", "0", "1", null, null, null, null, "APPLIED", null, null,
							null))));
			return out;
		}));
		Journal journal = new Journal(dir, "0.4.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertEquals(Journal.State.OK, journal.state());
		assertEquals(List.of(BATTERY_ENTRY, MAX_FPS_ENTRY, "0e5a4b01-7c2d-4e8f-9a10-b0a77e2b0003"), journal.entries().stream().map(JournalEntry::id).toList());
		HistoryModel.View view = HistoryModel.withProfiles(HistoryModel.build(journal.state(), journal.entries(), Map.of(), HistoryModel.Labels.RAW),
				ProfileStore.shared(dir).labels());
		Map<String, String> labels = new HashMap<>();
		view.entries().forEach(e -> labels.put(e.id(), e.profile()));
		assertEquals("Battery", labels.get(BATTERY_ENTRY));
		assertEquals("Max FPS", labels.get(MAX_FPS_ENTRY));
		assertNull(labels.get("0e5a4b01-7c2d-4e8f-9a10-b0a77e2b0003"));
		// Pruning against the rewritten journal keeps both labels.
		Set<String> ids = Set.copyOf(journal.entries().stream().map(JournalEntry::id).toList());
		assertTrue(ProfileStore.shared(dir).prune(ids));
		assertEquals(Map.of(BATTERY_ENTRY, "Battery", MAX_FPS_ENTRY, "Max FPS"), ProfileStore.shared(dir).labels());
	}

	@Test
	void v030ParsesPendingOpsAProfileSwitchStages() throws IOException {
		Path mods = dir.resolve("mods");
		PendingActions.Op patch = PendingActions.Op.patchJson(dir.resolve("sodium-options.json"), Map.of("performance.chunk_build_defer_mode", "ALWAYS"));
		PendingActions.Op toml = PendingActions.Op.patchToml(dir.resolve("DistantHorizons.toml"), Map.of("client.advanced.debugging.rendererMode", "DISABLED"));
		Path file = dir.resolve("pending.json");
		PendingActions.create(7, mods, dir, List.of(patch, toml)).save(file);
		var old = io.github.chaotix345.rigtune.v030.core.apply.PendingActions.load(file);
		assertEquals(List.of(patch.id(), toml.id()), old.ops().stream().map(io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op::id).toList());
		assertEquals(Map.of("client.advanced.debugging.rendererMode", "DISABLED"), old.ops().get(1).patches());
	}

	private io.github.chaotix345.rigtune.v030.core.history.Journal journal030() {
		return new io.github.chaotix345.rigtune.v030.core.history.Journal(dir, "0.3.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	private UndoPlanner.State state(Map<String, String> now) throws IOException {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		return new UndoPlanner.State() {
			@Override
			public String setting(String key) {
				return now.get(key);
			}

			@Override
			public boolean immediate(String key) {
				return key.startsWith("vanilla.");
			}

			@Override
			public boolean changeable(String key) {
				return true;
			}

			@Override
			public UndoPlanner.Folder folder() {
				return new UndoPlanner.Folder() {
					@Override
					public Path dir() {
						return mods;
					}

					@Override
					public Set<String> files() {
						return Set.of();
					}

					@Override
					public JarInfo jar(String fileName) {
						return null;
					}

					@Override
					public Set<String> providedElsewhere() {
						return Set.of();
					}
				};
			}
		};
	}
}
