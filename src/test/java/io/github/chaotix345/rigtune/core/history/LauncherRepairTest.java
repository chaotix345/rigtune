package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.history.LauncherRepair.Findings;
import io.github.chaotix345.rigtune.core.history.LauncherRepair.Pair;
import io.github.chaotix345.rigtune.core.launcher.Launcher;
import io.github.chaotix345.rigtune.core.model.Text;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// docs/v0.5/SPEC.md 4g (AC4g.1, AC4g.2's unit part): what an older RigTune's mod-file changes left for the launcher to
// catch up with, from RigTune's own records only (history.json, the mods folder), and the notice's text per launcher.
class LauncherRepairTest {
	final UndoPlannerTest.FakeState folder = new UndoPlannerTest.FakeState();
	final List<JournalChange> changes = new ArrayList<>();

	private static JournalChange disable(String file, String modId, String resultFile, String group) {
		return JournalChange.file(JournalChange.DISABLE, modId, file, JournalChange.APPLIED, "op-" + file, group).withResultFile(resultFile);
	}

	private static JournalChange enable(String file, String modId, String group) {
		return JournalChange.file(JournalChange.ENABLE, modId, file, JournalChange.APPLIED, "op-" + file, group);
	}

	private Findings find() {
		return LauncherRepair.find(List.of(new JournalEntry("e1", "2026-09-24T23:09:01Z", JournalEntry.APPLY, "0.4.0", "26.2", null, changes)), folder);
	}

	private void update(String modId, String group) {
		changes.add(disable(modId + "-1.jar", modId, modId + "-1.jar.disabled", group));
		changes.add(enable(modId + "-2.jar", modId, group));
	}

	@Test
	void anUpdatePairInEffect() {
		update("sodium", "g1");
		folder.jar("sodium-1.jar.disabled", "sodium").jar("sodium-2.jar", "sodium");

		assertEquals(new Findings(List.of(new Pair("sodium", "sodium-1.jar.disabled", "sodium-2.jar")), List.of(), List.of()), find());
	}

	// 0.1.0's disables have no mod id (the legacy import): it's read from the .disabled jar.
	@Test
	void aLegacyDisableIsMatchedByTheIdOfItsDisabledJar() {
		update("modmenu", "g1");
		changes.set(0, disable("modmenu-1.jar", null, "modmenu-1.jar.disabled", "g1"));
		folder.jar("modmenu-1.jar.disabled", "modmenu").jar("modmenu-2.jar", "modmenu");

		assertEquals(List.of(new Pair("modmenu", "modmenu-1.jar.disabled", "modmenu-2.jar")), find().pairs());
	}

	@Test
	void anAddedJarAndRigTuneItself() {
		changes.add(enable("fastquit.jar", "fastquit", "g1"));
		changes.add(enable("lib.jar", "lib", "g1"));
		changes.add(enable("rigtune-0.4.0.jar", "rigtune", "g2"));
		folder.jar("fastquit.jar", "fastquit").jar("lib.jar", "lib").jar("rigtune-0.4.0.jar", "rigtune");

		assertEquals(new Findings(List.of(), List.of("fastquit.jar", "lib.jar"), List.of()), find());
	}

	@Test
	void aDisableAlone() {
		changes.add(disable("indium.jar", "indium", "indium.jar.disabled", "g1"));
		folder.jar("indium.jar.disabled", "indium");

		assertEquals(new Findings(List.of(), List.of(), List.of("indium.jar.disabled")), find());
	}

	// A change whose file is gone was repaired (the player deleted it in the launcher).
	@Test
	void whatIsGoneIsRepaired() {
		update("sodium", "g1");
		changes.add(enable("fastquit.jar", "fastquit", "g2"));
		changes.add(disable("indium.jar", "indium", "indium.jar.disabled", "g3"));
		folder.jar("sodium-2.jar", "sodium");

		assertTrue(find().isEmpty(), find().toString());
	}

	// RW-14: a disable without a resultFile was no rename of RigTune's ("already gone"): never a finding, even with a
	// .disabled copy of that name there (the player's own, or the launcher's).
	@Test
	void aDisableRigTuneDidntDoIsNoFinding() {
		changes.add(disable("fabric-26.2.jar", null, null, "g1"));
		changes.add(enable("DistantHorizons.jar", "distanthorizons", "g1"));
		folder.jar("fabric-26.2.jar.disabled", "distanthorizons").jar("DistantHorizons.jar", "distanthorizons");

		assertTrue(find().isEmpty(), find().toString());
	}

	@Test
	void onlyAppliedChangesCount() {
		changes.add(enable("fastquit.jar", "fastquit", "g1").withStatus(JournalChange.STAGED));
		changes.add(enable("ixeris.jar", "ixeris", "g2").withStatus(JournalChange.REVERTED));
		changes.add(enable("bbe.jar", "bbe", "g3").withStatus(JournalChange.ABANDONED));
		folder.jar("fastquit.jar", "fastquit").jar("ixeris.jar", "ixeris").jar("bbe.jar", "bbe");

		assertTrue(find().isEmpty(), find().toString());
	}

	// AC4g.1 on the anonymised real instance (the user's history.json after 0.4.0's helper run, with the mods listing):
	// exactly one pair in effect (Entity Culling 1.11.1 -> 1.11.2) and 7 added jars; the DH group (RW-1) is none of them.
	@Test
	void theRealInstance() throws IOException {
		Path copy = Path.of("C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/590d2d3e-58b4-418a-a809-0e625214088f/scratchpad/realworld/instance-copy");
		assumeTrue(Files.isRegularFile(copy.resolve("config/rigtune/history.json")));
		List<JournalEntry> entries = new Journal(copy.resolve("config"), "0.4.0+mc26.2", "26.2", (m, e) -> {
		}).entries();
		UndoPlannerTest.FakeState real = new UndoPlannerTest.FakeState();
		for (String line : Files.readAllLines(copy.resolve("mods-listing.txt"), StandardCharsets.UTF_8)) {
			if (!line.isBlank()) {
				real.jar(line.strip(), "other:" + line.strip());
			}
		}

		Findings findings = LauncherRepair.find(entries, real);

		assertEquals(List.of(new Pair("entityculling", "entityculling-fabric-1.11.1-mc26.2.jar.disabled", "entityculling-fabric-1.11.2-mc26.2.jar")),
				findings.pairs());
		assertEquals(List.of("bbe-fabric-1.3.7+mc26.2.jar", "moreculling-fabric-26.2-1.8.1.jar", "asynclogger-2.2.2+26.1.2-fabric.jar",
				"fastquit-3.1.5+mc26.2.jar", "Ixeris-4.6.8+26.2-fabric.jar", "structure_layout_optimizer-1.1.4+26.1-fabric.jar",
				"ResourcefulConfig-5.0.0.jar"), findings.added());
		assertEquals(List.of(), findings.disabledOnly());
	}

	// Dismiss hides the notice until the set of findings it asks a step for changes: the key follows that set, not its
	// order, and not what needs nothing in that launcher (added jars; disabled copies outside ATLauncher, pairs in it).
	@Test
	void theKeyFollowsTheSetOfFindingsThatNeedAStep() {
		Pair a = new Pair("a", "a-1.jar.disabled", "a-2.jar");
		Pair b = new Pair("b", "b-1.jar.disabled", "b-2.jar");
		Findings one = new Findings(List.of(a, b), List.of("x.jar"), List.of());
		Findings reordered = new Findings(List.of(b, a), List.of(), List.of());
		Findings moreDisabled = new Findings(List.of(a, b), List.of(), List.of("c.jar.disabled"));
		Findings fewer = new Findings(List.of(a), List.of(), List.of());

		assertTrue(one.key(Launcher.MODRINTH_APP).startsWith(LauncherRepair.KEY_PREFIX), one.key(Launcher.MODRINTH_APP));
		assertEquals(one.key(Launcher.MODRINTH_APP), reordered.key(Launcher.MODRINTH_APP));
		assertEquals(one.key(Launcher.MODRINTH_APP), moreDisabled.key(Launcher.MODRINTH_APP));
		assertNotEquals(one.key(Launcher.MODRINTH_APP), fewer.key(Launcher.MODRINTH_APP));
		assertNotEquals(one.key(Launcher.ATLAUNCHER), moreDisabled.key(Launcher.ATLAUNCHER));
		assertEquals(one.key(Launcher.ATLAUNCHER), fewer.key(Launcher.ATLAUNCHER));
	}

	@Test
	void actionablePerLauncher() {
		Findings pairs = new Findings(List.of(new Pair("a", "a-1.jar.disabled", "a-2.jar")), List.of(), List.of());
		Findings disabled = new Findings(List.of(), List.of(), List.of("c.jar.disabled"));
		Findings added = new Findings(List.of(), List.of("x.jar"), List.of());

		for (Launcher launcher : Launcher.values()) {
			assertEquals(launcher != Launcher.ATLAUNCHER, LauncherRepair.actionable(pairs, launcher), launcher.name());
			assertEquals(launcher == Launcher.ATLAUNCHER, LauncherRepair.actionable(disabled, launcher), launcher.name());
			assertFalse(LauncherRepair.actionable(added, launcher), launcher.name());
		}
	}

	// AC4g.2: Copy list holds only file names, one per line, hidden characters escaped.
	@Test
	void theCopyListHoldsFileNamesOnlyEscaped() {
		Findings findings = new Findings(List.of(new Pair("a", "a-1\u202e.jar.disabled", "a-2.jar"), new Pair("b", "b-1.jar.disabled", "b-2.jar")),
				List.of("x.jar"), List.of("c.jar.disabled"));

		assertEquals("a-1\\u202e.jar.disabled\nb-1.jar.disabled", LauncherRepair.copyList(findings, Launcher.MODRINTH_APP));
		assertEquals("c.jar.disabled", LauncherRepair.copyList(findings, Launcher.ATLAUNCHER));
	}

	@Test
	void theModrinthAppTextPutsDeletingTheOldCopyInTheAppFirst() {
		Findings findings = new Findings(List.of(new Pair("a", "a-1.jar.disabled", "a-2.jar"), new Pair("b", "b-1.jar.disabled", "b-2.jar")),
				List.of("x.jar", "y.jar"), List.of());

		assertEquals("Mod changes from an older RigTune: help Modrinth App catch up", LauncherRepair.message(Launcher.MODRINTH_APP).english());
		String detail = LauncherRepair.detail(findings, Launcher.MODRINTH_APP).english();
		assertEquals("Do this in the Modrinth App, not in File Explorer (the app keeps its own list): open this instance → Content → filter"
				+ " Disabled → select the old copy → Delete: a-1.jar.disabled, b-1.jar.disabled. RigTune's newer versions stay. If an old copy"
				+ " isn't listed, the app has already hidden it: nothing to do. Or, to let the app own every file: delete the new copy, update"
				+ " the old one, then switch it on. RigTune also added: x.jar, y.jar. The app lists them as your own files and will update"
				+ " them itself; nothing to do.", detail);
	}

	@Test
	void everyLauncherHasItsOwnSteps() {
		Findings pairs = new Findings(List.of(new Pair("a", "a-1.jar.disabled", "a-2.jar")), List.of(), List.of());
		Findings disabled = new Findings(List.of(), List.of(), List.of("c.jar.disabled"));

		assertTrue(LauncherRepair.detail(pairs, Launcher.PRISM).english().startsWith("In Prism Launcher: Edit... → Mods → select the old disabled"
				+ " copy → Remove, before any Check for Updates: a-1.jar.disabled."), LauncherRepair.detail(pairs, Launcher.PRISM).english());
		assertTrue(LauncherRepair.detail(pairs, Launcher.GDLAUNCHER).english().startsWith("In GDLauncher: Mods → the old disabled copy → Delete:"
				+ " a-1.jar.disabled."), LauncherRepair.detail(pairs, Launcher.GDLAUNCHER).english());
		assertTrue(LauncherRepair.detail(pairs, Launcher.CURSEFORGE).english().contains("a-1.jar.disabled"));
		assertTrue(LauncherRepair.detail(disabled, Launcher.ATLAUNCHER).english().startsWith("ATLauncher doesn't list the mods RigTune disabled:"
				+ " c.jar.disabled."), LauncherRepair.detail(disabled, Launcher.ATLAUNCHER).english());
		for (Launcher generic : List.of(Launcher.MULTIMC, Launcher.OFFICIAL, Launcher.UNKNOWN)) {
			assertTrue(LauncherRepair.detail(pairs, generic).english().startsWith("In the launcher that keeps this instance's list of mods, remove"
					+ " the old disabled copies: a-1.jar.disabled."), generic.name());
		}
		assertEquals("Mod changes from an older RigTune: help your launcher catch up", LauncherRepair.message(Launcher.UNKNOWN).english());
		for (Launcher launcher : Launcher.values()) {
			assertAllKeysAreRepairKeys(LauncherRepair.detail(pairs, launcher));
		}
	}

	private static void assertAllKeysAreRepairKeys(Text text) {
		switch (text) {
			case Text.Translatable t -> {
				assertTrue(t.key().startsWith("rigtune.repair."), t.key());
				t.args().stream().filter(a -> a instanceof Text).forEach(a -> assertAllKeysAreRepairKeys((Text) a));
			}
			case Text.Joined j -> j.parts().forEach(LauncherRepairTest::assertAllKeysAreRepairKeys);
			case Text.Literal l -> {
			}
		}
	}

	// The held changes' count: an update (a disable and an enable of one group) is one mod change.
	@Test
	void heldModChangesCountAnUpdateOnce() {
		var mods = Path.of("mods");
		var update = io.github.chaotix345.rigtune.core.apply.PendingActions.group(
				io.github.chaotix345.rigtune.core.apply.PendingActions.Op.disableFile(mods.resolve("a-1.jar")),
				io.github.chaotix345.rigtune.core.apply.PendingActions.Op.enableFile(mods.resolve("a-2.jar.rigtune-pending"), mods.resolve("a-2.jar")));
		var addition = io.github.chaotix345.rigtune.core.apply.PendingActions.group(
				io.github.chaotix345.rigtune.core.apply.PendingActions.Op.enableFile(mods.resolve("b.jar.rigtune-pending"), mods.resolve("b.jar")),
				io.github.chaotix345.rigtune.core.apply.PendingActions.Op.enableFile(mods.resolve("lib.jar.rigtune-pending"), mods.resolve("lib.jar")));
		var all = new ArrayList<>(update);
		all.addAll(addition);

		assertEquals(1, LauncherRepair.modChanges(update));
		assertEquals(3, LauncherRepair.modChanges(all));
		assertEquals(Set.of(), Set.copyOf(LauncherRepair.find(List.of(), folder).added()));
	}
}
