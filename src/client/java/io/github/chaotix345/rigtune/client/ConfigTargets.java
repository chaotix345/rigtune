package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.apply.PropertiesConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.apply.TomlConfigPatcher;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Settings namespaces that live in another mod's config file. Apply stages them post-exit, one op per key, after
// validating each value against the file as it is now (docs/v0.2/SPEC.md items 3 and 7).
public final class ConfigTargets {
	public interface Stager {
		SodiumConfigPatcher.Staged stage(Path file, Map<String, String> patches);
	}

	// The file's current values, keyed without the namespace prefix.
	public interface Reader {
		Map<String, String> read(Path file);
	}

	// prefix: the settings-key namespace including its dot; keys inside the file drop it.
	public record Target(String prefix, Path file, Stager stager, Reader reader) {
	}

	private ConfigTargets() {
	}

	public static List<Target> all(Path configDir) {
		return List.of(
				new Target(SettingsBridge.SODIUM_PREFIX, SettingsBridge.sodiumConfig(), SodiumConfigPatcher::stage,
						file -> withoutPrefix(SettingsBridge.readSodium(file), SettingsBridge.SODIUM_PREFIX)),
				new Target("dh.", configDir.resolve("DistantHorizons.toml"), TomlConfigPatcher::stage, TomlConfigPatcher::readValues),
				new Target("iris.", configDir.resolve("iris.properties"), PropertiesConfigPatcher::stage, PropertiesConfigPatcher::readValues));
	}

	private static Map<String, String> withoutPrefix(Map<String, String> values, String prefix) {
		Map<String, String> out = new LinkedHashMap<>();
		values.forEach((key, value) -> out.put(key.startsWith(prefix) ? key.substring(prefix.length()) : key, value));
		return out;
	}

	public static @Nullable Target forKey(List<Target> targets, String key) {
		for (Target target : targets) {
			if (key.startsWith(target.prefix())) {
				return target;
			}
		}
		return null;
	}
}
