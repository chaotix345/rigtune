package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TestJars {
	private TestJars() {
	}

	// A minimal Fabric mod jar: just a fabric.mod.json with the given id.
	public static Path modJar(Path jar, String modId) throws IOException {
		Files.createDirectories(jar.toAbsolutePath().getParent());
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"schemaVersion\":1,\"id\":\"" + modId + "\",\"version\":\"1\"}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	// A Fabric mod jar with this fabric.mod.json.
	public static Path modJar(Path jar, com.google.gson.JsonObject fabricModJson) throws IOException {
		Files.createDirectories(jar.toAbsolutePath().getParent());
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(fabricModJson.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	// A Fabric mod jar whose fabric.mod.json also has a display name.
	public static Path modJar(Path jar, String modId, String name) throws IOException {
		Files.createDirectories(jar.toAbsolutePath().getParent());
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			com.google.gson.JsonObject json = new com.google.gson.JsonObject();
			json.addProperty("schemaVersion", 1);
			json.addProperty("id", modId);
			json.addProperty("version", "1");
			json.addProperty("name", name);
			zip.write(json.toString().getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}

	// A valid jar that isn't a Fabric mod: no fabric.mod.json.
	public static Path plainJar(Path jar) throws IOException {
		Files.createDirectories(jar.toAbsolutePath().getParent());
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			zip.putNextEntry(new ZipEntry("pack.mcmeta"));
			zip.write("{}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return jar;
	}
}
