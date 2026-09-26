package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 2d, AC2d.2 (amendments A-H1, A-M1, A-L1): the staged view the incompatibility checks read, built
// from pending.json as the helper sees it (PendingActions.relocated).
class StagedProjectsTest {
	@TempDir
	Path dir;

	private Path pendingFile(List<Op> ops) throws Exception {
		Path config = Files.createDirectories(dir.resolve("config"));
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path file = PendingActions.defaultPath(config);
		Files.createDirectories(file.getParent());
		PendingActions.create(1, mods, config, ops).save(file);
		return file;
	}

	@Test
	void stagedEnablesWithAProjectCountAndNothingElseDoes() throws Exception {
		Path mods = dir.resolve("mods");
		Path config = dir.resolve("config");
		Op lithium = Op.enableFile(mods.resolve("lithium.jar.rigtune-pending"), mods.resolve("lithium.jar")).withModId("lithium")
				.withProjectId("gvQqBUqZ").withVersionId("ZouiUX7t");
		Op noVersion = Op.enableFile(mods.resolve("krypton.jar.rigtune-pending"), mods.resolve("krypton.jar")).withModId("krypton")
				.withProjectId("fQEb0iXm");
		// Written by 0.3.0 (no projectId), or an undo's re-enable: nothing to fold.
		Op old = Op.enableFile(mods.resolve("old.jar.rigtune-pending"), mods.resolve("old.jar")).withModId("old");
		Op disable = Op.disableFile(mods.resolve("sodium.jar"));
		Op patch = Op.patchJson(config.resolve("sodium-options.json"), Map.of("quality.weather_quality", "\"FAST\""));
		// Another instance's folders: relocated() drops it, as the helper would.
		Op elsewhere = Op.enableFile(dir.resolve("other/mods/x.jar.rigtune-pending"), dir.resolve("other/mods/x.jar")).withModId("x")
				.withProjectId("ELSEWHERE").withVersionId("ELSEV");

		StagedProjects staged = StagedProjects.read(pendingFile(List.of(lithium, noVersion, old, disable, patch, elsewhere)));

		assertEquals(Set.of("gvQqBUqZ", "fQEb0iXm"), staged.projects());
		assertEquals(Map.of("ZouiUX7t", "gvQqBUqZ"), staged.projectByVersion());
	}

	@Test
	void noPlanOrAnUnreadableOneStagesNothing() throws Exception {
		Path file = PendingActions.defaultPath(Files.createDirectories(dir.resolve("config")));
		assertSame(StagedProjects.NONE, StagedProjects.read(file));
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{ not json");
		assertSame(StagedProjects.NONE, StagedProjects.read(file));
		assertTrue(StagedProjects.fold(null).isEmpty());
	}

	@Test
	void aPlanWrittenBy030StagesNothing() throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path file = pendingFile(List.of());
		Files.writeString(file, """
				{"createdAt": "2026-09-20T09:00:00Z", "gamePid": 1, "modsDir": "%s", "configDir": "%s",
				 "ops": [{"type": "ENABLE_FILE", "from": "%s", "to": "%s", "id": "op-1", "modId": "a", "attempts": 0}]}
				""".formatted(slashes(mods), slashes(dir.resolve("config")), slashes(mods.resolve("a.jar.rigtune-pending")), slashes(mods.resolve("a.jar"))));

		assertTrue(StagedProjects.read(file).isEmpty());
	}

	private static String slashes(Path path) {
		return path.toAbsolutePath().toString().replace('\\', '/');
	}
}
