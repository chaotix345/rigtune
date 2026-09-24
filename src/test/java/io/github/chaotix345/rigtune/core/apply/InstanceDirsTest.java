package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InstanceDirsTest {
	@Test
	void defaultsToGameDirMods(@TempDir Path dir) throws IOException {
		assertEquals(dir.resolve("mods").toAbsolutePath().normalize(), InstanceDirs.modsDir(dir, null));
		Files.createDirectories(dir.resolve("mods"));
		assertEquals(dir.resolve("mods").toRealPath(), InstanceDirs.modsDir(dir, null));
	}

	@Test
	void honoursTheModsFolderPropertyAsFabricDoes(@TempDir Path dir) throws IOException {
		Path shared = Files.createDirectories(dir.resolve("shared").resolve("mods-26.2"));
		assertEquals(shared.toRealPath(), InstanceDirs.modsDir(dir.resolve("instance"), shared.toString()));
		// Fabric takes a relative value as given (Paths.get), so it is relative to the working directory, not gameDir.
		assertEquals(Path.of("custom-mods").toAbsolutePath().normalize(), InstanceDirs.modsDir(dir.resolve("instance"), "custom-mods"));
	}

	@Test
	void findsTheInstanceFromWhereThePlanIs(@TempDir Path dir) {
		Path pending = PendingActions.defaultPath(dir.resolve("config"));

		assertEquals(dir.resolve("config").toAbsolutePath().normalize(), InstanceDirs.configDirOf(pending));
		assertEquals(InstanceDirs.modsDir(dir, null), InstanceDirs.modsDirOf(pending));
		assertThrows(IllegalArgumentException.class, () -> InstanceDirs.configDirOf(dir.getRoot().resolve("pending.json")));
	}

	@Test
	void aSymlinkedModsFolderResolvesToWhereFabricFindsTheJars(@TempDir Path dir) throws IOException {
		Path real = Files.createDirectories(dir.resolve("real-mods"));
		Path game = Files.createDirectories(dir.resolve("game"));
		try {
			Files.createSymbolicLink(game.resolve("mods"), real);
		} catch (IOException | UnsupportedOperationException e) {
			assumeTrue(false, "symbolic links unavailable: " + e);
		}
		Path mods = InstanceDirs.modsDir(game, null);

		assertEquals(real.toRealPath(), mods);
		assertTrue(SafeFileNames.isDirectChild(game.resolve("mods"), real.resolve("sodium.jar")));
		assertTrue(SafeFileNames.isDirectChild(mods, game.resolve("mods").resolve("sodium.jar")));
	}
}
