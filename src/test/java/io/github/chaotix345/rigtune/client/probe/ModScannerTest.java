package io.github.chaotix345.rigtune.client.probe;

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
	void skipsBuiltinMods() {
		assertTrue(ModScanner.skip("minecraft", "builtin"));
		assertTrue(ModScanner.skip("java", "builtin"));
		assertTrue(ModScanner.skip("mixinextras", "fabric"));
		assertFalse(ModScanner.skip("fabric-api-base", "fabric"));
		assertFalse(ModScanner.skip("sodium", "fabric"));
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
