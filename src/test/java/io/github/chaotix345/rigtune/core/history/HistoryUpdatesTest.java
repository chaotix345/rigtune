package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HistoryUpdatesTest {
	private static final Path MODS = Path.of("game", "mods");

	private static JournalEntry entry(String id, String kind, JournalChange... changes) {
		return new JournalEntry(id, "2026-09-25T10:00:00Z", kind, "0.2.0", "26.2", null, List.of(changes));
	}

	private static Op withId(Op op, String id, String group) {
		return new Op(op.type(), op.from(), op.to(), op.path(), op.patches(), id, group, op.modId(), op.attempts());
	}

	private static JournalChange change(List<JournalEntry> entries, int entry, int change) {
		return entries.get(entry).changes().get(change);
	}

	@Test
	void okAndAlreadyDoneBecomeAppliedAndFailedStaysStaged() {
		List<JournalEntry> entries = List.of(entry("e1", JournalEntry.APPLY,
				JournalChange.file(JournalChange.ENABLE, "lithium", "lithium.jar", JournalChange.STAGED, "op1", "g1"),
				JournalChange.setting("sodium.a", "0", "4", JournalChange.STAGED, "op2"),
				JournalChange.setting("sodium.b", "0", "4", JournalChange.STAGED, "op3")));
		List<OpResult> results = List.of(
				new OpResult(withId(Op.enableFile(MODS.resolve("lithium.jar.rigtune-pending"), MODS.resolve("lithium.jar")), "op1", "g1"), Status.OK, "Enabled"),
				new OpResult(withId(Op.patchJson(Path.of("s.json"), Map.of("a", "4")), "op2", null), Status.SKIPPED_ALREADY_DONE, "already"),
				new OpResult(withId(Op.patchJson(Path.of("s.json"), Map.of("b", "4")), "op3", null), Status.FAILED, "busy"));

		List<JournalEntry> out = HistoryUpdates.applyResults(entries, results);

		assertEquals(JournalChange.APPLIED, change(out, 0, 0).status());
		assertEquals(JournalChange.APPLIED, change(out, 0, 1).status());
		assertEquals(JournalChange.STAGED, change(out, 0, 2).status());
	}

	@Test
	void abandonedBecomesAbandoned() {
		List<JournalEntry> entries = List.of(entry("e1", JournalEntry.APPLY,
				JournalChange.file(JournalChange.ENABLE, "sodium", "sodium.jar", JournalChange.STAGED, "op1", "g1")));
		List<OpResult> results = List.of(new OpResult(withId(Op.disableFile(MODS.resolve("x.jar")), "op1", "g1"), Status.ABANDONED, "dup"));

		assertEquals(JournalChange.ABANDONED, change(HistoryUpdates.applyResults(entries, results), 0, 0).status());
	}

	@Test
	void onlyStagedChangesMove() {
		List<JournalEntry> entries = List.of(entry("e1", JournalEntry.APPLY,
				JournalChange.file(JournalChange.ENABLE, "a", "a.jar", JournalChange.DISCARDED, "op1", "g1")));
		List<OpResult> results = List.of(new OpResult(withId(Op.disableFile(MODS.resolve("a.jar")), "op1", "g1"), Status.OK, "ok"));

		assertEquals(JournalChange.DISCARDED, change(HistoryUpdates.applyResults(entries, results), 0, 0).status());
	}

	// Review M6: the executor falls back to x.jar.disabled.1 when x.jar.disabled is taken, and undo must re-enable that one.
	@Test
	void aDisableRecordsTheNameTheFileActuallyGotAndTheGroupItRanIn() {
		List<JournalEntry> entries = List.of(entry("e1", JournalEntry.APPLY,
				JournalChange.file(JournalChange.DISABLE, "indium", "indium.jar", JournalChange.STAGED, "op1", "old-group")));
		Op op = withId(Op.disableFile(MODS.resolve("indium.jar")), "op1", "merged-group");
		List<OpResult> results = List.of(new OpResult(op, Status.OK, "Disabled indium.jar -> indium.jar.disabled.1",
				MODS.resolve("indium.jar.disabled.1").toString()));

		JournalChange out = change(HistoryUpdates.applyResults(entries, results), 0, 0);

		assertEquals("indium.jar.disabled.1", out.resultFile());
		assertEquals("merged-group", out.group());
	}

	@Test
	void anAlreadyDoneDisableHasNoResultFile() {
		List<JournalEntry> entries = List.of(entry("e1", JournalEntry.APPLY,
				JournalChange.file(JournalChange.DISABLE, "indium", "indium.jar", JournalChange.STAGED, "op1", null)));
		List<OpResult> results = List.of(new OpResult(withId(Op.disableFile(MODS.resolve("indium.jar")), "op1", null),
				Status.SKIPPED_ALREADY_DONE, "indium.jar is already gone"));

		JournalChange out = change(HistoryUpdates.applyResults(entries, results), 0, 0);

		assertEquals(JournalChange.APPLIED, out.status());
		assertNull(out.resultFile());
	}

	@Test
	void anAppliedUndoChangeMarksTheChangeItRevertsReverted() {
		JournalChange original = JournalChange.setting("sodium.a", "0", "4", JournalChange.APPLIED, "op1");
		JournalChange undo = JournalChange.setting("sodium.a", "4", "0", JournalChange.STAGED, "op2").reverting(original.id());
		List<JournalEntry> entries = List.of(entry("e1", JournalEntry.APPLY, original), entry("u1", JournalEntry.UNDO, undo));
		List<OpResult> results = List.of(new OpResult(withId(Op.patchJson(Path.of("s.json"), Map.of("a", "0")), "op2", null), Status.OK, "ok"));

		List<JournalEntry> out = HistoryUpdates.applyResults(entries, results);

		assertEquals(JournalChange.REVERTED, change(out, 0, 0).status());
		assertEquals(JournalChange.APPLIED, change(out, 1, 0).status());
	}

	@Test
	void discardMarksStagedChangesByOpId() {
		List<JournalEntry> entries = List.of(entry("e1", JournalEntry.APPLY,
				JournalChange.setting("sodium.a", "0", "4", JournalChange.STAGED, "op1"),
				JournalChange.setting("sodium.b", "0", "4", JournalChange.STAGED, "op2"),
				JournalChange.setting("sodium.c", "0", "4", JournalChange.APPLIED, "op3")));

		List<JournalEntry> out = HistoryUpdates.discard(entries, Set.of("op1", "op3"));

		assertEquals(List.of(JournalChange.DISCARDED, JournalChange.STAGED, JournalChange.APPLIED),
				out.getFirst().changes().stream().map(JournalChange::status).toList());
	}

	@Test
	void revertMarksAppliedChangesById() {
		JournalChange a = JournalChange.setting("vanilla.a", "1", "2", JournalChange.APPLIED, null);
		JournalChange b = JournalChange.setting("vanilla.b", "1", "2", JournalChange.APPLIED, null);

		List<JournalEntry> out = HistoryUpdates.revert(List.of(entry("e1", JournalEntry.APPLY, a, b)), Set.of(a.id()));

		assertEquals(List.of(JournalChange.REVERTED, JournalChange.APPLIED), out.getFirst().changes().stream().map(JournalChange::status).toList());
	}

	// Review H5: a helper killed mid-run, or a failed journal update, must not leave changes STAGED forever.
	@Test
	void reconcileAppliesTheLastResultThenAbandonsLostOps() {
		List<JournalEntry> entries = List.of(entry("e1", JournalEntry.APPLY,
				JournalChange.setting("sodium.a", "0", "4", JournalChange.STAGED, "done"),
				JournalChange.setting("sodium.b", "0", "4", JournalChange.STAGED, "still-pending"),
				JournalChange.setting("sodium.c", "0", "4", JournalChange.STAGED, "lost"),
				JournalChange.setting("sodium.d", "0", "4", JournalChange.STAGED, "failed-then-dropped")));
		List<OpResult> lastApply = List.of(
				new OpResult(withId(Op.patchJson(Path.of("s.json"), Map.of("a", "4")), "done", null), Status.OK, "ok"),
				new OpResult(withId(Op.patchJson(Path.of("s.json"), Map.of("d", "4")), "failed-then-dropped", null), Status.FAILED, "x"));

		List<JournalEntry> out = HistoryUpdates.reconcile(entries, Set.of("still-pending"), lastApply);

		assertEquals(List.of(JournalChange.APPLIED, JournalChange.STAGED, JournalChange.ABANDONED, JournalChange.ABANDONED),
				out.getFirst().changes().stream().map(JournalChange::status).toList());
	}

	@Test
	void appendAddsAnEntryAtTheEnd() {
		List<JournalEntry> out = HistoryUpdates.append(List.of(entry("e1", JournalEntry.APPLY)), entry("u1", JournalEntry.UNDO));
		assertEquals(List.of("e1", "u1"), out.stream().map(JournalEntry::id).toList());
	}
}
