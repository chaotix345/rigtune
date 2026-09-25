package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesConfigPatcherTest {
	// The test working directory is a build scratch dir (build.gradle's `test.workingDir`), not the project root, so
	// the fixture is loaded from the test classpath rather than a relative file path.
	private static String fixture() throws IOException {
		try (InputStream in = PropertiesConfigPatcherTest.class.getResourceAsStream("/iris/iris.properties")) {
			if (in == null) {
				throw new IOException("Missing test resource iris/iris.properties");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void readValuesReadsEveryKey(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());
		Map<String, String> values = PropertiesConfigPatcher.readValues(file);

		assertEquals("false", values.get("enableShaders"));
		assertEquals("32", values.get("maxShadowRenderDistance"));
		assertEquals("SRGB", values.get("colorSpace"));
		assertEquals("ComplementaryReimagined_r5.9.3.zip", values.get("shaderPack"));
	}

	@Test
	void readValuesIsEmptyForAMissingFile(@TempDir Path dir) {
		assertTrue(PropertiesConfigPatcher.readValues(dir.resolve("absent.properties")).isEmpty());
	}

	@Test
	void patchFileSetsAnExistingKeyAndReportsChange(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());

		assertTrue(PropertiesConfigPatcher.patchFile(file, Map.of("maxShadowRenderDistance", "16")));
		assertEquals("16", PropertiesConfigPatcher.readValues(file).get("maxShadowRenderDistance"));

		assertFalse(PropertiesConfigPatcher.patchFile(file, Map.of("maxShadowRenderDistance", "16")));
	}

	@Test
	void patchFileWritesAFileJavaPropertiesCanReadBack(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());
		PropertiesConfigPatcher.patchFile(file, Map.of("enableShaders", "true"));

		Properties reloaded = new Properties();
		try (var in = Files.newInputStream(file)) {
			reloaded.load(in);
		}
		assertEquals("true", reloaded.getProperty("enableShaders"));
		assertEquals("32", reloaded.getProperty("maxShadowRenderDistance"), "other keys untouched");
	}

	@Test
	void patchFileThrowsForAMissingFile(@TempDir Path dir) {
		assertThrows(IOException.class, () -> PropertiesConfigPatcher.patchFile(dir.resolve("absent.properties"), Map.of("a", "1")));
	}

	@Test
	void patchFileThrowsForAMissingKeyAndLeavesTheFileAlone(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());
		String before = Files.readString(file, StandardCharsets.UTF_8);

		assertThrows(IOException.class, () -> PropertiesConfigPatcher.patchFile(file, Map.of("noSuchKey", "1")));
		assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void stagingRefusesAMissingKeyAndStagesTheRest(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("iris.properties"), fixture());
		Map<String, String> patches = new LinkedHashMap<>();
		patches.put("maxShadowRenderDistance", "16");
		patches.put("noSuchKey", "1");

		SodiumConfigPatcher.Staged staged = PropertiesConfigPatcher.stage(file, patches);

		assertEquals(1, staged.ops().size());
		assertEquals(List.of("noSuchKey"), List.copyOf(staged.refused().keySet()));
	}

	@Test
	void stagingRefusesEverythingForAMissingOrBrokenFile(@TempDir Path dir) throws IOException {
		Map<String, String> patches = Map.of("maxShadowRenderDistance", "16");
		SodiumConfigPatcher.Staged missing = PropertiesConfigPatcher.stage(dir.resolve("absent.properties"), patches);
		assertTrue(missing.ops().isEmpty());
		assertEquals(patches.keySet(), missing.refused().keySet());

		Path broken = Files.writeString(dir.resolve("broken.properties"), "maxShadowRenderDistance=\\u12");
		SodiumConfigPatcher.Staged brokenStaged = PropertiesConfigPatcher.stage(broken, patches);
		assertTrue(brokenStaged.ops().isEmpty());
		assertEquals(patches.keySet(), brokenStaged.refused().keySet());
	}
}
