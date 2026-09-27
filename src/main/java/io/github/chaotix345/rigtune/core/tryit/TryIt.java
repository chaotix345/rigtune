package io.github.chaotix345.rigtune.core.tryit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.profile.ShareKeys;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

// One Try it (docs/v0.5/SPEC.md 6, docs/research/v0.5/feature-try-it.md §2.4): what tryit.json's `current` holds, the
// identity only. The stage is never stored: TryItFlow derives it from this, the pair in benchmarks.json and the entry
// in history.json. id: "t-<uuid>"; pairId: "tryit-<uuid>", the Measure pair's id (BenchmarkMenuScreen's "Measure after"
// skips that prefix); entryId: the History entry the change is journaled under; key/from/to: the setting and its values;
// session: the game session that started it; settingsBefore: the managed settings at Start; settingsAfter and
// afterSession: the managed settings and the session when the newest after run started (null before one has);
// afterRunId: the after run whose verdict was shown (its regression notice acknowledged). A try that's closed moves to
// `recent` as a Closed row.
public record TryIt(String id, String pairId, String entryId, @Nullable String recommendationId, String key, @Nullable String from,
		@Nullable String to, Kind kind, BenchmarkRequest.Scene scene, @Nullable String startedAt, String session, @Nullable String rigtuneVersion,
		@Nullable String mcVersion, Map<String, String> settingsBefore, @Nullable Map<String, String> settingsAfter, @Nullable String afterSession,
		@Nullable String afterRunId) {
	public static final String ID_PREFIX = "t-";
	public static final String PAIR_PREFIX = "tryit-";

	// NOW: applies at once (vanilla); RESTART: staged for the helper, in effect after a restart (Sodium, DH, Iris).
	public enum Kind {
		NOW, RESTART;

		String json() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	// How a try was closed.
	public enum Decision {
		KEPT, REVERTED, CANCELLED, FAILED;

		String json() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	// A closed try in `recent`: the change, the verdict it closed with (null when it had none) and the decision.
	public record Closed(String id, String key, @Nullable String from, @Nullable String to, @Nullable String verdict, @Nullable Double lowPercent,
			@Nullable Double avgPercent, @Nullable Double floorPercent, Decision decision, String at) {
		public static Closed of(TryIt t, Decision decision, @Nullable String verdict, @Nullable Double lowPercent, @Nullable Double avgPercent,
				@Nullable Double floorPercent, String at) {
			return new Closed(t.id(), t.key(), t.from(), t.to(), verdict, lowPercent, avgPercent, floorPercent, decision, at);
		}

		JsonObject toJson() {
			JsonObject out = new JsonObject();
			put(out, "id", id);
			put(out, "key", key);
			put(out, "from", from);
			put(out, "to", to);
			put(out, "verdict", verdict);
			put(out, "lowPercent", lowPercent);
			put(out, "avgPercent", avgPercent);
			put(out, "floorPercent", floorPercent);
			put(out, "decision", decision.json());
			put(out, "at", at);
			return out;
		}

		// Null when the row isn't one (a hand edit): an id, a key, a known decision and a time are needed.
		static @Nullable Closed fromJson(@Nullable JsonElement element) {
			if (!(element instanceof JsonObject o)) {
				return null;
			}
			String id = text(o, "id");
			String key = text(o, "key");
			Decision decision = parseDecision(text(o, "decision"));
			String at = text(o, "at");
			if (blank(id) || blank(key) || decision == null || blank(at)) {
				return null;
			}
			return new Closed(id, key, text(o, "from"), text(o, "to"), text(o, "verdict"), number(o, "lowPercent"), number(o, "avgPercent"),
					number(o, "floorPercent"), decision, at);
		}
	}

	public TryIt {
		settingsBefore = managed(settingsBefore);
		settingsAfter = settingsAfter == null ? null : managed(settingsAfter);
	}

	// A new try for the setting key (from -> to), with new ids.
	public static TryIt of(String entryId, @Nullable String recommendationId, String key, @Nullable String from, @Nullable String to, Kind kind,
			BenchmarkRequest.Scene scene, @Nullable String startedAt, String session, @Nullable String rigtuneVersion, @Nullable String mcVersion,
			Map<String, String> settingsBefore) {
		return new TryIt(ID_PREFIX + UUID.randomUUID(), PAIR_PREFIX + UUID.randomUUID(), entryId, recommendationId, key, from, to, kind, scene,
				startedAt, session, rigtuneVersion, mcVersion, settingsBefore, null, null, null);
	}

	// When an after run starts: the managed settings then and that session.
	public TryIt withAfter(Map<String, String> settings, String session) {
		return new TryIt(id, pairId, entryId, recommendationId, key, from, to, kind, scene, startedAt, this.session, rigtuneVersion, mcVersion,
				settingsBefore, settings, session, afterRunId);
	}

	public TryIt withAfterRun(String runId) {
		return new TryIt(id, pairId, entryId, recommendationId, key, from, to, kind, scene, startedAt, session, rigtuneVersion, mcVersion,
				settingsBefore, settingsAfter, afterSession, runId);
	}

	// previous: this try's object as it is in the file, whose fields (and snapshot entries) this version doesn't know are
	// kept. A managed key missing from a snapshot is removed from it.
	JsonObject toJson(JsonObject previous) {
		JsonObject out = previous.deepCopy();
		put(out, "id", id);
		put(out, "pairId", pairId);
		put(out, "entryId", entryId);
		put(out, "recommendationId", recommendationId);
		put(out, "key", key);
		put(out, "from", from);
		put(out, "to", to);
		put(out, "kind", kind.json());
		put(out, "scene", scene.name());
		put(out, "startedAt", startedAt);
		put(out, "session", session);
		put(out, "rigtuneVersion", rigtuneVersion);
		put(out, "mcVersion", mcVersion);
		out.add("settingsBefore", snapshot(out.get("settingsBefore"), settingsBefore));
		if (settingsAfter == null) {
			out.remove("settingsAfter");
		} else {
			out.add("settingsAfter", snapshot(out.get("settingsAfter"), settingsAfter));
		}
		put(out, "afterSession", afterSession);
		put(out, "afterRunId", afterRunId);
		return out;
	}

	// Null when the object isn't a usable try (a hand edit): the ids, the key, the kind, the scene and the session are
	// needed, and the pair id must carry PAIR_PREFIX. Other values of the wrong type read as absent.
	static @Nullable TryIt fromJson(@Nullable JsonElement element) {
		if (!(element instanceof JsonObject o)) {
			return null;
		}
		String id = text(o, "id");
		String pairId = text(o, "pairId");
		String entryId = text(o, "entryId");
		String key = text(o, "key");
		Kind kind = parseKind(text(o, "kind"));
		BenchmarkRequest.Scene scene = parseScene(text(o, "scene"));
		String session = text(o, "session");
		if (blank(id) || pairId == null || !pairId.startsWith(PAIR_PREFIX) || blank(entryId) || blank(key) || kind == null || scene == null
				|| blank(session)) {
			return null;
		}
		Map<String, String> after = o.get("settingsAfter") instanceof JsonObject ? strings(o.get("settingsAfter")) : null;
		return new TryIt(id, pairId, entryId, text(o, "recommendationId"), key, text(o, "from"), text(o, "to"), kind, scene, text(o, "startedAt"),
				session, text(o, "rigtuneVersion"), text(o, "mcVersion"), strings(o.get("settingsBefore")), after, text(o, "afterSession"),
				text(o, "afterRunId"));
	}

	// Only ShareKeys.MANAGED keys with a value a setting can have (docs/v0.5/SPEC.md 6: the snapshots are limited to them).
	private static Map<String, String> managed(@Nullable Map<String, String> values) {
		Map<String, String> out = new LinkedHashMap<>();
		if (values != null) {
			values.forEach((k, v) -> {
				if (ShareKeys.managed(k) && SettingKeys.safeValue(v)) {
					out.put(k, v);
				}
			});
		}
		return Collections.unmodifiableMap(out);
	}

	private static JsonObject snapshot(@Nullable JsonElement previous, Map<String, String> values) {
		JsonObject out = previous instanceof JsonObject o ? o.deepCopy() : new JsonObject();
		for (String k : ShareKeys.MANAGED) {
			if (!values.containsKey(k)) {
				out.remove(k);
			}
		}
		values.forEach(out::addProperty);
		return out;
	}

	private static Map<String, String> strings(@Nullable JsonElement element) {
		Map<String, String> out = new LinkedHashMap<>();
		if (element instanceof JsonObject o) {
			o.entrySet().forEach(e -> {
				if (e.getValue() instanceof JsonPrimitive p && p.isString()) {
					out.put(e.getKey(), p.getAsString());
				}
			});
		}
		return out;
	}

	static @Nullable String text(JsonObject o, String name) {
		return o.get(name) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
	}

	private static @Nullable Double number(JsonObject o, String name) {
		if (o.get(name) instanceof JsonPrimitive p && p.isNumber()) {
			double value = p.getAsDouble();
			return Double.isFinite(value) ? value : null;
		}
		return null;
	}

	private static boolean blank(@Nullable String value) {
		return value == null || value.isBlank();
	}

	private static void put(JsonObject o, String name, @Nullable Object value) {
		switch (value) {
			case null -> o.remove(name);
			case String s -> o.addProperty(name, s);
			case Number n -> o.addProperty(name, n);
			default -> throw new IllegalArgumentException(name);
		}
	}

	private static @Nullable Kind parseKind(@Nullable String value) {
		for (Kind k : Kind.values()) {
			if (k.json().equals(value)) {
				return k;
			}
		}
		return null;
	}

	private static @Nullable Decision parseDecision(@Nullable String value) {
		for (Decision d : Decision.values()) {
			if (d.json().equals(value)) {
				return d;
			}
		}
		return null;
	}

	private static BenchmarkRequest.@Nullable Scene parseScene(@Nullable String value) {
		for (BenchmarkRequest.Scene s : BenchmarkRequest.Scene.values()) {
			if (s.name().equals(value)) {
				return s;
			}
		}
		return null;
	}
}
