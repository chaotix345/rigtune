package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.RepoFiles;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/v0.5/SPEC.md 2H RW-4 (AC2H.7): History showed 0.1.0's updates of DH, Mod Menu, YACL and Zoomify as two unrelated
// rows each, because the disable's mod id is read from a jar the launcher had removed (real-world-2026-09-27.md §2.2). A
// disable without a mod id gets the mod id of the single enable in its group, at import and on the staged path, so each
// pair is one "Updated <mod>" row. The anonymised real input: 0.1.0's last-apply.json (v010/real-instance) and its
// pending.json with the DH group (tools/e2e/seeds/v010-dh), the old jars gone as at 0.4.0's first start (Entity
// Culling's .disabled copy was still there).
class LegacyImportRealUpdatesTest {
	private static final StagedChanges.ConfigKeys NO_KEYS = new StagedChanges.ConfigKeys() {
		@Override
		public String key(Op op, String keyInFile) {
			return null;
		}

		@Override
		public String current(Op op, String keyInFile) {
			return null;
		}
	};

	@Test
	void theRealImportShowsFourUpdatedRowsInsteadOfEight(@TempDir Path game) throws IOException {
		Path mods = Files.createDirectories(game.resolve("mods"));
		Path config = game.resolve("config");
		ApplyResult last = ApplyResult.load(V010Fixtures.install("real-instance/last-apply.json", config.resolve("last-apply.json"), mods, config));
		Path pending = Files.writeString(Files.createDirectories(PendingActions.defaultPath(config).getParent()).resolve("pending.json"), V010Fixtures.template(
				Files.readString(RepoFiles.resolve("tools/e2e/seeds/v010-dh/pending.json")), mods, config));
		TestJars.modJar(mods.resolve("entityculling-fabric-1.11.1-mc26.2.jar.disabled"), "entityculling");

		JournalEntry entry = LegacyImport.entry(last, PendingActions.load(pending), ModJars::modIdOf, ModJars::nameOf, NO_KEYS, "26.2");
		List<HistoryModel.Change> rows = HistoryModel.build(Journal.State.OK, List.of(entry), Map.of(), HistoryModel.Labels.RAW)
				.entries().getFirst().changes();

		List<String> updated = rows.stream().filter(r -> r.row() == HistoryModel.Row.UPDATED).map(HistoryModel.Change::modId).toList();
		assertEquals(List.of("entityculling", "modmenu", "yet_another_config_lib_v3", "zoomify", "distanthorizons"), updated);
		assertEquals(List.of(), rows.stream().filter(r -> r.row() == HistoryModel.Row.DISABLED).map(HistoryModel.Change::file).toList());
		assertEquals(12, rows.size(), rows.toString());
	}

	// Only a group with a single enable lends its mod id: with two (an addition joined to an update), which one the
	// disable belongs to can't be told, so it stays as it was.
	@Test
	void aDisableInAGroupWithTwoEnablesKeepsNoModId() {
		Path mods = Path.of("game", "mods").toAbsolutePath();
		List<Op> group = PendingActions.group(Op.disableFile(mods.resolve("a-1.jar")),
				Op.enableFile(mods.resolve("a-2.jar.rigtune-pending"), mods.resolve("a-2.jar")).withModId("a"),
				Op.enableFile(mods.resolve("lib.jar.rigtune-pending"), mods.resolve("lib.jar")).withModId("lib"));
		PendingActions leftover = PendingActions.create(1, mods, mods.resolveSibling("config"), group);

		JournalEntry entry = LegacyImport.entry(null, leftover, jar -> null, NO_KEYS, "26.2");

		assertEquals(java.util.Arrays.asList(null, "a", "lib"), entry.changes().stream().map(JournalChange::modId).toList());
	}
}
