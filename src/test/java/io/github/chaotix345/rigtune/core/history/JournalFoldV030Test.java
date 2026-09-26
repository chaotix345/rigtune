package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.v030.core.history.UndoPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 2o M6 and the compatibility promise: the baseline entry the cap folds is an ordinary Apply to the
// pinned 0.3.0 Journal, HistoryModel and UndoPlanner (src/test/java/.../v030/, verbatim v0.3.0), and a 0.3.0 rewrite of
// history.json keeps it usable by 0.4.
class JournalFoldV030Test {
	private static final String RD = "vanilla.renderDistance";
	private static final String FPS = "vanilla.maxFps";
	private static final String VSYNC = "vanilla.enableVsync";
	private static final String DEFER = "sodium.performance.chunk_build_defer_mode";
	private static final Map<String, String> NOW = Map.of(RD, "8", FPS, "260", VSYNC, "false", DEFER, "ALWAYS");

	@TempDir
	Path dir;

	private static JournalChange set(String key, String before, String after) {
		return JournalChange.setting(key, before, after, JournalChange.APPLIED, null);
	}

	private Journal writeFolded() throws IOException {
		return writeFolded(null);
	}

	// The first Apply and 60 profile switches, written by the 0.4 journal one entry at a time. lastOpId: when set, the
	// last switch's Sodium change is still staged with this op.
	private Journal writeFolded(String lastOpId) throws IOException {
		Journal journal = new Journal(dir, "0.4.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		Instant start = Instant.parse("2026-09-01T10:00:00Z");
		List<JournalEntry> written = new ArrayList<>();
		written.add(new JournalEntry("first", start.toString(), JournalEntry.APPLY, "0.4.0+mc26.2", "26.2", null, List.of(set(RD, "12", "8"),
				set(FPS, "120", "260"), set(DEFER, "ONE_FRAME", "ALWAYS"),
				JournalChange.file(JournalChange.ENABLE, "x", "x.jar", JournalChange.APPLIED, "op-x", "g-x").withModName("X Mod"))));
		for (int i = 0; i < 60; i++) {
			String at = start.plusSeconds(60L * (i + 1)).toString();
			written.add(new JournalEntry("switch-" + i, at, JournalEntry.APPLY, "0.4.0+mc26.2", "26.2", null, i % 2 == 0
					? List.of(set(RD, "8", "6"), set(FPS, "260", "60"), set(VSYNC, "false", "true"), set(DEFER, "ALWAYS", "ZERO_FRAMES"))
					: List.of(set(RD, "6", "8"), set(FPS, "60", "260"), set(VSYNC, "true", "false"), i == 59 && lastOpId != null
							? JournalChange.setting(DEFER, "ZERO_FRAMES", "ALWAYS", JournalChange.STAGED, lastOpId) : set(DEFER, "ZERO_FRAMES", "ALWAYS"))));
		}
		for (JournalEntry entry : written) {
			assertTrue(journal.update(entries -> {
				List<JournalEntry> out = new ArrayList<>(entries);
				out.add(entry);
				return out;
			}));
		}
		assertFalse(journal.entries().stream().anyMatch(e -> e.id().equals("first")));
		return journal;
	}

	private static String baselineId(List<JournalEntry> entries) {
		return entries.stream().filter(e -> !e.id().startsWith("switch-")).map(JournalEntry::id).findFirst().orElseThrow();
	}

	@Test
	void v030ReadsListsAndUndoesTheBaseline() throws IOException {
		List<JournalEntry> ours = writeFolded().entries();
		String baseline = baselineId(ours);

		var old = journal030();
		assertEquals(io.github.chaotix345.rigtune.v030.core.history.Journal.State.OK, old.state());
		assertFalse(old.readOnly());
		assertEquals(ours.stream().map(JournalEntry::id).toList(), old.entries().stream().map(io.github.chaotix345.rigtune.v030.core.history.JournalEntry::id).toList());
		var view = io.github.chaotix345.rigtune.v030.core.history.HistoryModel.build(old.state(), old.entries(), Map.of(),
				io.github.chaotix345.rigtune.v030.core.history.HistoryModel.Labels.RAW);
		assertEquals(ours.size(), view.entries().size());
		var listed = view.entries().stream().filter(e -> e.id().equals(baseline)).findFirst().orElseThrow();
		assertEquals("apply", listed.kind());
		assertEquals("rigtune.history.kind.apply", listed.kindKey(), "0.3.0 shows the baseline as an Apply");
		assertTrue(listed.undoable());

		var state = state030();
		var all = io.github.chaotix345.rigtune.v030.core.history.UndoPlanner.plan(old.entries(), List.of(), state, true);
		assertNull(all.plan().problem());
		assertEquals(Map.of(RD, "12", FPS, "120", VSYNC, "false"), all.script().immediate());
		assertEquals(Map.of(DEFER, "ONE_FRAME"), all.script().staged());
		assertEquals(List.of(dir.resolve("mods").resolve("x.jar").toString()), all.script().fileOps().stream().map(op -> op.path()).toList());

		// Undo this on the baseline: the mod it added goes; the settings later switches changed again are skipped.
		var entry = io.github.chaotix345.rigtune.v030.core.history.UndoPlanner.planEntry(old.entries(), List.of(), state, baseline);
		assertNull(entry.plan().problem());
		assertEquals(baseline, entry.plan().undoOf());
		assertEquals(1, entry.script().fileOps().size());
		assertTrue(entry.script().immediate().isEmpty(), entry.script().immediate().toString());
		assertTrue(entry.plan().items().stream().anyMatch(i -> i.action() == UndoPlan.Action.SKIP));
	}

	// After a downgrade, 0.3.0's helper applies the op 0.4 left staged and rewrites history.json (dropping modName). The
	// baseline stays, and 0.4 plans Undo all back to the values from before the first Apply. (0.3.0's own cap drops the
	// baseline at its first eviction, as it always dropped the oldest entry: see docs/v0.4/design/ws-g4.md.)
	@Test
	void aV030RewriteKeepsTheFoldUsable() throws IOException {
		var op = io.github.chaotix345.rigtune.v030.core.apply.PendingActions.Op.patchJson(dir.resolve("sodium-options.json"),
				Map.of("performance.chunk_build_defer_mode", "ALWAYS"));
		Journal ours = writeFolded(op.id());
		String baseline = baselineId(ours.entries());
		List<JournalChange> before = ours.entries().getFirst().changes();

		assertTrue(journal030().updateExisting(entries -> io.github.chaotix345.rigtune.v030.core.history.HistoryUpdates.applyResults(entries,
				List.of(new io.github.chaotix345.rigtune.v030.core.apply.ApplyResult.OpResult(op,
						io.github.chaotix345.rigtune.v030.core.apply.ApplyResult.Status.OK, "Patched 1 value(s) in sodium-options.json")))));

		List<JournalEntry> reread = ours.entries();
		assertEquals(Journal.State.OK, ours.state());
		assertEquals(Journal.MAX_ENTRIES, reread.size());
		assertEquals(baseline, reread.getFirst().id());
		assertEquals(before.stream().map(c -> c.withModName(null)).toList(), reread.getFirst().changes());
		assertTrue(reread.getLast().changes().stream().allMatch(c -> JournalChange.APPLIED.equals(c.status())), reread.getLast().toString());

		UndoPlannerTest.FakeState state = new UndoPlannerTest.FakeState();
		state.settings.putAll(NOW);
		state.jar("x.jar", "x");
		UndoPlanner.Result all = UndoPlanner.plan(reread, List.of(), state, true);
		assertNull(all.plan().problem());
		assertEquals(Map.of(RD, "12", FPS, "120", VSYNC, "false"), all.script().immediate());
		assertEquals(Map.of(DEFER, "ONE_FRAME"), all.script().staged());
		assertEquals(1, all.script().fileOps().size());
	}

	private io.github.chaotix345.rigtune.v030.core.history.Journal journal030() {
		return new io.github.chaotix345.rigtune.v030.core.history.Journal(dir, "0.3.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	private io.github.chaotix345.rigtune.v030.core.history.UndoPlanner.State state030() {
		Path mods = dir.resolve("mods");
		return new io.github.chaotix345.rigtune.v030.core.history.UndoPlanner.State() {
			@Override
			public String setting(String key) {
				return NOW.get(key);
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
			public io.github.chaotix345.rigtune.v030.core.history.UndoPlanner.Folder folder() {
				return new io.github.chaotix345.rigtune.v030.core.history.UndoPlanner.Folder() {
					@Override
					public Path dir() {
						return mods;
					}

					@Override
					public Set<String> files() {
						return Set.of("x.jar");
					}

					@Override
					public io.github.chaotix345.rigtune.v030.core.history.JarInfo jar(String fileName) {
						return fileName.equals("x.jar") ? new io.github.chaotix345.rigtune.v030.core.history.JarInfo("x", Set.of(), Set.of()) : null;
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
