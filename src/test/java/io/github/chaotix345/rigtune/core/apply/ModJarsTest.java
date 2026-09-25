package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ModJarsTest {
	@TempDir
	Path dir;

	private Path jarWith(String name, String fabricModJson) throws IOException {
		Path jar = dir.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(fabricModJson.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	private static String oversized() {
		return "{\"id\":\"big\",\"pad\":\"" + "a".repeat(ModJars.MAX_FABRIC_MOD_JSON_BYTES) + "\"}";
	}

	// Rewrites the uncompressed size the central directory declares for the jar's only entry.
	private static void declareSize(Path jar, int size) throws IOException {
		byte[] bytes = Files.readAllBytes(jar);
		ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i + 4 <= bytes.length; i++) {
			if (buffer.getInt(i) == 0x02014b50) {
				buffer.putInt(i + 24, size);
				Files.write(jar, bytes);
				return;
			}
		}
		throw new IOException("no central directory header in " + jar);
	}

	@Test
	void readsTheModId() throws IOException {
		assertEquals("sodium", ModJars.readModId(TestJars.modJar(dir.resolve("sodium.jar"), "sodium")));
	}

	// Review 4, security-1: fabric.mod.json is read through a bounded stream, so a huge entry can't exhaust the
	// helper's memory; one over the cap has no id.
	@Test
	void aFabricModJsonOverTheCapHasNoId() throws IOException {
		assertNull(ModJars.readModId(jarWith("big.jar", oversized())));
	}

	@Test
	void anEntryThatInflatesPastItsDeclaredSizeIsCutOffAtTheCap() throws IOException {
		Path bomb = jarWith("bomb.jar", oversized());
		declareSize(bomb, 64);

		assertNull(ModJars.readModId(bomb));
	}

	@Test
	void anUnparseableFabricModJsonHasNoId() throws IOException {
		assertNull(ModJars.readModId(jarWith("broken.jar", "{\"id\": \"broken\", ")));
	}
}
