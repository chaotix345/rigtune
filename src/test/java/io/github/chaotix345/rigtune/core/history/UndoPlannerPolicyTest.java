package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.TextChecks;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.UndoPlan.Action;
import io.github.chaotix345.rigtune.core.history.UndoPlanner.Result;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

// docs/v0.5/SPEC.md 4c, AC4c.1 (and AC4c.3's unit part, RW-14): under LAUNCHER or PENDING every APPLIED mod-file change is
// skipped with the launcher reason (the Undo screen adds the launcher's steps); a STAGED one is still cancelled (it only
// touches pending.json and RigTune's own downloads); settings revert as before. RW-14 (every policy): an APPLIED disable
// without a resultFile was no rename of RigTune's, so it is skipped rather than re-enabling a guessed <file>.disabled.
class UndoPlannerPolicyTest {
	private static final Path MODS = Path.of("game", "mods").toAbsolutePath();

	static final class PolicyState extends UndoPlannerTest.FakeState {
		ModFilesPolicy policy = ModFilesPolicy.RIGTUNE;
		@Nullable LauncherInfo launcher = LauncherInfo.of(Launcher.MODRINTH_APP);

		@Override
		public ModFilesPolicy modFiles() {
			return policy;
		}

		@Override
		public @Nullable LauncherInfo launcher() {
			return launcher;
		}
	}

	final PolicyState state = new PolicyState();
	final List<JournalEntry> entries = new ArrayList<>();
	final List<Op> pending = new ArrayList<>();
	final List<Result> planned = new ArrayList<>();

	// Every plan shows only en_us.json text (as UndoPlannerTest checks for its own).
	@AfterEach
	void everyItemIsTranslatable() {
		for (Result result : planned) {
			for (UndoPlan.Item item : result.plan().items()) {
				assertEquals(item.reason(), item.reasonText() == null ? null : item.reasonText().english(), item.toString());
				if (item.reasonText() != null) {
					TextChecks.assertPseudoLocalised(item.reasonText(), Set.of(), item.toString());
				}
			}
		}
	}

	private void entry(String id, JournalChange... changes) {
		entries.add(new JournalEntry(id, "2026-09-25T10:00:00Z", JournalEntry.APPLY, "0.4.0", "26.2", null, List.of(changes)));
	}

	private static JournalChange enabled(String modId, String file, String group) {
		return JournalChange.file(JournalChange.ENABLE, modId, file, JournalChange.APPLIED, "op-" + file, group);
	}

	private static JournalChange disabled(String modId, String file, @Nullable String resultFile, String group) {
		return JournalChange.file(JournalChange.DISABLE, modId, file, JournalChange.APPLIED, "op-" + file, group).withResultFile(resultFile);
	}

	private Result all() {
		Result result = UndoPlanner.plan(entries, pending, state, true);
		planned.add(result);
		return result;
	}

	private Result last() {
		Result result = UndoPlanner.plan(entries, pending, state, false);
		planned.add(result);
		return result;
	}

	private static List<UndoPlan.Item> items(Result result, Action action) {
		return result.plan().items().stream().filter(i -> i.action() == action).toList();
	}

	private void anAddADisableAndAnUpdatePair() {
		state.jar("lithium.jar", "lithium").jar("indium.jar.disabled", "indium").jar("sodium-0.7.0.jar.disabled", "sodium").jar("sodium-0.7.1.jar", "sodium");
		entry("e1", enabled("lithium", "lithium.jar", "g-add"), disabled("indium", "indium.jar", "indium.jar.disabled", "g-disable"),
				disabled("sodium", "sodium-0.7.0.jar", "sodium-0.7.0.jar.disabled", "g-update"), enabled("sodium", "sodium-0.7.1.jar", "g-update"));
	}

	@Test
	void underRigTuneTheyAreUndoneAsBefore() {
		anAddADisableAndAnUpdatePair();
		Result result = all();
		assertEquals(4, items(result, Action.REVERT).size(), result.plan().toString());
		assertFalse(result.script().fileOps().isEmpty());
	}

	@Test
	void underLauncherEveryAppliedFileChangeIsSkippedWithTheLauncherReason() {
		anAddADisableAndAnUpdatePair();
		state.policy = ModFilesPolicy.LAUNCHER;
		// Undo last looks for the newest entry with something left to revert (DESIGN "Undo"); here there is none.
		Result lastPlan = last();
		assertTrue(lastPlan.script().fileOps().isEmpty());
		assertTrue(lastPlan.plan().isEmpty(), lastPlan.plan().toString());
		for (Result result : List.of(all(), UndoPlanner.planEntry(entries, pending, state, "e1"))) {
			assertTrue(result.script().fileOps().isEmpty(), result.script().toString());
			assertEquals(List.of(), items(result, Action.REVERT));
			List<UndoPlan.Item> skipped = items(result, Action.SKIP);
			assertEquals(4, skipped.size(), result.plan().toString());
			for (UndoPlan.Item item : skipped) {
				assertEquals("This instance's mods are managed by the Modrinth App: change it there", item.reason(), item.toString());
			}
		}
		Result result = all();
		Map<String, String> kinds = new HashMap<>();
		items(result, Action.SKIP).forEach(i -> kinds.put(i.description(), UndoPlanner.launcherStepsKind(i)));
		assertEquals(Map.of("Enable lithium.jar", "disable", "Disable indium.jar", "enable", "Disable sodium-0.7.0.jar", "enable",
				"Enable sodium-0.7.1.jar", "disable"), kinds);
		for (UndoPlan.Item item : items(result, Action.SKIP)) {
			assertEquals(LauncherInfo.of(Launcher.MODRINTH_APP), UndoPlanner.launcherOf(item), "the steps' launcher is the reason's (review H1)");
		}
	}

	// Review M3: RigTune's own update is never undone, and under LAUNCHER no steps to disable the new RigTune or turn the
	// old one back on are offered either: its own reason comes first.
	@Test
	void underLauncherRigTunesOwnUpdateKeepsItsOwnReason() {
		state.policy = ModFilesPolicy.LAUNCHER;
		state.jar("rigtune-0.4.0.jar.disabled", "rigtune").jar("rigtune-0.5.0.jar", "rigtune");
		entry("e1", disabled("rigtune", "rigtune-0.4.0.jar", "rigtune-0.4.0.jar.disabled", "g-self"), enabled("rigtune", "rigtune-0.5.0.jar", "g-self"));

		Result result = all();

		List<UndoPlan.Item> skipped = items(result, Action.SKIP);
		assertEquals(2, skipped.size(), result.plan().toString());
		for (UndoPlan.Item item : skipped) {
			assertEquals("RigTune never undoes its own update", item.reason(), item.toString());
			assertNull(UndoPlanner.launcherStepsKind(item));
			assertNull(UndoPlanner.launcherOf(item));
		}
	}

	@Test
	void anUnnamedLauncherAndPendingNameNobody() {
		anAddADisableAndAnUpdatePair();
		state.policy = ModFilesPolicy.LAUNCHER;
		state.launcher = LauncherInfo.UNKNOWN;
		assertTrue(items(all(), Action.SKIP).stream().allMatch(i -> i.reason().equals("This instance's mods are managed by your launcher: change it there")
				&& UndoPlanner.launcherOf(i) == null));
		state.policy = ModFilesPolicy.PENDING;
		state.launcher = null;
		Result pendingPlan = all();
		assertTrue(pendingPlan.script().fileOps().isEmpty());
		for (UndoPlan.Item item : items(pendingPlan, Action.SKIP)) {
			assertEquals("Checking which launcher manages this instance's mods; undo it once that's known", item.reason());
			assertNull(UndoPlanner.launcherStepsKind(item), "no steps before the launcher is known");
		}
	}

	// A staged change still waits in pending.json: cancelling it touches only RigTune's own files, so it stays one-click.
	@Test
	void aStagedChangeIsStillCancelled() {
		state.policy = ModFilesPolicy.LAUNCHER;
		Op enable = PendingActions.group(Op.enableFile(MODS.resolve("lithium.jar.rigtune-pending"), MODS.resolve("lithium.jar")).withModId("lithium")).getFirst();
		pending.add(enable);
		entry("e1", JournalChange.file(JournalChange.ENABLE, "lithium", "lithium.jar", JournalChange.STAGED, enable.id(), enable.group()));

		Result result = all();

		assertEquals(1, items(result, Action.DISCARD_STAGED).size(), result.plan().toString());
		assertEquals(Set.of(enable.id()), result.script().discardOpIds());
	}

	// A mixed entry: its settings are put back, its applied mod files are left to the launcher.
	@Test
	void aMixedEntryRevertsItsSettingsOnly() {
		state.policy = ModFilesPolicy.LAUNCHER;
		state.settings.put("vanilla.renderDistance", "10");
		state.jar("lithium.jar", "lithium");
		entry("e1", JournalChange.setting("vanilla.renderDistance", "12", "10", JournalChange.APPLIED, null), enabled("lithium", "lithium.jar", "g1"));

		Result result = all();

		assertEquals(Map.of("vanilla.renderDistance", "12"), result.script().immediate());
		assertTrue(result.script().fileOps().isEmpty());
		assertEquals(1, items(result, Action.SKIP).size());
	}

	// RW-14: 0.2+ helpers record resultPath on every OK and the legacy import computes one for 0.1.0's, so an applied disable
	// without a resultFile was no rename of RigTune's (the jar was already gone: SKIPPED_ALREADY_DONE). Undo skips it
	// instead of re-enabling a <file>.disabled the player may have made, in every policy.
	@Test
	void aDisableWithoutAResultFileIsNotRigTunesToUndo() {
		state.jar("indium.jar.disabled", "indium");
		entry("e1", disabled("indium", "indium.jar", null, null));

		Result result = all();

		assertTrue(result.script().fileOps().isEmpty(), result.script().toString());
		UndoPlan.Item item = items(result, Action.SKIP).getFirst();
		assertEquals("RigTune didn't disable indium.jar (it was already gone)", item.reason());
		assertNull(UndoPlanner.launcherStepsKind(item));
	}

	// Review L8: the change RigTune never did keeps RW-14's reason; what was changed with it says so, naming that file.
	@Test
	void aGroupMateOfADisableRigTuneNeverDidSaysWhatItWentWith() {
		state.jar("sodium-0.7.1.jar", "sodium");
		entry("e1", disabled("sodium", "sodium-0.7.0.jar", null, "g-update"), enabled("sodium", "sodium-0.7.1.jar", "g-update"));

		Result result = all();

		assertTrue(result.script().fileOps().isEmpty(), result.script().toString());
		Map<String, String> reasons = new HashMap<>();
		items(result, Action.SKIP).forEach(i -> reasons.put(i.description(), i.reason()));
		assertEquals(Map.of("Disable sodium-0.7.0.jar", "RigTune didn't disable sodium-0.7.0.jar (it was already gone)",
				"Enable sodium-0.7.1.jar", "Changed together with sodium-0.7.0.jar, which RigTune didn't disable (it was already gone)"), reasons);
	}

	@Test
	void theStepsKindIsOnlyForTheLauncherReason() {
		UndoPlan.Item other = UndoPlan.Item.of(Text.of("rigtune.undo.item.enable", "Enable %s", "x.jar"), Action.SKIP,
				Text.of("rigtune.undo.reason.file_gone", "%s is no longer in the mods folder", "x.jar"), false, List.of(), List.of());
		assertNull(UndoPlanner.launcherStepsKind(other));
	}
}
