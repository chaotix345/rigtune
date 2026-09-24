package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class Json {
	private Json() {
	}

	static String string(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	static long number(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e == null || e.isJsonNull() ? 0 : e.getAsLong();
	}

	static boolean bool(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e != null && !e.isJsonNull() && e.getAsBoolean();
	}

	static JsonObject object(JsonObject obj, String key) {
		JsonElement e = obj.get(key);
		return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
	}

	static List<String> strings(JsonObject obj, String key) {
		return list(obj, key, JsonElement::getAsString);
	}

	static <T> List<T> objects(JsonObject obj, String key, Function<JsonObject, T> mapper) {
		return list(obj, key, e -> mapper.apply(e.getAsJsonObject()));
	}

	private static <T> List<T> list(JsonObject obj, String key, Function<JsonElement, T> mapper) {
		JsonElement e = obj.get(key);
		if (e == null || !e.isJsonArray()) {
			return List.of();
		}
		JsonArray array = e.getAsJsonArray();
		List<T> out = new ArrayList<>(array.size());
		for (JsonElement item : array) {
			if (!item.isJsonNull()) {
				out.add(mapper.apply(item));
			}
		}
		return List.copyOf(out);
	}
}
