package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyImportTest {
	private static final Path MODS = Path.of("game", "mods").toAbsolutePath();
	private static final Path CONFIG = Path.of("game", "config").toAbsolutePath();
	private static final Path SODIUM = CONFIG.resolve("sodium-options.json");

	private static final StagedChanges.ConfigKeys CONFIG_KEYS = new StagedChanges.ConfigKeys() {
		@Override
		public String key(Op op, String keyInFile) {
			return Path.of(op.path()).equals(SODIUM) ? "sodium." + keyInFile : null;
		}

		@Override
		public String current(Op op, String keyInFile) {
			return "0";
		}
	};

	// Jars read as: rigtune-*.jar* -> rigtune, otherwise the file name up to the first '-'.
	private static String modIdOf(Path jar) {
		String name = jar.getFileName().toString();
		return name.startsWith("rigtune") ? "rigtune" : name.substring(0, Math.max(0, name.indexOf('-')));
	}

	private static ApplyResult result(ApplyResult.OpResult... results) {
		return new ApplyResult("2026-09-24T18:05:00Z", List.of(results));
	}

	private static Op withId(Op op, String id, String group) {
		return new Op(op.type(), op.from(), op.to(), op.path(), op.patches(), id, group, op.modId(), op.attempts());
	}

	private static JournalEntry entry(ApplyResult lastApply, PendingActions leftover) {
		return LegacyImport.entry(lastApply, leftover, LegacyImportTest::modIdOf, CONFIG_KEYS, "26.2");
	}

	@Test
	void theLastRunsFileOpsBecomeAppliedChangesWithoutRigTuneOrConfigPatches() {
		ApplyResult last = result(
				new ApplyResult.OpResult(withId(Op.disableFile(MODS.resolve("rigtune-0.1.0.jar")), "self-1", "self"), ApplyResult.Status.OK,
						"Disabled rigtune-0.1.0.jar -> rigtune-0.1.0.jar.disabled"),
				new ApplyResult.OpResult(withId(Op.enableFile(MODS.resolve("rigtune-0.2.0.jar.rigtune-pending"), MODS.resolve("rigtune-0.2.0.jar"))
						.withModId("rigtune"), "self-2", "self"), ApplyResult.Status.OK, "Enabled rigtune-0.2.0.jar"),
				new ApplyResult.OpResult(withId(Op.enableFile(MODS.resolve("lithium-0.25.jar.rigtune-pending"), MODS.resolve("lithium-0.25.jar"))
						.withModId("lithium"), "l", "g"), ApplyResult.Status.OK, "Enabled lithium-0.25.jar"),
				new ApplyResult.OpResult(withId(Op.patchJson(SODIUM, Map.of("a", "1")), "p", null), ApplyResult.Status.OK, "Patched"),
				new ApplyResult.OpResult(withId(Op.enableFile(MODS.resolve("ferrite-8.jar.rigtune-pending"), MODS.resolve("ferrite-8.jar"))
						.withModId("ferrite"), "f", "g2"), ApplyResult.Status.FAILED, "already exists"));

		JournalEntry entry = entry(last, null);

		assertEquals(JournalEntry.LEGACY_IMPORT, entry.kind());
		assertEquals("2026-09-24T18:05:00Z", entry.at());
		assertEquals("26.2", entry.mcVersion());
		assertEquals(List.of("lithium-0.25.jar"), entry.changes().stream().map(JournalChange::file).toList());
		JournalChange lithium = entry.changes().getFirst();
		assertEquals(JournalChange.ENABLE, lithium.action());
		assertEquals(JournalChange.APPLIED, lithium.status());
		assertEquals("lithium", lithium.modId());
		assertEquals("g", lithium.group());
	}

	// Review M6: 0.1.0 only says where a disabled jar went in its message.
	@Test
	void aDisablesActualNameComesFromTheMessageOrTheResultPath() {
		ApplyResult last = result(
				new ApplyResult.OpResult(withId(Op.disableFile(MODS.resolve("indium-1.0.jar")), "i", null), ApplyResult.Status.OK,
						"Disabled indium-1.0.jar -> indium-1.0.jar.disabled.1"),
				new ApplyResult.OpResult(withId(Op.disableFile(MODS.resolve("starlight-1.1.jar")), "s", null), ApplyResult.Status.OK,
						"Disabled starlight-1.1.jar", MODS.resolve("starlight-1.1.jar.disabled.2").toString()),
				new ApplyResult.OpResult(withId(Op.disableFile(MODS.resolve("gone-1.jar")), "g", null), ApplyResult.Status.SKIPPED_ALREADY_DONE,
						"gone-1.jar is already gone"));

		List<JournalChange> changes = entry(last, null).changes();

		assertEquals(List.of("indium-1.0.jar.disabled.1", "starlight-1.1.jar.disabled.2"),
				changes.stream().limit(2).map(JournalChange::resultFile).toList());
		assertEquals("indium", changes.getFirst().modId());
		assertNull(changes.get(2).resultFile());
		assertTrue(changes.stream().allMatch(c -> JournalChange.DISABLE.equals(c.action()) && JournalChange.APPLIED.equals(c.status())));
	}

	// Review L2: a disable without a group is RigTune's own when the disabled jar reads as rigtune.
	@Test
	void rigTunesOwnDisableIsFoundByReadingTheJar() {
		ApplyResult last = result(new ApplyResult.OpResult(withId(Op.disableFile(MODS.resolve("rigtune-0.1.0.jar")), "x", null),
				ApplyResult.Status.OK, "Disabled rigtune-0.1.0.jar -> rigtune-0.1.0.jar.disabled"));

		assertNull(entry(last, null));
	}

	@Test
	void leftoverStagedOpsWithIdsBecomeStagedChanges() {
		List<Op> update = PendingActions.group(Op.disableFile(MODS.resolve("sodium-0.7.0.jar")),
				Op.enableFile(MODS.resolve("sodium-0.7.1.jar.rigtune-pending"), MODS.resolve("sodium-0.7.1.jar")).withModId("sodium"));
		Op patch = Op.patchJson(SODIUM, Map.of("performance.threads", "4"));
		Op untracked = new Op(PendingActions.Type.DISABLE_FILE, null, null, MODS.resolve("starlight-1.jar").toString(), null);
		List<Op> selfUpdate = PendingActions.group(Op.disableFile(MODS.resolve("rigtune-0.1.0.jar")),
				Op.enableFile(MODS.resolve("rigtune-0.2.0.jar.rigtune-pending"), MODS.resolve("rigtune-0.2.0.jar")).withModId("rigtune"));
		List<Op> ops = new ArrayList<>(update);
		ops.add(patch);
		ops.add(untracked);
		ops.addAll(selfUpdate);
		PendingActions leftover = PendingActions.create(1, MODS, CONFIG, ops);

		List<JournalChange> changes = entry(null, leftover).changes();

		assertEquals(List.of(update.get(0).id(), update.get(1).id(), patch.id()), changes.stream().map(JournalChange::opId).toList());
		assertTrue(changes.stream().allMatch(c -> JournalChange.STAGED.equals(c.status())));
		assertEquals("sodium.performance.threads", changes.get(2).key());
		assertEquals("0", changes.get(2).before());
		assertEquals(update.get(0).group(), changes.get(0).group());
	}

	@Test
	void nothingToImportGivesNull() {
		assertNull(entry(null, null));
		assertNull(entry(result(), PendingActions.create(1, MODS, CONFIG, List.of())));
	}

	// docs/v0.4/SPEC.md 2o L1: every last-apply.json 0.1.0 wrote in the fixtures (hand-written, captured from the released
	// jar, the user's real instance) is 0.1.x's.
	@Test
	void the010LastApplyFilesAreFrom010(@TempDir Path game) throws IOException {
		Path mods = game.resolve("mods");
		Path config = game.resolve("config");
		for (String fixture : List.of("last-apply.json", "captured/last-apply.json", "real-instance/last-apply.json")) {
			ApplyResult last = ApplyResult.load(V010Fixtures.install(fixture, config.resolve(fixture.replace('/', '-')), mods, config));
			assertTrue(LegacyImport.fromV010(last), fixture);
		}
	}

	// What 0.2.0 and later write, or what can't be told apart: never 0.1.x.
	@Test
	void laterOrUnclearLastApplyFilesAreNotFrom010() {
		Op enable = withId(Op.enableFile(MODS.resolve("x.jar.rigtune-pending"), MODS.resolve("x.jar")).withModId("x"), "x", "g");
		ApplyResult.OpResult enabled010 = new ApplyResult.OpResult(enable, ApplyResult.Status.OK, "Enabled x.jar");
		assertTrue(LegacyImport.fromV010(result(enabled010)));

		assertFalse(LegacyImport.fromV010(null));
		assertFalse(LegacyImport.fromV010(result()));
		assertFalse(LegacyImport.fromV010(result(enabled010, new ApplyResult.OpResult(withId(Op.disableFile(MODS.resolve("y.jar")), "y", null),
				ApplyResult.Status.OK, "Disabled y.jar -> y.jar.disabled", MODS.resolve("y.jar.disabled").toString()))), "a resultPath");
		assertFalse(LegacyImport.fromV010(result(enabled010, new ApplyResult.OpResult(Op.patchToml(CONFIG.resolve("DistantHorizons.toml"),
				Map.of("a", "1")), ApplyResult.Status.OK, "Patched"))), "a TOML patch");
		assertFalse(LegacyImport.fromV010(result(enabled010, new ApplyResult.OpResult(Op.patchProperties(CONFIG.resolve("iris.properties"),
				Map.of("a", "1")), ApplyResult.Status.OK, "Patched"))), "a properties patch");
		assertFalse(LegacyImport.fromV010(result(new ApplyResult.OpResult(enable.withProjectId("P7dR8mSH"), ApplyResult.Status.OK, "Enabled x.jar"))),
				"a Modrinth project id");
		assertFalse(LegacyImport.fromV010(result(enabled010, new ApplyResult.OpResult(new Op(null, null, null, "z", null), ApplyResult.Status.OK,
				"?"))), "an unknown op type");
		assertFalse(LegacyImport.fromV010(result(new ApplyResult.OpResult(Op.patchJson(SODIUM, Map.of("a", "1")), ApplyResult.Status.OK, "Patched"))),
				"only a patch");
		assertFalse(LegacyImport.fromV010(result(new ApplyResult.OpResult(enable, ApplyResult.Status.FAILED, "locked"))), "no file op done");

		// Of those, only the ones with a later marker are known to be a later version's.
		assertFalse(LegacyImport.fromLater(null));
		assertFalse(LegacyImport.fromLater(result(enabled010)));
		assertFalse(LegacyImport.fromLater(result(new ApplyResult.OpResult(enable, ApplyResult.Status.FAILED, "locked"))));
		assertTrue(LegacyImport.fromLater(result(new ApplyResult.OpResult(enable, ApplyResult.Status.OK, "Enabled x.jar", MODS.resolve("x.jar").toString()))));
		assertTrue(LegacyImport.fromLater(result(new ApplyResult.OpResult(Op.patchJson(CONFIG.resolve("other.json"), Map.of("a", "1")),
				ApplyResult.Status.OK, "Patched"))));
	}

	// docs/v0.4/SPEC.md 2o L1: ops 0.1.x staged (every released 0.1.0 op has an id; updates are grouped) are 0.1.x's; any
	// op only a later version stages makes the plan a later one.
	@Test
	void plansOnly010CouldStageAreRecognised(@TempDir Path game) throws IOException {
		Path mods = game.resolve("mods");
		Path config = game.resolve("config");
		for (String fixture : List.of("pending.json", "captured/pending.json", "pending-pre-groups.json")) {
			PendingActions plan = PendingActions.load(V010Fixtures.install(fixture, config.resolve(fixture.replace('/', '-')), mods, config));
			assertTrue(LegacyImport.v010Plan(plan), fixture);
		}
		Op sodium = Op.patchJson(SODIUM, Map.of("a", "1"));
		Op disable = Op.disableFile(MODS.resolve("y.jar"));
		assertTrue(LegacyImport.v010Plan(PendingActions.create(1, MODS, CONFIG, List.of(sodium, disable))));
		assertFalse(LegacyImport.v010Plan(null));
		assertFalse(LegacyImport.v010Plan(PendingActions.create(1, MODS, CONFIG, List.of())));
		assertFalse(LegacyImport.v010Plan(PendingActions.create(1, MODS, CONFIG, List.of(sodium,
				Op.patchToml(CONFIG.resolve("DistantHorizons.toml"), Map.of("a", "1"))))), "a TOML patch");
		assertFalse(LegacyImport.v010Plan(PendingActions.create(1, MODS, CONFIG, List.of(sodium,
				Op.patchProperties(CONFIG.resolve("iris.properties"), Map.of("a", "1"))))), "a properties patch");
		assertFalse(LegacyImport.v010Plan(PendingActions.create(1, MODS, CONFIG, List.of(disable,
				Op.enableFile(MODS.resolve("x.jar.rigtune-pending"), MODS.resolve("x.jar")).withVersionId("v1")))), "a Modrinth version id");
		assertFalse(LegacyImport.v010Plan(PendingActions.create(1, MODS, CONFIG, List.of(Op.patchJson(CONFIG.resolve("other.json"),
				Map.of("a", "1"))))), "a JSON patch of another file");
	}
}
