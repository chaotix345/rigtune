package io.github.chaotix345.rigtune.core.model;

import java.util.Map;

/**
 * Current settings keyed by namespaced key ("vanilla.renderDistance",
 * "sodium.performance.chunk_builder_threads"). Values are unquoted strings.
 */
public record SettingsSnapshot(Map<String, String> values) {
	public String get(String key) {
		return values.get(key);
	}

	public boolean has(String key) {
		return values.containsKey(key);
	}
}
