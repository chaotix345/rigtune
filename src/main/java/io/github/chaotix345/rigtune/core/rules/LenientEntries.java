package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.github.chaotix345.rigtune.RigTune;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

// For a list section 0.5 adds (RulesDocument.stutterFixes; `@JsonAdapter(LenientEntries.class)` on a List<T> field): an
// entry this version can't read drops only itself, and a section that isn't an array becomes null (LenientSection's
// rule for a whole section), never a rejected document. Conditions inside still go through ConditionAdapterFactory.
public final class LenientEntries implements TypeAdapterFactory {
	@Override
	public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
		TypeAdapter<T> delegate = gson.getAdapter(type);
		if (type.getRawType() != List.class || !(type.getType() instanceof ParameterizedType list)) {
			return delegate;
		}
		Type elementType = list.getActualTypeArguments()[0];
		TypeAdapter<?> elements = gson.getAdapter(TypeToken.get(elementType));
		TypeAdapter<JsonElement> trees = gson.getAdapter(JsonElement.class);
		return new TypeAdapter<T>() {
			@Override
			public void write(JsonWriter out, T value) throws IOException {
				delegate.write(out, value);
			}

			@Override
			@SuppressWarnings("unchecked")
			public T read(JsonReader in) throws IOException {
				JsonElement tree = trees.read(in);
				if (tree == null || tree.isJsonNull()) {
					return null;
				}
				if (!(tree instanceof JsonArray array)) {
					RigTune.LOGGER.warn("Ignoring a rules section this version can't read ({}): not an array", type);
					return null;
				}
				List<Object> out = new ArrayList<>();
				for (JsonElement entry : array) {
					try {
						Object value = elements.fromJsonTree(entry);
						if (value != null) {
							out.add(value);
						}
					} catch (RuntimeException e) {
						RigTune.LOGGER.warn("Ignoring a rules entry this version can't read ({}): {}", elementType, e.getMessage());
					}
				}
				return (T) out;
			}
		};
	}
}
