package io.github.chaotix345.rigtune.v040.core.rules;

import com.google.gson.GsonBuilder;

// Test helper, not a pinned copy: parses a rules document the way 0.4.0's RulesLoader.parse does (GsonBuilder with the
// pinned ConditionAdapterFactory, which is package-private, then fillDefaults), for tests outside this package.
public final class V040Parser {
	private V040Parser() {
	}

	public static RulesDocument parse(String json) {
		RulesDocument doc = new GsonBuilder().registerTypeAdapterFactory(new ConditionAdapterFactory()).create().fromJson(json, RulesDocument.class);
		doc.fillDefaults();
		return doc;
	}
}
