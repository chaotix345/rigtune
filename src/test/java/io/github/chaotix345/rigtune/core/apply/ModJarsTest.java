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
import java.util.Set;
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

	// Review 4, rules-accuracy-1: a mod's own updater leaves its next build in mods/update/, directly or in a folder of
	// its own (Distant Horizons: update/DistantHorizons-3.3.2 - 26.2 neo/fabric-26.2.jar).
	@Test
	void queuedUpdatesAreTheModIdsOfTheJarsInModsUpdate() throws IOException {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path update = Files.createDirectories(mods.resolve("update"));
		TestJars.modJar(update.resolve("DistantHorizons-3.3.2 - 26.2 neo").resolve("fabric-26.2.jar"), "distanthorizons");
		TestJars.modJar(update.resolve("other-2.jar"), "other");
		Files.writeString(update.resolve("broken.jar"), "not a zip");
		Files.writeString(update.resolve("notes.txt"), "not a jar");
		TestJars.modJar(update.resolve("a").resolve("b").resolve("deep.jar"), "deep");
		TestJars.modJar(mods.resolve("sodium.jar"), "sodium");

		assertEquals(Set.of("distanthorizons", "other"), ModJars.queuedUpdates(mods));
	}

	@Test
	void noUpdateFolderNoQueuedUpdates() throws IOException {
		assertEquals(Set.of(), ModJars.queuedUpdates(Files.createDirectories(dir.resolve("mods"))));
		assertEquals(Set.of(), ModJars.queuedUpdates(dir.resolve("missing")));
	}

	// docs/v0.4/SPEC.md 2c (AC2c.1) and plan review P-L1: the mod's display name, with modIdOf's null-on-failure contract,
	// and sanitised because it comes from a downloaded file.
	@Test
	void readsTheModName() throws IOException {
		assertEquals("Sodium", ModJars.nameOf(TestJars.modJar(dir.resolve("sodium.jar"), "sodium", "Sodium")));
	}

	@Test
	void aMissingOrUnreadableJarOrNameHasNoName() throws IOException {
		assertNull(ModJars.nameOf(dir.resolve("gone.jar")));
		assertNull(ModJars.nameOf(TestJars.modJar(dir.resolve("unnamed.jar"), "unnamed")));
		assertNull(ModJars.nameOf(TestJars.plainJar(dir.resolve("plain.jar"))));
		assertNull(ModJars.nameOf(Files.writeString(dir.resolve("text.jar"), "not a zip")));
		assertNull(ModJars.nameOf(jarWith("object.jar", "{\"id\": \"x\", \"name\": {\"en\": \"X\"}}")));
		assertNull(ModJars.nameOf(jarWith("blank.jar", "{\"id\": \"x\", \"name\": \" \u00a7 \\u0007 \"}")));
	}

	@Test
	void theNameLosesFormattingCodesAndControlCharactersAndIsCapped() throws IOException {
		Path jar = TestJars.modJar(dir.resolve("evil.jar"), "evil", "\u00a7cRed\u202e\u200b  Mod\n\t\u0007\ufeff");

		assertEquals("Red Mod", ModJars.nameOf(jar));
		assertEquals("Foo Bar", ModJars.sanitizeName("Foo\tBar\n"));
		assertEquals("Fancy", ModJars.sanitizeName("\u00a7lFancy\u00a7"));
		assertEquals(64, ModJars.sanitizeName("\ud835\udcd0".repeat(100)).codePointCount(0, 128));
		assertEquals("\ud835\udcd0".repeat(64), ModJars.sanitizeName("\ud835\udcd0".repeat(100)));
		assertNull(ModJars.sanitizeName(null));
		assertNull(ModJars.sanitizeName("\u00a7\n"));
	}

	// docs/v0.4/SPEC.md 2o, H2: a fabric.mod.json section of ranges, a string or an array; empty when there is none.
	@Test
	void rangesOfReadsStringsAndArrays(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
		com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(
				"{\"id\":\"iris\",\"version\":\"1.12.0\",\"depends\":{\"sodium\":[\"0.10.x\",\"0.11.x\"],\"minecraft\":\"~26.2\"}}").getAsJsonObject();
		Path jar = TestJars.modJar(dir.resolve("iris.jar"), json);

		assertEquals(java.util.Map.of("sodium", java.util.List.of("0.10.x", "0.11.x"), "minecraft", java.util.List.of("~26.2")), ModJars.rangesOf(jar, "depends"));
		assertEquals(java.util.Map.of(), ModJars.rangesOf(jar, "breaks"));
		assertEquals("1.12.0", ModJars.versionOf(jar));
		assertEquals(java.util.Map.of(), ModJars.rangesOf(dir.resolve("missing.jar"), "depends"));
	}
}
