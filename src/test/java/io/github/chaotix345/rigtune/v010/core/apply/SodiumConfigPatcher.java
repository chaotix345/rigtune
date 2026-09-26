package io.github.chaotix345.rigtune.v010.core.apply;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
		node.add(leaf, convert(dottedPath, node.get(leaf), value));
	}

	// An existing field keeps its JSON type: Sodium rejects the whole file (and falls back to defaults) when a
	// field has the wrong type. A value that doesn't fit fails the patch. New fields get an inferred type.
	private static JsonPrimitive convert(String dottedPath, JsonElement existing, String value) {
		if (value == null) {
			throw new IllegalArgumentException("Cannot set " + dottedPath + ": no value");
		}
		if (existing == null || existing.isJsonNull()) {
			Boolean bool = parseBoolean(value);
			if (bool != null) {
				return new JsonPrimitive(bool);
			}
			JsonPrimitive number = parseNumber(value);
			return number != null ? number : new JsonPrimitive(value);
		}
		if (!existing.isJsonPrimitive()) {
			throw new IllegalArgumentException("Cannot set " + dottedPath + ": it holds " + (existing.isJsonObject() ? "an object" : "a list"));
		}
		JsonPrimitive current = existing.getAsJsonPrimitive();
		if (current.isBoolean()) {
			Boolean bool = parseBoolean(value);
			if (bool == null) {
				throw new IllegalArgumentException("Cannot set " + dottedPath + " to \"" + value + "\": it expects true or false");
			}
			return new JsonPrimitive(bool);
		}
		if (current.isNumber()) {
			JsonPrimitive number = parseNumber(value);
			if (number == null) {
				throw new IllegalArgumentException("Cannot set " + dottedPath + " to \"" + value + "\": it expects a number");
			}
			if (integral(current) && !integral(number)) {
				throw new IllegalArgumentException("Cannot set " + dottedPath + " to \"" + value + "\": it expects a whole number");
			}
			return number;
		}
		return new JsonPrimitive(value);
	}

	private static Boolean parseBoolean(String value) {
		if (value.equalsIgnoreCase("true")) {
			return true;
		}
		return value.equalsIgnoreCase("false") ? false : null;
	}

	private static JsonPrimitive parseNumber(String value) {
		if (INTEGER.matcher(value).matches()) {
			try {
				return new JsonPrimitive(Long.parseLong(value));
			} catch (NumberFormatException e) {
				return new JsonPrimitive(new BigDecimal(value));
			}
		}
		return DECIMAL.matcher(value).matches() ? new JsonPrimitive(new BigDecimal(value)) : null;
	}

	private static boolean integral(JsonPrimitive number) {
		String text = number.getAsString();
		return text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0;
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

	public record Staged(List<PendingActions.Op> ops, Map<String, String> refused) {
	}

	// One PATCH_JSON op per key, so a value that stops fitting later fails alone. Each value is first checked against
	// the file as it is now, with the rules patchFile uses; one that doesn't fit is refused (key -> reason) instead of
	// failing at every exit. A missing file accepts anything, since new fields get an inferred type.
	public static Staged stage(Path file, Map<String, String> patches) {
		JsonObject root;
		try {
			root = Files.exists(file) ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
		} catch (IOException | RuntimeException e) {
			Map<String, String> refused = new LinkedHashMap<>();
			patches.keySet().forEach(key -> refused.put(key, "can't read " + file.getFileName() + ": " + e.getMessage()));
			return new Staged(List.of(), refused);
		}
		List<PendingActions.Op> ops = new ArrayList<>();
		Map<String, String> refused = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : patches.entrySet()) {
			Map<String, String> single = Collections.singletonMap(entry.getKey(), entry.getValue());
			try {
				patch(root, single);
				ops.add(PendingActions.Op.patchJson(file, single));
			} catch (IllegalArgumentException e) {
				refused.put(entry.getKey(), e.getMessage());
			}
		}
		return new Staged(List.copyOf(ops), refused);
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
