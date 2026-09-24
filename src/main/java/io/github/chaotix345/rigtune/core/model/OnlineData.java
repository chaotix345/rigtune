package io.github.chaotix345.rigtune.core.model;

import java.util.Map;

/**
 * Results of Modrinth lookups. availableBySlug: slug -> true/false for the running MC version
 * and Fabric (missing = unknown). updatesByModId: installed mods with a newer compatible version.
 */
public record OnlineData(boolean online, Map<String, Boolean> availableBySlug, Map<String, UpdateInfo> updatesByModId) {
	public static OnlineData offline() {
		return new OnlineData(false, Map.of(), Map.of());
	}
}
