package io.github.chaotix345.rigtune.client.probe;

import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.client.ConfigTargets;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.OptionInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

	@Test
	void readTargetsPrefixesKeysFromEachTargetsReader(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("thing.toml"), "x");
		ConfigTargets.Target target = new ConfigTargets.Target("dh.", file, (f, p) -> null, f -> Map.of("a.b", "1"));

		assertEquals(Map.of("dh.a.b", "1"), SettingsBridge.readTargets(List.of(target)));
	}

	@Test
	void readTargetsIsEmptyForAMissingFile(@TempDir Path dir) {
		ConfigTargets.Target target = new ConfigTargets.Target("dh.", dir.resolve("absent.toml"), (f, p) -> null, f -> Map.of("x", "1"));

		assertTrue(SettingsBridge.readTargets(List.of(target)).isEmpty());
	}

	@Test
	void readTargetsCachesByLastModifiedTimeAndRereadsWhenItChanges(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("thing.properties"), "a=1");
		AtomicInteger calls = new AtomicInteger();
		ConfigTargets.Target target = new ConfigTargets.Target("iris.", file, (f, p) -> null, f -> {
			calls.incrementAndGet();
			return Map.of("a", "1");
		});

		SettingsBridge.readTargets(List.of(target));
		SettingsBridge.readTargets(List.of(target));
		assertEquals(1, calls.get(), "an unchanged mtime should reuse the cached read");

		FileTime original = Files.getLastModifiedTime(file);
		Files.setLastModifiedTime(file, FileTime.from(original.toInstant().plusSeconds(5)));
		SettingsBridge.readTargets(List.of(target));
		assertEquals(2, calls.get(), "a changed mtime should re-read");
	}

	// Review finding (Minor 8): an empty read (which for these readers can mean a transient parse failure racing a
	// concurrent write) must not be cached under a mtime that would otherwise be trusted, or the empty result could
	// stick around indefinitely even once the file is readable again.
	@Test
	void readTargetsDoesNotCacheAnEmptyRead(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("thing.toml"), "x");
		AtomicInteger calls = new AtomicInteger();
		ConfigTargets.Target target = new ConfigTargets.Target("dh.", file, (f, p) -> null,
				f -> calls.incrementAndGet() == 1 ? Map.of() : Map.of("a", "1"));

		assertTrue(SettingsBridge.readTargets(List.of(target)).isEmpty());
		assertEquals(Map.of("dh.a", "1"), SettingsBridge.readTargets(List.of(target)));
		assertEquals(2, calls.get(), "an empty read must not be cached, even though the mtime didn't change");
	}

	// Review finding (Minor 8): if the file's mtime changes while the reader is mid-read (a concurrent write), the
	// read that raced it must not be cached under either the before- or the after-mtime -- it may be a torn read.
	@Test
	void readTargetsDoesNotCacheAReadWhoseFileChangedDuringTheRead(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("thing.toml"), "x");
		AtomicInteger calls = new AtomicInteger();
		ConfigTargets.Target target = new ConfigTargets.Target("dh.", file, (f, p) -> null, f -> {
			int call = calls.incrementAndGet();
			if (call == 1) {
				try {
					FileTime now = Files.getLastModifiedTime(f);
					Files.setLastModifiedTime(f, FileTime.from(now.toInstant().plusSeconds(5)));
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			return Map.of("a", Integer.toString(call));
		});

		assertEquals(Map.of("dh.a", "1"), SettingsBridge.readTargets(List.of(target)));
		// The mtime is stable now (the racing write already landed before the first call returned), so this call
		// must not be served from a cache entry keyed to the mtime seen before that write.
		assertEquals(Map.of("dh.a", "2"), SettingsBridge.readTargets(List.of(target)));
		assertEquals(2, calls.get());
	}

	// Review finding (Minor 8): the cache stores Map.copyOf(values) rather than the reader's own map, so a reader
	// that hands back a mutable map (or reuses one across calls) can't corrupt what a later call serves from cache.
	@Test
	void readTargetsCachesADefensiveCopySoMutatingTheReadersMapAfterwardsDoesNotAffectLaterReads(@TempDir Path dir) throws IOException {
		Path file = Files.writeString(dir.resolve("thing.toml"), "x");
		Map<String, String> mutable = new java.util.HashMap<>(Map.of("a", "1"));
		ConfigTargets.Target target = new ConfigTargets.Target("dh.", file, (f, p) -> null, f -> mutable);

		SettingsBridge.readTargets(List.of(target));
		mutable.put("a", "2");

		assertEquals(Map.of("dh.a", "1"), SettingsBridge.readTargets(List.of(target)),
				"the cache must hold its own copy, not the reader's live map");
	}
}
