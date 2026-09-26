package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

// Parses a Condition and records the keys this client doesn't know in Condition.unknownFields, on the node that has
// them (nested `not` and `anyOf` conditions go through this adapter too). These count as unknown as well, because
// reading them the v1 way would fail open:
// - a key with an explicit JSON null (read as an absent field);
// - a number that isn't an integer in the field's range (Gson's tree reader truncates 3.5 to 3 and wraps 2^32+1 to 1);
// - a boolean field whose value isn't a JSON boolean ("yes" would read as false);
// - a map field (modVersion, settingIs, driverVersion, the stutter share maps) that isn't an object of strings, numbers or
//   booleans (Gson would reject the whole document);
// - a string-list field (gpuVendor, os, ..., gcCollector) that isn't an array of strings, numbers, booleans or nulls
//   (v0.4, docs/v0.4/plan-review.md K-M1: Gson would reject the whole document here too).
// A condition that is itself JSON null (e.g. "recommendWhen": null) becomes a poisoned condition, not "always".
final class ConditionAdapterFactory implements TypeAdapterFactory {
	static final Map<String, Class<?>> KNOWN_KEYS = Arrays.stream(Condition.class.getFields())
			.filter(f -> !Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()))
			.collect(Collectors.toUnmodifiableMap(Field::getName, Field::getType));
	// The List<String> fields (every List but anyOf, which holds conditions).
	static final Set<String> STRING_LISTS = Arrays.stream(Condition.class.getFields())
			.filter(f -> f.getType() == List.class && f.getGenericType() instanceof ParameterizedType p
					&& p.getActualTypeArguments()[0] == String.class)
			.map(Field::getName)
			.collect(Collectors.toUnmodifiableSet());
	private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
	private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
	private static final BigDecimal LONG_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
	private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

	@Override
	@SuppressWarnings("unchecked")
	public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
		if (type.getRawType() != Condition.class) {
			return null;
		}
		TypeAdapter<Condition> delegate = gson.getDelegateAdapter(this, TypeToken.get(Condition.class));
		TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
		return (TypeAdapter<T>) new TypeAdapter<Condition>() {
			@Override
			public void write(JsonWriter out, Condition value) throws IOException {
				delegate.write(out, value);
			}

			@Override
			public Condition read(JsonReader in) throws IOException {
				JsonElement tree = elements.read(in);
				if (tree == null || tree.isJsonNull()) {
					Condition poisoned = new Condition();
					poisoned.unknownFields = Set.of("null");
					return poisoned;
				}
				if (!tree.isJsonObject()) {
					throw new JsonParseException("A condition must be a JSON object, not " + tree);
				}
				JsonObject object = (JsonObject) tree;
				Set<String> unknown = new TreeSet<>();
				for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
					Class<?> fieldType = KNOWN_KEYS.get(entry.getKey());
					if (fieldType == null || !readable(fieldType, entry.getValue())
							|| STRING_LISTS.contains(entry.getKey()) && !stringList(entry.getValue())) {
						unknown.add(entry.getKey());
					}
				}
				// Keep the unreadable values out of the delegate, which would coerce them or fail the whole document.
				JsonObject readable = object.deepCopy();
				unknown.forEach(readable::remove);
				Condition condition = delegate.fromJsonTree(readable);
				condition.unknownFields = Set.copyOf(unknown);
				return condition;
			}
		};
	}

	private static boolean readable(Class<?> fieldType, JsonElement value) {
		if (value.isJsonNull()) {
			return false;
		}
		if (fieldType == Boolean.class) {
			return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
		}
		if (fieldType == Integer.class) {
			return integral(value, INT_MIN, INT_MAX);
		}
		if (fieldType == Long.class) {
			return integral(value, LONG_MIN, LONG_MAX);
		}
		if (fieldType == Map.class) {
			return value.isJsonObject() && value.getAsJsonObject().entrySet().stream().allMatch(e -> e.getValue().isJsonPrimitive());
		}
		return true;
	}

	private static boolean stringList(JsonElement value) {
		if (!value.isJsonArray()) {
			return false;
		}
		for (JsonElement element : value.getAsJsonArray()) {
			if (!element.isJsonNull() && !element.isJsonPrimitive()) {
				return false;
			}
		}
		return true;
	}

	private static boolean integral(JsonElement value, BigDecimal min, BigDecimal max) {
		if (!value.isJsonPrimitive() || !((JsonPrimitive) value).isNumber()) {
			return false;
		}
		try {
			BigDecimal number = new BigDecimal(value.getAsString());
			return number.stripTrailingZeros().scale() <= 0 && number.compareTo(min) >= 0 && number.compareTo(max) <= 0;
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
