package io.github.chaotix345.rigtune.core.model;

import java.util.Set;

public final class SettingKeys {
	public static final String VANILLA_PREFIX = "vanilla.";
	public static final String SODIUM_PREFIX = "sodium.";
	public static final Set<String> VANILLA_ALLOWED = Set.of(
			"vanilla.renderDistance",
			"vanilla.simulationDistance",
			"vanilla.entityDistanceScaling",
			"vanilla.graphicsPreset",
			"vanilla.maxFps",
			"vanilla.enableVsync",
			"vanilla.inactivityFpsLimit",
			"vanilla.particles",
			"vanilla.biomeBlendRadius",
			"vanilla.weatherRadius",
			"vanilla.textureFiltering",
			"vanilla.renderClouds",
			"vanilla.prioritizeChunkUpdates",
			"vanilla.improvedTransparency",
			"vanilla.entityShadows",
			"vanilla.cutoutLeaves");

	private SettingKeys() {
	}

	public static boolean changeable(String key) {
		return key != null && (VANILLA_ALLOWED.contains(key) || key.startsWith(SODIUM_PREFIX) && key.length() > SODIUM_PREFIX.length());
	}

	public static boolean safeValue(String value) {
		return value != null && value.chars().noneMatch(Character::isISOControl);
	}
}
