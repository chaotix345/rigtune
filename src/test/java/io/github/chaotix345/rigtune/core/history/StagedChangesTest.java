package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagedChangesTest {
	private static final Path GAME = Path.of("game").toAbsolutePath();
	private static final Path MODS = GAME.resolve("mods");
	private static final Path CONFIG = GAME.resolve("config");
	private static final Path SODIUM = CONFIG.resolve("sodium-options.json");

	// sodium-options.json holds threads = 0; any other file is unknown.
	private static final StagedChanges.ConfigKeys CONFIG_KEYS = new StagedChanges.ConfigKeys() {
		@Override
		public String key(Op op, String keyInFile) {
			return Path.of(op.path()).equals(SODIUM) ? "sodium." + keyInFile : null;
		}

		@Override
		public String current(Op op, String keyInFile) {
			return Map.of("performance.threads", "0").get(keyInFile);
		}
	};

	private static PendingActions plan(List<Op> ops) {
		return PendingActions.create(1, MODS, CONFIG, ops);
	}

	private static StagedChanges.Outcome stage(PendingActions base, List<Op> incoming, Set<String> journaled) {
		return StagedChanges.of(base, incoming, base.merge(incoming), CONFIG_KEYS, jar -> "id-of-" + jar.getFileName(), journaled);
	}

	@Test
	void fileOpsAreRecordedWithTheirSurvivingIdsGroupsAndModIds() {
		List<Op> update = PendingActions.group(Op.disableFile(MODS.resolve("sodium-0.7.0.jar")),
				Op.enableFile(MODS.resolve("sodium-0.7.1.jar.rigtune-pending"), MODS.resolve("sodium-0.7.1.jar")).withModId("sodium"));

		List<JournalChange> changes = stage(plan(List.of()), update, Set.of()).changes();

		assertEquals(2, changes.size());
		JournalChange disable = changes.get(0);
		assertEquals(JournalChange.DISABLE, disable.action());
		assertEquals("sodium-0.7.0.jar", disable.file());
		assertEquals("id-of-sodium-0.7.0.jar", disable.modId());
		assertEquals(update.get(0).id(), disable.opId());
		assertEquals(update.get(0).group(), disable.group());
		JournalChange enable = changes.get(1);
		assertEquals(JournalChange.ENABLE, enable.action());
		assertEquals("sodium-0.7.1.jar", enable.file());
		assertEquals("sodium", enable.modId());
		assertEquals(JournalChange.STAGED, enable.status());
		assertEquals(update.get(1).id(), enable.opId());
	}

	// Review H5: a repeated op keeps the staged id, and the journal already has a change for it.
	@Test
	void aRepeatedOpIsNotRecordedAgainButJoinsTheNewGroup() {
		Op disable = Op.disableFile(MODS.resolve("x.jar"));
		PendingActions base = plan(List.of(disable));
		List<Op> update = PendingActions.group(Op.disableFile(MODS.resolve("x.jar")),
				Op.enableFile(MODS.resolve("x-2.jar.rigtune-pending"), MODS.resolve("x-2.jar")).withModId("x"));

		List<JournalChange> changes = stage(base, update, Set.of(disable.id())).changes();

		assertEquals(List.of("x-2.jar"), changes.stream().map(JournalChange::file).toList());
	}

	@Test
	void aRepeatedOpThatWasNeverJournaledIsRecordedWithTheStagedId() {
		Op disable = Op.disableFile(MODS.resolve("x.jar"));
		List<JournalChange> changes = stage(plan(List.of(disable)), List.of(Op.disableFile(MODS.resolve("x.jar"))), Set.of()).changes();

		assertEquals(List.of(disable.id()), changes.stream().map(JournalChange::opId).toList());
	}

	// Review H5: threads 0 -> 4 is staged, then 6: undoing the second must give 4, not 0.
	@Test
	void aConfigBeforeValueCountsTheOpsAlreadyStaged() {
		PendingActions base = plan(List.of(Op.patchJson(SODIUM, Map.of("performance.threads", "4"))));

		List<JournalChange> changes = stage(base, List.of(Op.patchJson(SODIUM, Map.of("performance.threads", "6"))), Set.of()).changes();

		assertEquals(1, changes.size());
		assertEquals("sodium.performance.threads", changes.getFirst().key());
		assertEquals("4", changes.getFirst().before());
		assertEquals("6", changes.getFirst().after());
	}

	@Test
	void aConfigBeforeValueComesFromTheFileWhenNothingIsStaged() {
		List<JournalChange> changes = stage(plan(List.of()), List.of(Op.patchJson(SODIUM, Map.of("performance.threads", "6"))), Set.of()).changes();

		assertEquals("0", changes.getFirst().before());
		assertEquals(JournalChange.SETTING, changes.getFirst().type());
	}

	@Test
	void aConfigChangeToTheSameValueIsNotRecorded() {
		PendingActions base = plan(List.of(Op.patchJson(SODIUM, Map.of("performance.threads", "4"))));

		assertTrue(stage(base, List.of(Op.patchJson(SODIUM, Map.of("performance.threads", "4"))), Set.of()).changes().isEmpty());
	}

	@Test
	void anUnknownConfigFileIsNotRecorded() {
		List<Op> ops = List.of(Op.patchJson(CONFIG.resolve("other.json"), Map.of("a", "1")));

		assertTrue(stage(plan(List.of()), ops, Set.of()).changes().isEmpty());
	}

	@Test
	void aReplacedEnableIsReportedForDiscarding() {
		Op old = Op.enableFile(MODS.resolve("m-1.jar.rigtune-pending"), MODS.resolve("m-1.jar")).withModId("m");
		Op newer = Op.enableFile(MODS.resolve("m-2.jar.rigtune-pending"), MODS.resolve("m-2.jar")).withModId("m");

		StagedChanges.Outcome outcome = stage(plan(List.of(old)), List.of(newer), Set.of(old.id()));

		assertEquals(List.of(old.id()), outcome.discardedOpIds());
		assertEquals(List.of(newer.id()), outcome.changes().stream().map(JournalChange::opId).toList());
	}

	// docs/v0.4/SPEC.md 2c (AC2c.1): each file change records the jar's display name, read where the jar is at staging
	// time: the download (op.from()) for an enable, the installed jar (op.path()) for a disable; null when it can't be read.
	@Test
	void fileChangesRecordTheJarsNameFromWhereTheJarIsNow(@TempDir Path dir) throws Exception {
		Path mods = dir.resolve("mods");
		Path download = TestJars.modJar(mods.resolve("sodium-0.7.1.jar.rigtune-pending"), "sodium", "Sodium");
		Path installed = TestJars.modJar(mods.resolve("iris-1.0.jar"), "iris", "Iris Shaders");
		List<Op> ops = List.of(
				Op.enableFile(download, mods.resolve("sodium-0.7.1.jar")).withModId("sodium"),
				Op.disableFile(installed),
				Op.disableFile(mods.resolve("gone.jar")));
		PendingActions base = PendingActions.create(1, mods, dir.resolve("config"), List.of());

		List<JournalChange> changes = StagedChanges.of(base, ops, base.merge(ops), CONFIG_KEYS, ModJars::modIdOf, ModJars::nameOf, Set.of()).changes();

		assertEquals("Sodium", changes.get(0).modName());
		assertEquals("Iris Shaders", changes.get(1).modName());
		assertNull(changes.get(2).modName());
		assertEquals("sodium-0.7.1.jar", changes.get(0).file());
	}

	@Test
	void withoutANameReaderNothingIsRecorded() {
		List<Op> ops = List.of(Op.disableFile(MODS.resolve("sodium-0.7.0.jar")));

		assertNull(stage(plan(List.of()), ops, Set.of()).changes().getFirst().modName());
	}
}
