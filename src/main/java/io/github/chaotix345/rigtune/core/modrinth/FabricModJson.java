package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// docs/v0.5/SPEC.md 2H L5: a download's fabric.mod.json, read in memory (RangeReader), as Apply reads it from the downloaded
// jar: the id, name and version as ModJars reads them, the jar's own `provides` (JarInfo), its `depends` and `breaks` ranges
// (ModJars.rangesOf), and whether it nests jars, whose ids a read of this one entry doesn't know.
public record FabricModJson(String id, @Nullable String name, @Nullable String version, Set<String> provides, Map<String, List<String>> depends,
		Map<String, List<String>> breaks, boolean nestsJars) {
	public FabricModJson {
		provides = Set.copyOf(provides);
		depends = Map.copyOf(depends);
		breaks = Map.copyOf(breaks);
	}

	// Null where Apply's ModJars.modIdOf reads no id: the jar is "not a Fabric mod jar" there too.
	public static @Nullable FabricModJson parse(byte[] json) {
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
			if (!parsed.isJsonObject()) {
				return null;
			}
			root = parsed.getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
		String id = field(root, "id");
		if (id == null) {
			return null;
		}
		Set<String> provides = new HashSet<>();
		if (root.get("provides") instanceof JsonArray array) {
			array.forEach(e -> {
				if (e.isJsonPrimitive()) {
					provides.add(e.getAsString());
				}
			});
		}
		return new FabricModJson(id, ModJars.sanitizeName(field(root, "name")), field(root, "version"), provides, ranges(root, "depends"),
				ranges(root, "breaks"), root.get("jars") instanceof JsonArray jars && !jars.isEmpty());
	}

	private static @Nullable String field(JsonObject root, String name) {
		JsonElement value = root.get(name);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static Map<String, List<String>> ranges(JsonObject root, String section) {
		if (!(root.get(section) instanceof JsonObject ranges)) {
			return Map.of();
		}
		Map<String, List<String>> out = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> e : ranges.entrySet()) {
			List<String> list = new ArrayList<>();
			if (e.getValue() instanceof JsonArray array) {
				array.forEach(range -> {
					if (range.isJsonPrimitive()) {
						list.add(range.getAsString());
					}
				});
			} else if (e.getValue().isJsonPrimitive()) {
				list.add(e.getValue().getAsString());
			}
			out.put(e.getKey(), List.copyOf(list));
		}
		return out;
	}
}
