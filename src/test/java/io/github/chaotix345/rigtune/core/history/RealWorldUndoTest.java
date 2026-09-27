package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.UndoPlan.Action;
import io.github.chaotix345.rigtune.core.history.UndoPlanner.Result;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.github.chaotix345.rigtune.core.history.RealWorldFixtures.DH;
import static io.github.chaotix345.rigtune.core.history.RealWorldFixtures.DH_OLD;
import static io.github.chaotix345.rigtune.core.history.RealWorldFixtures.LEGACY_ENTRY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4c, AC4c.2 and AC4c.3 on the user's real records (src/test/resources/realworld/2026-09-27): what Undo
// last, Undo all and Undo this ("Imported from 0.1") do in the Modrinth App instance where 0.1.0's mod changes broke the
// app's Update all (RW-2), before and after P0.4's rule, and RW-14's disable without a resultFile (rw's variant D).
// Formerly rigtune-realworld's throwaway RealWorld20260927Test (its Undo half); WS-L2 keeps the helper half.
class RealWorldUndoTest {
	@TempDir
	Path instance;

	static final class State extends UndoPlannerTest.FakeState {
		ModFilesPolicy policy = ModFilesPolicy.RIGTUNE;

		@Override
		public ModFilesPolicy modFiles() {
			return policy;
		}

		@Override
		public LauncherInfo launcher() {
			return LauncherInfo.of(Launcher.MODRINTH_APP);
		}
	}

	private List<Result> plans(State state) {
		List<JournalEntry> entries = RealWorldFixtures.history(instance);
		return List.of(UndoPlanner.plan(entries, List.of(), state, false), UndoPlanner.plan(entries, List.of(), state, true),
				UndoPlanner.planEntry(entries, List.of(), state, LEGACY_ENTRY));
	}

	private static List<String> ops(Result result) {
		return result.script().fileOps().stream().map(RealWorldUndoTest::describe).toList();
	}

	private static String describe(Op op) {
		return op.type() == PendingActions.Type.ENABLE_FILE
				? "enable " + Path.of(op.from()).getFileName() + " -> " + Path.of(op.to()).getFileName()
				: "disable " + Path.of(op.path()).getFileName();
	}

	private static List<String> skipped(Result result, String file) {
		return result.plan().items().stream().filter(i -> i.action() == Action.SKIP && i.description().contains(file)).map(UndoPlan.Item::reason).toList();
	}

	@Test
	void theRecordsAreTheRealOnes() {
		List<JournalEntry> entries = RealWorldFixtures.history(instance);
		assertEquals(1, entries.size());
		JournalEntry legacy = entries.getFirst();
		assertEquals(LEGACY_ENTRY, legacy.id());
		assertEquals(JournalEntry.LEGACY_IMPORT, legacy.kind());
		assertEquals(17, legacy.changes().size());
		assertTrue(legacy.changes().stream().allMatch(c -> JournalChange.APPLIED.equals(c.status())));
		JournalChange dhDisable = legacy.changes().stream().filter(c -> DH_OLD.equals(c.file())).findFirst().orElseThrow();
		assertNull(dhDisable.resultFile(), "0.4.0's helper found the old DH jar already gone (RW-14)");
	}

	// Before P0.4 (RIGTUNE) minus RW-14's skip: the 7 added jars disabled and Entity Culling swapped back to 1.11.1; the
	// Mod Menu, YACL and Zoomify groups skipped (their old copies are gone); the DH group skipped by RW-14 now. Never the
	// app's DH jar.
	@Test
	void underRigTuneTheOldPlanWithRw14() {
		State state = (State) RealWorldFixtures.folder(new State());
		for (Result result : plans(state)) {
			assertEquals(List.of("disable ResourcefulConfig-5.0.0.jar", "disable structure_layout_optimizer-1.1.4+26.1-fabric.jar",
					"disable Ixeris-4.6.8+26.2-fabric.jar", "disable fastquit-3.1.5+mc26.2.jar", "disable asynclogger-2.2.2+26.1.2-fabric.jar",
					"disable moreculling-fabric-26.2-1.8.1.jar", "disable bbe-fabric-1.3.7+mc26.2.jar", "disable entityculling-fabric-1.11.2-mc26.2.jar",
					"enable entityculling-fabric-1.11.1-mc26.2.jar.disabled -> entityculling-fabric-1.11.1-mc26.2.jar").stream().sorted().toList(),
					ops(result).stream().sorted().toList(), result.plan().toString());
			assertEquals(List.of("RigTune didn't disable fabric-26.2.jar (it was already gone)"), skipped(result, DH_OLD));
			assertEquals(List.of("RigTune didn't disable fabric-26.2.jar (it was already gone)"), skipped(result, DH));
			assertEquals(List.of("modmenu-20.0.2.jar.disabled is no longer in the mods folder"), skipped(result, "modmenu-20.0.2.jar"));
			assertTrue(ops(result).stream().noneMatch(op -> op.contains(DH) || op.contains(DH_OLD)), ops(result).toString());
		}
	}

	// AC4c.2: under LAUNCHER (the Modrinth App) none of Undo last, Undo all and Undo this stages a file op; every applied
	// mod-file change is the app's to change back, with its reason.
	@Test
	void underLauncherNoUndoStagesAFileOp() {
		State state = (State) RealWorldFixtures.folder(new State());
		state.policy = ModFilesPolicy.LAUNCHER;
		for (Result result : plans(state)) {
			assertEquals(List.of(), ops(result), result.plan().toString());
			assertTrue(result.plan().items().stream().noneMatch(i -> i.action() == Action.REVERT), result.plan().toString());
			assertTrue(result.plan().items().stream().allMatch(i -> "This instance's mods are managed by the Modrinth App: change it there".equals(i.reason())),
					result.plan().toString());
		}
		Result all = plans(state).get(1);
		assertEquals(17, all.plan().items().size());
	}

	// AC4c.3, rw's variant D: had the player disabled DH 3.3.0 in the app instead (the app renames it to
	// fabric-26.2.jar.disabled), 0.4 would swap the app's DH 3.3.2 back to it; RW-14 skips the disable RigTune never did,
	// in every policy.
	@Test
	void variantDStagesNoRenameOfTheAppsJars() {
		for (ModFilesPolicy policy : List.of(ModFilesPolicy.RIGTUNE, ModFilesPolicy.LAUNCHER)) {
			State state = (State) RealWorldFixtures.folder(new State());
			state.jar(DH_OLD + ".disabled", "distanthorizons");
			state.policy = policy;
			for (Result result : plans(state)) {
				assertTrue(ops(result).stream().noneMatch(op -> op.contains(DH) || op.contains(DH_OLD)), policy + ": " + ops(result));
				if (result.plan().undoOf() != null) {
					assertEquals(2, skipped(result, DH_OLD).size() + skipped(result, DH).size(), policy + ": " + result.plan());
				}
			}
		}
	}
}
