package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public final class SodiumConfigPatcher {
	private static final Pattern INTEGER = Pattern.compile("-?\\d+");
	private static final Pattern DECIMAL = Pattern.compile("-?\\d+\\.\\d+");

	private SodiumConfigPatcher() {
	}

	public static JsonObject patch(JsonObject root, Map<String, String> patches) {
		JsonObject out = root.deepCopy();
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			set(out, entry.getKey(), entry.getValue());
		}
		return out;
	}

	private static void set(JsonObject root, String dottedPath, String value) {
		String[] parts = dottedPath.split("\\.");
		JsonObject node = root;
		for (int i = 0; i < parts.length - 1; i++) {
			JsonElement child = node.get(parts[i]);
			if (child == null || child.isJsonNull()) {
				JsonObject created = new JsonObject();
				node.add(parts[i], created);
				node = created;
			} else if (child.isJsonObject()) {
				node = child.getAsJsonObject();
			} else {
				throw new IllegalArgumentException("Cannot set " + dottedPath + ": " + parts[i] + " is not an object");
			}
		}
		String leaf = parts[parts.length - 1];
		node.add(leaf, convert(node.get(leaf), value));
	}

	private static JsonPrimitive convert(JsonElement existing, String value) {
		if (existing != null && existing.isJsonPrimitive() && existing.getAsJsonPrimitive().isString()) {
			return new JsonPrimitive(value);
		}
		if (value.equals("true") || value.equals("false")) {
			return new JsonPrimitive(Boolean.parseBoolean(value));
		}
		if (INTEGER.matcher(value).matches()) {
			try {
				return new JsonPrimitive(Long.parseLong(value));
			} catch (NumberFormatException e) {
				return new JsonPrimitive(new BigDecimal(value));
			}
		}
		if (DECIMAL.matcher(value).matches()) {
			return new JsonPrimitive(new BigDecimal(value));
		}
		return new JsonPrimitive(value);
	}

	// A missing file is created from an empty object; Sodium fills in defaults for absent fields.
	public static boolean patchFile(Path file, Map<String, String> patches) throws IOException {
		JsonObject root = Files.exists(file)
				? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
				: new JsonObject();
		JsonObject patched = patch(root, patches);
		if (patched.equals(root) && Files.exists(file)) {
			return false;
		}
		AtomicFiles.writeString(file, PendingActions.GSON.toJson(patched));
		return true;
	}

	public static Map<String, String> flatten(JsonObject obj, String prefix) {
		Map<String, String> out = new LinkedHashMap<>();
		flattenInto(obj, prefix, out);
		return out;
	}

	private static void flattenInto(JsonObject obj, String prefix, Map<String, String> out) {
		for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
			String key = prefix + entry.getKey();
			JsonElement value = entry.getValue();
			if (value.isJsonObject()) {
				flattenInto(value.getAsJsonObject(), key + ".", out);
			} else if (value.isJsonPrimitive()) {
				out.put(key, value.getAsString());
			}
		}
	}
}
