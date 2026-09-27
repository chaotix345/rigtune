package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 2H L5: fabric.mod.json bytes read in memory (RangeReader) say what Apply's own reads of the jar say
// (ModJars.modIdOf/nameOf/versionOf/rangesOf, JarInfo's provides).
class FabricModJsonTest {
	private static final String FULL = """
			{"schemaVersion":1,"id":"iris","version":"1.11.4","name":"Iris \\u00a7cShaders","provides":["oculus"],
			 "depends":{"sodium":["0.9.x","0.10.x"],"minecraft":">=26.2"},"breaks":{"optifabric":"*"},
			 "jars":[{"file":"META-INF/jars/glsl-transformer.jar"}]}""";

	private static FabricModJson parse(String json) {
		return FabricModJson.parse(json.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void readsWhatApplyReadsFromTheJar(@TempDir Path dir) throws IOException {
		Path jar = TestJars.modJar(dir.resolve("iris.jar"), com.google.gson.JsonParser.parseString(FULL).getAsJsonObject());
		FabricModJson json = parse(FULL);

		assertEquals(ModJars.modIdOf(jar), json.id());
		assertEquals(ModJars.nameOf(jar), json.name());
		assertEquals(ModJars.versionOf(jar), json.version());
		assertEquals(ModJars.rangesOf(jar, "depends"), json.depends());
		assertEquals(ModJars.rangesOf(jar, "breaks"), json.breaks());
		assertEquals(Set.of("oculus"), json.provides());
		assertTrue(json.nestsJars());
		assertEquals(Map.of("sodium", List.of("0.9.x", "0.10.x"), "minecraft", List.of(">=26.2")), json.depends());
	}

	@Test
	void withoutNestedJarsItsProvidesAreJarInfosOwn(@TempDir Path dir) throws IOException {
		String json = "{\"schemaVersion\":1,\"id\":\"lib\",\"version\":\"2\",\"provides\":[\"lib-api\"]}";
		Path jar = TestJars.modJar(dir.resolve("lib.jar"), com.google.gson.JsonParser.parseString(json).getAsJsonObject());

		FabricModJson parsed = parse(json);

		assertFalse(parsed.nestsJars());
		assertEquals(JarInfo.read(jar).provides(), parsed.provides());
		assertEquals(Map.of(), parsed.depends());
	}

	// Where Apply's ModJars.modIdOf reads no id, the jar is "not a Fabric mod jar" there too.
	@Test
	void noIdMeansNotAMod() {
		assertNull(parse("{\"schemaVersion\":1,\"version\":\"1\"}"));
		assertNull(parse("{\"id\":{\"nested\":true}}"));
		assertNull(parse("[\"id\"]"));
		assertNull(parse("not json {"));
		assertNull(parse(""));
		assertEquals("42", parse("{\"id\":42}").id());
	}
}
