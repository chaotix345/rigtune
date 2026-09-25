package io.github.chaotix345.rigtune.core.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JarInfoTest {
	private static byte[] zip(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

	private static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void readsTheIdProvidesNestedIdsAndDepends(@TempDir Path dir) throws IOException {
		byte[] deepest = zip(Map.of("fabric.mod.json", utf8("{\"id\":\"deep\"}")));
		Map<String, byte[]> inner = new LinkedHashMap<>();
		inner.put("fabric.mod.json", utf8("{\"id\":\"inner\",\"provides\":[\"inner-alias\"],\"jars\":[{\"file\":\"META-INF/jars/deep.jar\"}]}"));
		inner.put("META-INF/jars/deep.jar", deepest);
		Map<String, byte[]> outer = new LinkedHashMap<>();
		outer.put("fabric.mod.json", utf8("""
				{"schemaVersion":1,"id":"fabric-api","version":"1","provides":["fabric"],
				 "depends":{"fabricloader":">=0.19","minecraft":"~26.2"},
				 "jars":[{"file":"META-INF/jars/inner.jar"},{"file":"META-INF/jars/missing.jar"}]}
				"""));
		outer.put("META-INF/jars/inner.jar", zip(inner));
		Path jar = Files.write(dir.resolve("fabric-api.jar"), zip(outer));

		JarInfo info = JarInfo.read(jar);

		assertEquals("fabric-api", info.id());
		assertEquals(Set.of("fabric", "inner", "inner-alias", "deep"), info.provides());
		assertEquals(Set.of("fabricloader", "minecraft"), info.depends());
	}

	@Test
	void aFileThatIsNotAModJarGivesNull(@TempDir Path dir) throws IOException {
		assertNull(JarInfo.read(Files.writeString(dir.resolve("broken.jar"), "not a zip")));
		assertNull(JarInfo.read(Files.write(dir.resolve("plain.jar"), zip(Map.of("a.txt", utf8("a"))))));
		assertNull(JarInfo.read(Files.write(dir.resolve("noid.jar"), zip(Map.of("fabric.mod.json", utf8("{\"version\":\"1\"}"))))));
	}
}
