package io.github.chaotix345.rigtune.core.profile;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// The v1 share-code key table (docs/v0.4/SPEC.md 4, docs/research/v0.4/profiles.md §5.2): FROZEN and APPEND-ONLY. A key's
// index is its wire id, and an enum's value order is its wire values, so neither ever changes; a later RigTune only adds
// keys at the end (and values at the end of an enum), each a varint type, so older decoders can skip what they don't know.
// Every value is an integer on the wire (no strings, paths, mod ids or floats). The two thread counts are local-only:
// profiles keep them, share codes never carry them (a decoder drops them). This table is also the profiles' managed
// keyset: a profile never holds another key (never vanilla.graphicsPreset or iris.shaderPack).
public final class ShareKeys {
	public enum Kind {
		BOOL, ENUM, INT, INT10, QUARTER
	}

	// INT10: maxFps, 10..260 in steps of 10 (260 = unlimited), wire value/10 - 1. The wire value MATCH_DISPLAY means "match the
	// display's refresh" and resolves on import to that display's $refreshRateCap (60 when unknown).
	public static final int MATCH_DISPLAY = 26;

	// min/max: INT/INT10 bounds of the value; QUARTER bounds in quarter steps (0.5..5.0 = 2..20). values: an ENUM's values.
	public record Key(int index, String key, Kind kind, int min, int max, List<String> values, boolean shareable) {
		public Key {
			values = List.copyOf(values);
		}

		// The largest wire value (MATCH_DISPLAY for maxFps is a separate reserved value).
		public int maxWire() {
			return switch (kind) {
				case BOOL -> 1;
				case ENUM -> values.size() - 1;
				case INT -> max - min;
				case INT10 -> max / 10 - 1;
				case QUARTER -> max - min;
			};
		}

		public boolean validWire(int wire) {
			return wire >= 0 && (wire <= maxWire() || kind == Kind.INT10 && wire == MATCH_DISPLAY);
		}

		// The wire value of a setting value as the snapshot or a profile holds it, or null when it isn't one this key can
		// carry (not a value of its kind, out of range, not a step).
		public @Nullable Integer encode(@Nullable String value) {
			String v = normalise(value);
			if (v == null) {
				return null;
			}
			return switch (kind) {
				case BOOL -> v.equalsIgnoreCase("true") ? Integer.valueOf(1) : v.equalsIgnoreCase("false") ? Integer.valueOf(0) : null;
				case ENUM -> enumIndex(v);
				case INT -> {
					Integer n = whole(v);
					yield n == null || n < min || n > max ? null : n - min;
				}
				case INT10 -> {
					Integer n = whole(v);
					yield n == null || n < min || n > max || n % 10 != 0 ? null : n / 10 - 1;
				}
				case QUARTER -> {
					BigDecimal quarters = number(v);
					if (quarters == null) {
						yield null;
					}
					quarters = quarters.multiply(BigDecimal.valueOf(4));
					if (quarters.stripTrailingZeros().scale() > 0) {
						yield null;
					}
					int q = quarters.intValueExact();
					yield q < min || q > max ? null : q - min;
				}
			};
		}

		// The setting value for a wire value (validWire). MATCH_DISPLAY resolves to refreshCap. Canonical formatting: ints as
		// digits, quarters as Double.toString ("1.0", "0.75"), booleans "true"/"false", enums as listed.
		public String decode(int wire, int refreshCap) {
			if (!validWire(wire)) {
				throw new IllegalArgumentException(key + ": wire value " + wire + " out of range");
			}
			return switch (kind) {
				case BOOL -> wire == 1 ? "true" : "false";
				case ENUM -> values.get(wire);
				case INT -> Integer.toString(wire + min);
				case INT10 -> Integer.toString(wire == MATCH_DISPLAY ? refreshCap : (wire + 1) * 10);
				case QUARTER -> Double.toString((wire + min) / 4.0);
			};
		}

		private @Nullable Integer enumIndex(String v) {
			for (int i = 0; i < values.size(); i++) {
				if (values.get(i).equals(v)) {
					return i;
				}
			}
			for (int i = 0; i < values.size(); i++) {
				if (values.get(i).equalsIgnoreCase(v)) {
					return i;
				}
			}
			return null;
		}
	}

	private static final List<String> THREE = List.of("0", "1", "2");

	public static final List<Key> V1 = List.of(
			new Key(0, "vanilla.renderDistance", Kind.INT, 2, 32, List.of(), true),
			new Key(1, "vanilla.simulationDistance", Kind.INT, 5, 32, List.of(), true),
			new Key(2, "vanilla.entityDistanceScaling", Kind.QUARTER, 2, 20, List.of(), true),
			new Key(3, "vanilla.maxFps", Kind.INT10, 10, 260, List.of(), true),
			new Key(4, "vanilla.enableVsync", Kind.BOOL, 0, 1, List.of(), true),
			new Key(5, "vanilla.inactivityFpsLimit", Kind.ENUM, 0, 0, List.of("minimized", "afk"), true),
			new Key(6, "vanilla.particles", Kind.ENUM, 0, 0, THREE, true),
			new Key(7, "vanilla.biomeBlendRadius", Kind.INT, 0, 7, List.of(), true),
			new Key(8, "vanilla.weatherRadius", Kind.INT, 3, 10, List.of(), true),
			new Key(9, "vanilla.textureFiltering", Kind.ENUM, 0, 0, THREE, true),
			new Key(10, "vanilla.renderClouds", Kind.ENUM, 0, 0, List.of("false", "fast", "true"), true),
			new Key(11, "vanilla.prioritizeChunkUpdates", Kind.ENUM, 0, 0, THREE, true),
			new Key(12, "vanilla.improvedTransparency", Kind.BOOL, 0, 1, List.of(), true),
			new Key(13, "vanilla.entityShadows", Kind.BOOL, 0, 1, List.of(), true),
			new Key(14, "vanilla.cutoutLeaves", Kind.BOOL, 0, 1, List.of(), true),
			new Key(15, "sodium.performance.use_fog_occlusion", Kind.BOOL, 0, 1, List.of(), true),
			new Key(16, "sodium.performance.use_block_face_culling", Kind.BOOL, 0, 1, List.of(), true),
			new Key(17, "sodium.performance.use_entity_culling", Kind.BOOL, 0, 1, List.of(), true),
			new Key(18, "sodium.performance.animate_only_visible_textures", Kind.BOOL, 0, 1, List.of(), true),
			new Key(19, "sodium.performance.chunk_builder_threads", Kind.INT, 0, 32, List.of(), false),
			new Key(20, "sodium.performance.chunk_build_defer_mode", Kind.ENUM, 0, 0, List.of("ALWAYS", "ONE_FRAME", "ZERO_FRAMES"), true),
			new Key(21, "sodium.performance.quad_splitting_mode", Kind.ENUM, 0, 0, List.of("OFF", "SAFE", "UNLIMITED"), true),
			new Key(22, "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", Kind.INT, 32, 512, List.of(), true),
			new Key(23, "dh.client.advanced.graphics.quality.verticalQuality", Kind.ENUM, 0, 0,
					List.of("HEIGHT_MAP", "LOW", "MEDIUM", "HIGH", "VERY_HIGH", "EXTREME", "PIXEL_ART"), true),
			new Key(24, "dh.client.advanced.graphics.quality.horizontalQuality", Kind.ENUM, 0, 0,
					List.of("LOWEST", "LOW", "MEDIUM", "HIGH", "EXTREME"), true),
			new Key(25, "dh.client.advanced.graphics.quality.maxHorizontalResolution", Kind.ENUM, 0, 0,
					List.of("CHUNK", "HALF_CHUNK", "FOUR_BLOCKS", "TWO_BLOCKS", "BLOCK"), true),
			new Key(26, "dh.common.multiThreading.numberOfThreads", Kind.INT, 1, 32, List.of(), false),
			new Key(27, "dh.client.advanced.debugging.rendererMode", Kind.ENUM, 0, 0, List.of("DEFAULT", "DISABLED"), true),
			new Key(28, "iris.maxShadowRenderDistance", Kind.INT, 0, 32, List.of(), true),
			new Key(29, "iris.enableShaders", Kind.BOOL, 0, 1, List.of(), true));

	private static final Map<String, Key> BY_KEY = new LinkedHashMap<>();

	// The keys a profile can hold, in table order.
	public static final Set<String> MANAGED;

	static {
		Set<String> managed = new LinkedHashSet<>();
		for (Key key : V1) {
			BY_KEY.put(key.key(), key);
			managed.add(key.key());
		}
		MANAGED = Set.copyOf(managed);
	}

	private ShareKeys() {
	}

	public static @Nullable Key byIndex(int index) {
		return index >= 0 && index < V1.size() ? V1.get(index) : null;
	}

	public static @Nullable Key byKey(@Nullable String key) {
		return key == null ? null : BY_KEY.get(key);
	}

	public static boolean managed(@Nullable String key) {
		return key != null && BY_KEY.containsKey(key);
	}

	// Trimmed, one pair of surrounding quotes removed; null for null or blank.
	static @Nullable String normalise(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String s = value.trim();
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
			s = s.substring(1, s.length() - 1).trim();
		}
		return s.isEmpty() ? null : s;
	}

	private static @Nullable BigDecimal number(String v) {
		if (v.length() > 16) {
			return null;
		}
		try {
			BigDecimal n = new BigDecimal(v.toLowerCase(Locale.ROOT));
			return n.abs().compareTo(BigDecimal.valueOf(1_000_000)) > 0 ? null : n;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static @Nullable Integer whole(String v) {
		BigDecimal n = number(v);
		if (n == null || n.stripTrailingZeros().scale() > 0) {
			return null;
		}
		return n.intValueExact();
	}
}
