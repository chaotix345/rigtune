package io.github.chaotix345.rigtune.core.rules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.github.chaotix345.rigtune.RigTune;

import java.io.IOException;

// For the rules sections 0.4 adds (RulesDocument.profileTemplates, stutterAdvice; `@JsonAdapter(LenientSection.class)`):
// a section this version can't read (wrong shape, wrong value types) becomes null, which disables only that section,
// instead of Gson rejecting the whole rules document and every rules update with it (plan review K-M1's reasoning).
// Conditions inside still go through ConditionAdapterFactory, so a bad condition value poisons only that condition.
public final class LenientSection implements TypeAdapterFactory {
	@Override
	public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
		TypeAdapter<T> delegate = gson.getAdapter(type);
		TypeAdapter<JsonElement> elements = gson.getAdapter(JsonElement.class);
		return new TypeAdapter<T>() {
			@Override
			public void write(JsonWriter out, T value) throws IOException {
				delegate.write(out, value);
			}

			@Override
			public T read(JsonReader in) throws IOException {
				JsonElement tree = elements.read(in);
				if (tree == null || tree.isJsonNull()) {
					return null;
				}
				try {
					return delegate.fromJsonTree(tree);
				} catch (RuntimeException e) {
					RigTune.LOGGER.warn("Ignoring a rules section this version can't read ({}): {}", type, e.getMessage());
					return null;
				}
			}
		};
	}
}
