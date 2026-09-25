package io.github.chaotix345.rigtune.core.model;

import java.util.Set;
import java.util.regex.Pattern;

public final class SettingKeys {
	public static final String VANILLA_PREFIX = "vanilla.";
	public static final String SODIUM_PREFIX = "sodium.";
	// v0.2 item 7: DistantHorizons.toml / iris.properties, read through ConfigTargets.
	public static final String DH_PREFIX = "dh.";
	public static final String IRIS_PREFIX = "iris.";
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

	// One or more dot-separated identifier segments: letters, digits, underscore only. No path traversal ("..", "/",
	// "\"), no leading/trailing/empty segment, no whitespace, quotes or other characters that could break the TOML
	// or properties syntax the DH/Iris patchers speak, or the JSON path the Sodium patcher walks (review 3, security-2).
	private static final Pattern SAFE_DOTTED_KEY = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*");

	private SettingKeys() {
	}

	public static boolean changeable(String key) {
		return key != null && (VANILLA_ALLOWED.contains(key)
				|| safeNamespaced(key, SODIUM_PREFIX)
				|| safeNamespaced(key, DH_PREFIX)
				|| safeNamespaced(key, IRIS_PREFIX));
	}

	private static boolean safeNamespaced(String key, String prefix) {
		return key.startsWith(prefix) && key.length() > prefix.length()
				&& SAFE_DOTTED_KEY.matcher(key.substring(prefix.length())).matches();
	}

	public static boolean safeValue(String value) {
		return value != null && value.chars().noneMatch(Character::isISOControl);
	}
}
