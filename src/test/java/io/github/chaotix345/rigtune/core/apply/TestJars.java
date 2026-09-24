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
}
