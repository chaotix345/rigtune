package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

// Parses a Condition and records the keys this client doesn't know in Condition.unknownFields, on the node that has
// them (nested `not` and `anyOf` conditions go through this adapter too). A key with an explicit JSON null counts as
// unknown: v1 would read it as an absent field, which fails open.
final class ConditionAdapterFactory implements TypeAdapterFactory {
	static final Set<String> KNOWN_KEYS = Arrays.stream(Condition.class.getFields())
			.filter(f -> !Modifier.isStatic(f.getModifiers()) && !Modifier.isTransient(f.getModifiers()))
			.map(Field::getName)
			.collect(Collectors.toUnmodifiableSet());

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
					return null;
				}
				if (!tree.isJsonObject()) {
					throw new JsonParseException("A condition must be a JSON object, not " + tree);
				}
				Set<String> unknown = new TreeSet<>();
				for (Map.Entry<String, JsonElement> entry : ((JsonObject) tree).entrySet()) {
					if (!KNOWN_KEYS.contains(entry.getKey()) || entry.getValue().isJsonNull()) {
						unknown.add(entry.getKey());
					}
				}
				Condition condition = delegate.fromJsonTree(tree);
				condition.unknownFields = Set.copyOf(unknown);
				return condition;
			}
		};
	}
}
