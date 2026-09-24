package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.apply.ModJars;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RealControllerTest {
	@Test
	void readsModIdFromJar(@TempDir Path dir) throws IOException {
		Path jar = dir.resolve("cloth.jar");
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write("{\"schemaVersion\":1,\"id\":\"cloth-config\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		assertEquals("cloth-config", ModJars.modIdOf(jar));
	}

	@Test
	void missingMetadataGivesNull(@TempDir Path dir) throws IOException {
		Path notJar = Files.writeString(dir.resolve("broken.jar"), "nope");
		assertNull(ModJars.modIdOf(notJar));
	}
}
