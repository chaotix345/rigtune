package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SodiumConfigPatcherTest {
	private static final String SODIUM = """
			{
			  "quality": {"weather_quality": "DEFAULT", "enable_vignette": true},
			  "performance": {"chunk_builder_threads": 0, "use_fog_occlusion": true, "chunk_build_defer_mode": "ALWAYS"},
			  "notifications": {"has_seen_donation_prompt": true},
			  "workarounds": ["a"],
			  "version": "1"
			}""";

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void setsDottedPathsWithTypeConversionAndPreservesOtherFields() {
		JsonObject root = json(SODIUM);
		Map<String, String> patches = new LinkedHashMap<>();
		patches.put("performance.chunk_builder_threads", "4");
		patches.put("performance.use_fog_occlusion", "false");
		patches.put("performance.chunk_build_defer_mode", "ONE_FRAME");
		patches.put("quality.weather_quality", "FAST");
		patches.put("advanced.cpu_render_ahead_limit", "3");
		patches.put("advanced.scale", "0.75");
		patches.put("version", "2");

		JsonObject out = SodiumConfigPatcher.patch(root, patches);

		JsonObject performance = out.getAsJsonObject("performance");
		assertTrue(performance.get("chunk_builder_threads").getAsJsonPrimitive().isNumber());
		assertEquals(4, performance.get("chunk_builder_threads").getAsInt());
		assertTrue(performance.get("use_fog_occlusion").getAsJsonPrimitive().isBoolean());
		assertFalse(performance.get("use_fog_occlusion").getAsBoolean());
		assertEquals("ONE_FRAME", performance.get("chunk_build_defer_mode").getAsString());
		assertEquals("FAST", out.getAsJsonObject("quality").get("weather_quality").getAsString());
		assertTrue(out.getAsJsonObject("quality").get("enable_vignette").getAsBoolean());
		assertEquals(3, out.getAsJsonObject("advanced").get("cpu_render_ahead_limit").getAsInt());
		assertEquals(0.75, out.getAsJsonObject("advanced").get("scale").getAsDouble());
		assertTrue(out.get("version").getAsJsonPrimitive().isString());
		assertEquals("2", out.get("version").getAsString());
		assertEquals(json(SODIUM).get("notifications"), out.get("notifications"));
		assertEquals(json(SODIUM).get("workarounds"), out.get("workarounds"));
		assertEquals(json(SODIUM), root);
	}

	@Test
	void booleanFieldsAcceptTrueOrFalseInAnyCase() {
		JsonObject out = SodiumConfigPatcher.patch(json(SODIUM), Map.of("performance.use_fog_occlusion", "FALSE", "quality.enable_vignette", "True"));

		assertFalse(out.getAsJsonObject("performance").get("use_fog_occlusion").getAsBoolean());
		assertTrue(out.getAsJsonObject("performance").get("use_fog_occlusion").getAsJsonPrimitive().isBoolean());
		assertTrue(out.getAsJsonObject("quality").get("enable_vignette").getAsBoolean());
	}

	@Test
	void booleanFieldsRejectEverythingElse() {
		for (String value : new String[]{"1", "0", "yes", "", "fast", " true"}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> SodiumConfigPatcher.patch(json(SODIUM), Map.of("performance.use_fog_occlusion", value)), value);
			assertTrue(e.getMessage().contains("performance.use_fog_occlusion") && e.getMessage().contains("true or false"), e.getMessage());
		}
	}

	@Test
	void numberFieldsKeepTheirKindOfNumber() {
		JsonObject root = json("{\"a\":{\"threads\":0,\"scale\":0.5,\"big\":1}}");

		JsonObject out = SodiumConfigPatcher.patch(root, Map.of("a.threads", "-3", "a.scale", "0.75", "a.big", "99999999999999999999"));

		assertEquals(-3, out.getAsJsonObject("a").get("threads").getAsInt());
		assertEquals(0.75, out.getAsJsonObject("a").get("scale").getAsDouble());
		assertEquals("99999999999999999999", out.getAsJsonObject("a").get("big").getAsString());
		assertEquals(1.0, SodiumConfigPatcher.patch(root, Map.of("a.scale", "1")).getAsJsonObject("a").get("scale").getAsDouble());
		for (String value : new String[]{"fast", "true", "4.5", "1e3", ""}) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> SodiumConfigPatcher.patch(root, Map.of("a.threads", value)), value);
			assertTrue(e.getMessage().contains("a.threads"), e.getMessage());
		}
		assertThrows(IllegalArgumentException.class, () -> SodiumConfigPatcher.patch(root, Map.of("a.scale", "half")));
	}

	@Test
	void stringFieldsStayStrings() {
		JsonObject out = SodiumConfigPatcher.patch(json(SODIUM), Map.of("quality.weather_quality", "true", "version", "3"));

		assertTrue(out.getAsJsonObject("quality").get("weather_quality").getAsJsonPrimitive().isString());
		assertEquals("true", out.getAsJsonObject("quality").get("weather_quality").getAsString());
		assertTrue(out.get("version").getAsJsonPrimitive().isString());
	}

	@Test
	void newFieldsGetAnInferredType() {
		JsonObject out = SodiumConfigPatcher.patch(json("{\"a\":{\"gone\":null}}"),
				Map.of("a.flag", "TRUE", "a.count", "12", "a.ratio", "1.5", "a.mode", "FANCY", "a.gone", "false"));

		JsonObject a = out.getAsJsonObject("a");
		assertTrue(a.get("flag").getAsJsonPrimitive().isBoolean() && a.get("flag").getAsBoolean());
		assertTrue(a.get("count").getAsJsonPrimitive().isNumber());
		assertEquals(1.5, a.get("ratio").getAsDouble());
		assertTrue(a.get("mode").getAsJsonPrimitive().isString());
		assertTrue(a.get("gone").getAsJsonPrimitive().isBoolean());
	}

	@Test
	void refusesToReplaceObjectsOrListsWithValues() {
		assertThrows(IllegalArgumentException.class, () -> SodiumConfigPatcher.patch(json(SODIUM), Map.of("quality", "1")));
		assertThrows(IllegalArgumentException.class, () -> SodiumConfigPatcher.patch(json(SODIUM), Map.of("workarounds", "x")));
	}

	@Test
	void aBadValueFailsTheWholePatchOpAndLeavesTheFileAlone(@TempDir Path dir) throws IOException {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path config = Files.createDirectories(dir.resolve("config"));
		Path file = Files.writeString(config.resolve("sodium-options.json"), SODIUM);
		Path pending = PendingActions.defaultPath(config);
		Map<String, String> patches = new LinkedHashMap<>();
		patches.put("performance.chunk_builder_threads", "4");
		patches.put("performance.use_fog_occlusion", "1");
		PendingActions plan = PendingActions.create(1, mods, config, List.of(PendingActions.Op.patchJson(file, patches)));
		plan.save(pending);

		ApplyResult result = new ApplyExecutor(2, 1).run(plan, pending);

		assertEquals(ApplyResult.Status.FAILED, result.results().getFirst().status());
		assertTrue(result.results().getFirst().message().contains("expects true or false"), result.results().getFirst().message());
		assertEquals(SODIUM, Files.readString(file));
	}

	@Test
	void refusesToReplaceNonObjectWithObject() {
		assertThrows(IllegalArgumentException.class,
				() -> SodiumConfigPatcher.patch(json(SODIUM), Map.of("version.major", "2")));
	}

	@Test
	void flattensPrimitiveLeaves() {
		Map<String, String> flat = SodiumConfigPatcher.flatten(json(SODIUM), "sodium.");

		assertEquals("0", flat.get("sodium.performance.chunk_builder_threads"));
		assertEquals("true", flat.get("sodium.performance.use_fog_occlusion"));
		assertEquals("DEFAULT", flat.get("sodium.quality.weather_quality"));
		assertEquals("1", flat.get("sodium.version"));
		assertFalse(flat.containsKey("sodium.workarounds"));
		assertEquals(7, flat.size());
	}

	@Test
	void patchesFileAtomicallyAndReportsNoOp(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("sodium-options.json");
		Files.writeString(file, SODIUM);

		assertTrue(SodiumConfigPatcher.patchFile(file, Map.of("performance.chunk_builder_threads", "6")));
		String written = Files.readString(file);
		assertTrue(written.contains("\n  \"quality\": {"), written);
		assertEquals("6", SodiumConfigPatcher.flatten(json(written), "").get("performance.chunk_builder_threads"));
		assertEquals("ALWAYS", SodiumConfigPatcher.flatten(json(written), "").get("performance.chunk_build_defer_mode"));

		assertFalse(SodiumConfigPatcher.patchFile(file, Map.of("performance.chunk_builder_threads", "6")));
		try (var files = Files.list(dir)) {
			assertEquals(1, files.count());
		}

		Path missing = dir.resolve("new").resolve("sodium-options.json");
		assertTrue(SodiumConfigPatcher.patchFile(missing, Map.of("performance.chunk_builder_threads", "2")));
		assertEquals(Map.of("performance.chunk_builder_threads", "2"), SodiumConfigPatcher.flatten(json(Files.readString(missing)), ""));
	}
}
