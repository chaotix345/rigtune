package io.github.chaotix345.rigtune.client.probe;

import com.google.gson.JsonParser;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.OptionInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsBridgeTest {
	private static OptionInstance<Integer> range() {
		return new OptionInstance<>("options.renderDistance", OptionInstance.noTooltip(), (caption, value) -> caption,
				new OptionInstance.IntRange(2, 32), 12, OptionInstance.NO_ACTION);
	}

	private static OptionInstance<CloudStatus> clouds() {
		return new OptionInstance<>("options.renderClouds", OptionInstance.noTooltip(), (caption, value) -> caption,
				new OptionInstance.Enum<>(List.of(CloudStatus.values()), CloudStatus.CODEC), CloudStatus.FANCY, OptionInstance.NO_ACTION);
	}

	@Test
	void rejectsKeysOutsideTheAllowlistAndControlCharacters() {
		assertNull(SettingsBridge.rejection("renderDistance", "8"));
		assertNull(SettingsBridge.rejection("graphicsPreset", "fancy"));
		assertNotNull(SettingsBridge.rejection("lang", "en_us"));
		assertNotNull(SettingsBridge.rejection("key_key.attack", "key.keyboard.q"));
		assertNotNull(SettingsBridge.rejection("resourcePacks", "[]"));
		assertNotNull(SettingsBridge.rejection("renderDistance", "8\nkey_key.attack:key.keyboard.q"));
		assertNotNull(SettingsBridge.rejection("renderDistance", "8\r"));
		assertNotNull(SettingsBridge.rejection("renderDistance", "8\u0000"));
	}

	@Test
	void decodesIntegersWithinRange() {
		assertEquals(8, SettingsBridge.decode(range(), "8").getOrThrow());
		assertFalse(SettingsBridge.decode(range(), "40").result().isPresent());
		assertFalse(SettingsBridge.decode(range(), "far").result().isPresent());
	}

	@Test
	void decodesQuotedAndUnquotedEnums() {
		assertEquals(CloudStatus.FAST, SettingsBridge.decode(clouds(), "fast").getOrThrow());
		assertEquals(CloudStatus.FAST, SettingsBridge.decode(clouds(), "\"fast\"").getOrThrow());
		assertEquals(CloudStatus.FANCY, SettingsBridge.decode(clouds(), "true").getOrThrow());
		assertFalse(SettingsBridge.decode(clouds(), "sideways").result().isPresent());
	}

	@Test
	void decodesBooleans() {
		OptionInstance<Boolean> vsync = OptionInstance.createBoolean("options.vsync", true);
		assertEquals(false, SettingsBridge.decode(vsync, "false").getOrThrow());
		assertFalse(SettingsBridge.decode(vsync, "maybe").result().isPresent());
	}

	@Test
	void encodesWithoutQuotes() {
		assertEquals("true", SettingsBridge.encode(clouds()).orElseThrow());
		assertEquals("12", SettingsBridge.encode(range()).orElseThrow());
		assertEquals("true", SettingsBridge.encode(OptionInstance.createBoolean("options.vsync", true)).orElseThrow());
	}

	@Test
	void unquoteKeepsNumbersAsWritten() {
		assertEquals("1.0", SettingsBridge.unquote(JsonParser.parseString("1.0")));
		assertEquals("custom", SettingsBridge.unquote(JsonParser.parseString("\"custom\"")));
	}

	@Test
	void primitiveParsers() {
		assertEquals(3, SettingsBridge.primitiveParser(int.class).apply("3"));
		assertEquals(0.5f, SettingsBridge.primitiveParser(float.class).apply("0.5"));
		assertEquals(Boolean.TRUE, SettingsBridge.primitiveParser(boolean.class).apply("true"));
		assertThrows(IllegalArgumentException.class, () -> SettingsBridge.primitiveParser(boolean.class).apply("yes"));
		assertEquals("x", SettingsBridge.primitiveParser(String.class).apply("x"));
	}

	@Test
	void flattensSodiumConfig(@TempDir Path dir) throws IOException {
		Path file = dir.resolve("sodium-options.json");
		Files.writeString(file, "{\"quality\":{\"pixel_filtering_mode\":\"NEAREST\"},\"performance\":{\"chunk_builder_threads\":0,\"use_entity_culling\":true}}");
		Map<String, String> values = SettingsBridge.readSodium(file);
		assertEquals("NEAREST", values.get("sodium.quality.pixel_filtering_mode"));
		assertEquals("0", values.get("sodium.performance.chunk_builder_threads"));
		assertEquals("true", values.get("sodium.performance.use_entity_culling"));
	}

	@Test
	void missingOrBrokenSodiumConfigIsEmpty(@TempDir Path dir) throws IOException {
		assertTrue(SettingsBridge.readSodium(dir.resolve("absent.json")).isEmpty());
		Path broken = dir.resolve("broken.json");
		Files.writeString(broken, "{not json");
		assertTrue(SettingsBridge.readSodium(broken).isEmpty());
	}
}
