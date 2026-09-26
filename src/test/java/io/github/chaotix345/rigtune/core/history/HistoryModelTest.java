package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.history.ApplyFailures.Failure;
import io.github.chaotix345.rigtune.core.history.HistoryModel.Change;
import io.github.chaotix345.rigtune.core.history.HistoryModel.Entry;
import io.github.chaotix345.rigtune.core.history.HistoryModel.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md item 6, AC6.2: the History screen's model.
class HistoryModelTest {
	final List<JournalEntry> entries = new ArrayList<>();

	private JournalEntry add(String id, String kind, String undoOf, JournalChange... changes) {
		JournalEntry entry = new JournalEntry(id, "2026-09-2" + entries.size() + "T10:00:00Z", kind, "0.3.0+mc26.2", "26.2", undoOf, List.of(changes));
		entries.add(entry);
		return entry;
	}

	private HistoryModel.View view(Map<String, Failure> failures) {
		return HistoryModel.build(Journal.State.OK, entries, failures, HistoryModel.Labels.RAW);
	}

	private Entry entry(String id) {
		return view(Map.of()).entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
	}

	private static JournalChange setting(String key, String before, String after, String status) {
		return JournalChange.setting(key, before, after, status, null);
	}

	private static JournalChange file(String action, String modId, String file, String status, String group) {
		return JournalChange.file(action, modId, file, status, "op-" + file, group);
	}

	@Test
	void everyStatusHasALabelKey() {
		assertEquals("rigtune.history.status.applied", HistoryModel.statusKey(JournalChange.APPLIED));
		assertEquals("rigtune.history.status.staged", HistoryModel.statusKey(JournalChange.STAGED));
		assertEquals("rigtune.history.status.abandoned", HistoryModel.statusKey(JournalChange.ABANDONED));
		assertEquals("rigtune.history.status.discarded", HistoryModel.statusKey(JournalChange.DISCARDED));
		assertEquals("rigtune.history.status.reverted", HistoryModel.statusKey(JournalChange.REVERTED));
		assertEquals("rigtune.history.status.unknown", HistoryModel.statusKey("LATER"));
		assertEquals("rigtune.history.status.unknown", HistoryModel.statusKey(null));
	}

	@Test
	void everyKindHasALabelKey() {
		assertEquals("rigtune.history.kind.apply", HistoryModel.kindKey(JournalEntry.APPLY));
		assertEquals("rigtune.history.kind.benchmark", HistoryModel.kindKey(JournalEntry.BENCHMARK));
		assertEquals("rigtune.history.kind.undo", HistoryModel.kindKey(JournalEntry.UNDO));
		assertEquals("rigtune.history.kind.legacy_import", HistoryModel.kindKey(JournalEntry.LEGACY_IMPORT));
		assertEquals("rigtune.history.kind.unknown", HistoryModel.kindKey("preview"));
		assertEquals("rigtune.history.kind.unknown", HistoryModel.kindKey(null));
	}

	@Test
	void entriesAreNewestFirstAndCountSettingsAndMods() {
		add("e1", JournalEntry.APPLY, null,
				setting("vanilla.renderDistance", "16", "12", JournalChange.APPLIED),
				setting("vanilla.simulationDistance", "12", "8", JournalChange.APPLIED),
				setting("sodium.performance.use_entity_culling", "false", "true", JournalChange.STAGED),
				file(JournalChange.DISABLE, "sodium", "sodium-0.9.1.jar", JournalChange.STAGED, "g1"),
				file(JournalChange.ENABLE, "sodium", "sodium-0.9.2.jar", JournalChange.STAGED, "g1"),
				file(JournalChange.ENABLE, "lithium", "lithium-0.25.4.jar", JournalChange.STAGED, "g2"));
		add("b1", JournalEntry.BENCHMARK, null, setting("vanilla.renderDistance", "12", "14", JournalChange.APPLIED));

		HistoryModel.View view = view(Map.of());

		assertSame(Journal.State.OK, view.state());
		assertEquals(List.of("b1", "e1"), view.entries().stream().map(Entry::id).toList());
		Entry e1 = view.entries().get(1);
		assertEquals(3, e1.settings());
		assertEquals(2, e1.mods());
		assertEquals("rigtune.history.kind.apply", e1.kindKey());
		assertEquals("2026-09-20T10:00:00Z", e1.at());
		assertEquals("0.3.0+mc26.2", e1.rigtuneVersion());
		assertEquals("26.2", e1.mcVersion());
		assertEquals(1, view.entries().getFirst().settings());
		assertEquals(0, view.entries().getFirst().mods());
	}

	// Review B-L1: a disable + an enable of the same mod in one group is "Updated <mod>".
	@Test
	void anUpdateIsOneRow() {
		JournalChange off = file(JournalChange.DISABLE, "sodium", "sodium-0.9.1.jar", JournalChange.APPLIED, "g1");
		JournalChange on = file(JournalChange.ENABLE, "sodium", "sodium-0.9.2.jar", JournalChange.APPLIED, "g1");
		add("e1", JournalEntry.APPLY, null, off, on,
				file(JournalChange.DISABLE, "indium", "indium-1.0.jar", JournalChange.APPLIED, "g2"),
				file(JournalChange.ENABLE, "lithium", "lithium.jar", JournalChange.APPLIED, "g2"));

		List<Change> rows = entry("e1").changes();

		assertEquals(List.of(Row.UPDATED, Row.DISABLED, Row.ADDED), rows.stream().map(Change::row).toList());
		Change update = rows.getFirst();
		assertEquals(List.of(off.id(), on.id()), update.changeIds());
		assertEquals("sodium", update.modId());
		assertEquals("sodium-0.9.1.jar", update.file());
		assertEquals("sodium-0.9.2.jar", update.newFile());
		assertEquals("rigtune.history.status.applied", update.statusKey());
		assertEquals(3, entry("e1").mods(), "the update counts once");
	}

	@Test
	void anUndoReEnablesAndSaysWhatItUndid() {
		JournalChange added = file(JournalChange.ENABLE, "lithium", "lithium.jar", JournalChange.REVERTED, "g1");
		JournalChange removed = file(JournalChange.DISABLE, "indium", "indium.jar", JournalChange.REVERTED, "g2");
		add("e1", JournalEntry.APPLY, null, added, removed);
		add("u1", JournalEntry.UNDO, "e1",
				file(JournalChange.DISABLE, "lithium", "lithium.jar", JournalChange.APPLIED, "g3").reverting(added.id()),
				file(JournalChange.ENABLE, "indium", "indium.jar", JournalChange.APPLIED, "g3").reverting(removed.id()));
		add("u2", JournalEntry.UNDO, UndoPlanner.ALL);

		Entry undo = entry("u1");

		assertEquals(List.of(Row.DISABLED, Row.REENABLED), undo.changes().stream().map(Change::row).toList());
		assertEquals("e1", undo.undoOf());
		assertEquals("2026-09-20T10:00:00Z", undo.undoOfAt());
		assertFalse(undo.undoable());
		assertEquals(UndoPlanner.ALL, entry("u2").undoOf());
		assertNull(entry("u2").undoOfAt());
		assertEquals(List.of(Row.ADDED, Row.DISABLED), entry("e1").changes().stream().map(Change::row).toList());
		assertEquals("rigtune.history.status.reverted", entry("e1").changes().getFirst().statusKey());
	}

	// docs/v0.3/SPEC.md 3e, review B-M1: a staged change whose op failed at the last exit, and an abandoned one.
	@Test
	void failedAndAbandonedChangesCarryTheHelpersReason() {
		JournalChange failing = file(JournalChange.ENABLE, "dh", "dh.jar", JournalChange.STAGED, "g1");
		JournalChange dropped = file(JournalChange.ENABLE, "x", "x.jar", JournalChange.ABANDONED, "g2");
		JournalChange done = file(JournalChange.ENABLE, "y", "y.jar", JournalChange.APPLIED, "g3");
		add("e1", JournalEntry.APPLY, null, failing, dropped, done);
		Failure busy = new Failure(failing.opId(), Status.FAILED, PendingActions.Type.ENABLE_FILE, "dh", "dh.jar", "busy", 2);
		Failure gaveUp = new Failure(dropped.opId(), Status.ABANDONED, PendingActions.Type.ENABLE_FILE, "x", "x.jar", "gave up", 3);
		Failure stale = new Failure(done.opId(), Status.FAILED, PendingActions.Type.ENABLE_FILE, "y", "y.jar", "old", 1);

		List<Change> rows = view(Map.of(busy.opId(), busy, gaveUp.opId(), gaveUp, stale.opId(), stale)).entries().getFirst().changes();

		assertSame(busy, rows.get(0).failure());
		assertSame(gaveUp, rows.get(1).failure());
		assertNull(rows.get(2).failure());
	}

	// The user's real 0.1.0 failure: the disable of an update hit a sharing violation, so its enable wasn't applied.
	@Test
	void anUpdateShowsTheReasonOfItsDisable() {
		JournalChange off = file(JournalChange.DISABLE, "dh", "dh-1.jar", JournalChange.STAGED, "g1");
		JournalChange on = file(JournalChange.ENABLE, "dh", "dh-2.jar", JournalChange.STAGED, "g1");
		add("e1", JournalEntry.APPLY, null, off, on);
		Failure busy = new Failure(off.opId(), Status.FAILED, PendingActions.Type.DISABLE_FILE, null, "dh-1.jar", "in use", 1);
		Failure because = new Failure(on.opId(), Status.FAILED, PendingActions.Type.ENABLE_FILE, "dh", "dh-2.jar", "Not applied because disabling dh-1.jar failed", 1);

		Change row = view(Map.of(busy.opId(), busy, because.opId(), because)).entries().getFirst().changes().getFirst();

		assertEquals(Row.UPDATED, row.row());
		assertSame(busy, row.failure());
		assertSame(because, view(Map.of(because.opId(), because)).entries().getFirst().changes().getFirst().failure());
	}

	// Review B-L1: Undo this is offered while something is left to undo, never on an undo entry.
	@Test
	void undoableFollowsThePlanner() {
		JournalChange rd = setting("vanilla.renderDistance", "12", "16", JournalChange.APPLIED);
		add("done", JournalEntry.APPLY, null, rd);
		add("u1", JournalEntry.UNDO, "done", setting("vanilla.renderDistance", "16", "12", JournalChange.APPLIED).reverting(rd.id()));
		add("open", JournalEntry.BENCHMARK, null, setting("vanilla.renderDistance", "12", "14", JournalChange.APPLIED));
		add("legacy", JournalEntry.LEGACY_IMPORT, null, file(JournalChange.ENABLE, "x", "x.jar", JournalChange.APPLIED, "g"));

		assertFalse(entry("done").undoable());
		assertFalse(entry("u1").undoable());
		assertTrue(entry("open").undoable());
		assertTrue(entry("legacy").undoable());
	}

	@Test
	void settingsUseTheLabels() {
		add("e1", JournalEntry.APPLY, null, setting("vanilla.renderDistance", null, "12", JournalChange.APPLIED));
		HistoryModel.Labels labels = new HistoryModel.Labels() {
			@Override
			public String label(String key) {
				return key.toUpperCase(Locale.ROOT);
			}

			@Override
			public String value(String key, String value) {
				return value + " chunks";
			}
		};

		Change row = HistoryModel.build(Journal.State.OK, entries, Map.of(), labels).entries().getFirst().changes().getFirst();

		assertEquals(Row.SETTING, row.row());
		assertEquals("VANILLA.RENDERDISTANCE", row.label());
		assertNull(row.before());
		assertEquals("12 chunks", row.after());
		assertEquals("renderDistance", HistoryModel.Labels.RAW.label("vanilla.renderDistance"));
	}

	@Test
	void aHistoryThatCantBeShownHasNoEntries() {
		HistoryModel.View view = HistoryModel.build(Journal.State.CORRUPT, List.of(), Map.of(), HistoryModel.Labels.RAW);

		assertSame(Journal.State.CORRUPT, view.state());
		assertTrue(view.entries().isEmpty());
	}

	// docs/v0.4/SPEC.md 2c: a file change shows the mod's name when staging recorded it, else the file name; an update
	// takes the new jar's name. A name edited into history.json by hand is sanitised like one read from a jar (P-L1).
	@Test
	void fileRowsPreferTheModName() {
		add("e1", JournalEntry.APPLY, null,
				file(JournalChange.DISABLE, "sodium", "sodium-0.7.0.jar", JournalChange.APPLIED, "g").withModName("Sodium (old)"),
				file(JournalChange.ENABLE, "sodium", "sodium-0.7.1.jar", JournalChange.APPLIED, "g").withModName("Sodium"),
				file(JournalChange.ENABLE, "iris", "iris.jar", JournalChange.APPLIED, null).withModName("\u00a7aIris\u202e"),
				file(JournalChange.DISABLE, "dh", "dh.jar", JournalChange.APPLIED, null));

		List<Change> changes = entry("e1").changes();

		assertEquals(Row.UPDATED, changes.get(0).row());
		assertEquals("Sodium", changes.get(0).name());
		assertEquals("Sodium", changes.get(0).shownName());
		assertEquals("Iris", changes.get(1).shownName());
		assertNull(changes.get(2).name());
		assertEquals("dh.jar", changes.get(2).shownName());
		assertEquals("dh.jar", changes.get(2).file());
	}

	// docs/v0.4/SPEC.md 2a (AC2a.1): Undo last / Undo all are offered only when some entry has something to undo.
	@Test
	void anyUndoableIsFalseForAnEmptyOrFullyUndoneHistory() {
		assertFalse(HistoryModel.anyUndoable(view(Map.of())));
		assertFalse(HistoryModel.anyUndoable(null));
		add("apply", JournalEntry.APPLY, null, setting("vanilla.renderDistance", "12", "8", JournalChange.APPLIED));
		assertTrue(HistoryModel.anyUndoable(view(Map.of())));
		add("undo", JournalEntry.UNDO, "apply", setting("vanilla.renderDistance", "8", "12", JournalChange.APPLIED)
				.reverting(entries.getFirst().changes().getFirst().id()));
		assertFalse(HistoryModel.anyUndoable(view(Map.of())));
	}
}
