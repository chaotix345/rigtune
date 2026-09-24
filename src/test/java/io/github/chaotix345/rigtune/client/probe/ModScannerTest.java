package io.github.chaotix345.rigtune.client.probe;

import net.fabricmc.loader.api.metadata.ModOrigin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModScannerTest {
	@Test
	void skipsBuiltinAndNestedMods() {
		assertTrue(ModScanner.skip("minecraft", "builtin", ModOrigin.Kind.UNKNOWN, false));
		assertTrue(ModScanner.skip("mixinextras", "fabric", ModOrigin.Kind.PATH, false));
		assertTrue(ModScanner.skip("fabric-api-base", "fabric", ModOrigin.Kind.NESTED, true));
		assertFalse(ModScanner.skip("sodium", "fabric", ModOrigin.Kind.PATH, false));
	}

	@Test
	void hashesSingleJars(@TempDir Path dir) throws IOException {
		Path jar = dir.resolve("mod.jar");
		Files.writeString(jar, "abc");
		assertEquals(jar, ModScanner.singleJar(List.of(jar)));
		assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", ModScanner.sha1(jar));
	}

	@Test
	void ignoresDirectoriesAndMultiplePaths(@TempDir Path dir) throws IOException {
		Path jar = Files.writeString(dir.resolve("a.jar"), "x");
		assertNull(ModScanner.singleJar(List.of(dir)));
		assertNull(ModScanner.singleJar(List.of(jar, jar)));
		assertNull(ModScanner.singleJar(List.of(dir.resolve("missing.jar"))));
	}
}
