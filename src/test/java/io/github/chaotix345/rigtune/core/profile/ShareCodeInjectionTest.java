package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.apply.PropertiesConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.TomlConfigPatcher;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// docs/v0.4/SPEC.md AC4.3 (the files half): a code with a hostile name applies exactly like the same code with a plain
// name. The name never reaches a writer; the values that do are rebuilt from the key table. Here the three config files
// are patched as the helper patches them and compared byte for byte; options.txt is written by the game from the same
// switch values (compared as the values the game is given).
class ShareCodeInjectionTest {
	private static final String HOSTILE = "a\nb=c#d\"e‮f​g﻿h\u0007§" + "x".repeat(200);

	@TempDir
	Path dir;

	@Test
	void aHostileNameWritesTheSameBytesAsAPlainOne() throws IOException, ShareCodeException {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("vanilla.renderDistance", "9");
		values.put("vanilla.renderClouds", "fast");
		values.put("sodium.performance.use_entity_culling", "false");
		values.put("sodium.performance.chunk_build_defer_mode", "ALWAYS");
		values.put("dh.client.advanced.graphics.quality.verticalQuality", "LOW");
		values.put("dh.client.advanced.debugging.rendererMode", "DEFAULT");
		values.put("iris.enableShaders", "true");
		values.put("iris.maxShadowRenderDistance", "8");
		ShareCode.Decoded hostile = ShareCode.decode(ShareCode.encode(HOSTILE, values, -1));
		ShareCode.Decoded plain = ShareCode.decode(ShareCode.encode("plain", values, -1));
		assertEquals(plain.values(60), hostile.values(60));
		assertFalse(hostile.name().chars().anyMatch(c -> c < 0x20 || "=#\"‮​﻿§".indexOf(c) >= 0), hostile.name());

		assertArrayEquals(apply(hostile, "hostile"), apply(plain, "plain"));
		// The values in the files are the table's own spellings, never text from the code.
		String toml = Files.readString(dir.resolve("plain").resolve("DistantHorizons.toml"), StandardCharsets.UTF_8);
		assertFalse(toml.contains(HOSTILE.substring(0, 3)));
	}

	// The switch a decoded code makes, applied to copies of real config files: vanilla values as the game would get them,
	// then each staged file as the helper patches it. Returns everything written, concatenated.
	private byte[] apply(ShareCode.Decoded decoded, String name) throws IOException {
		Path config = Files.createDirectories(dir.resolve(name));
		Path sodium = config.resolve("sodium-options.json");
		Files.writeString(sodium, "{\"performance\":{\"use_entity_culling\":true,\"chunk_build_defer_mode\":\"ONE_FRAME\"}}", StandardCharsets.UTF_8);
		Path toml = copy("/dh/DistantHorizons.toml", config.resolve("DistantHorizons.toml"));
		Path properties = copy("/iris/iris.properties", config.resolve("iris.properties"));
		Map<String, String> current = new LinkedHashMap<>();
		current.put("vanilla.renderDistance", "12");
		current.put("vanilla.renderClouds", "true");
		SodiumConfigPatcher.flatten(com.google.gson.JsonParser.parseString(Files.readString(sodium)).getAsJsonObject(), "sodium.").forEach(current::put);
		TomlConfigPatcher.readValues(toml).forEach((k, v) -> current.put("dh." + k, v));
		PropertiesConfigPatcher.readValues(properties).forEach((k, v) -> current.put("iris." + k, v));
		List<Recommendation> recs = ProfileSwitch.build(decoded.values(60), new SettingsSnapshot(current), Set.of("sodium", "distanthorizons", "iris"),
				Map.of(), decoded.name() == null ? "Imported profile" : decoded.name());
		StringBuilder vanilla = new StringBuilder();
		for (Recommendation rec : recs) {
			Action.SetSetting set = (Action.SetSetting) rec.action();
			String key = set.key();
			if (key.startsWith("vanilla.")) {
				vanilla.append(key).append(':').append(set.newValue()).append('\n');
			} else if (key.startsWith("sodium.")) {
				SodiumConfigPatcher.patchFile(sodium, Map.of(key.substring("sodium.".length()), set.newValue()));
			} else if (key.startsWith("dh.")) {
				TomlConfigPatcher.patchFile(toml, Map.of(key.substring("dh.".length()), set.newValue()));
			} else if (key.startsWith("iris.")) {
				PropertiesConfigPatcher.patchFile(properties, Map.of(key.substring("iris.".length()), set.newValue()));
			}
		}
		assertEquals(8, recs.size(), "every value takes part: " + recs);
		byte[] out = (vanilla + new String(Files.readAllBytes(sodium), StandardCharsets.UTF_8) + Files.readString(toml, StandardCharsets.UTF_8)
				+ Files.readString(properties, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
		return out;
	}

	private static Path copy(String resource, Path to) throws IOException {
		try (InputStream in = ShareCodeInjectionTest.class.getResourceAsStream(resource)) {
			assertNotNull(in, resource);
			Files.write(to, in.readAllBytes());
		}
		return to;
	}
}
