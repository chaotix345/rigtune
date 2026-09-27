package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.store.StateStore;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

// config/rigtune/awareness.json (docs/v0.4/SPEC.md C1, items 7 and 9; plan review X-M1): one process-wide instance per
// file whose update() re-reads under a lock, because several threads write it (notice dismissals on the render thread,
// the hardware fingerprint after the probe, acknowledged regressions after a benchmark). Shape:
// {"formatVersion": 1,
//  "fingerprint": {"gpuVendor", "gpuRenderer", "gpuDriverRaw", "backend", "cpuName", "totalRamMb"},  absent = first run
//  "lastSeenRulesRevision": 13, "lastSeenRecommendationIds": ["set:vanilla.renderDistance", ...],  absent = no baseline
//  "dismissed": ["<notice key>", ...], "acknowledgedRegressions": ["<benchmark run id>", ...]}      always present
// Feature workstreams add typed accessors here; the contracts commit implements only the notice dismissals.
// v0.5 (docs/v0.5/SPEC.md C1; optional, absent until first written; 0.4.0 keeps them on its rewrites):
//  "acknowledgedStartupRegressions": ["<startup regression key>", ...]   C18
//  "optionsAtExit": {"<vanilla key>": "<value>", ...}                     4h: the snapshot at a clean exit, consumed at the next start
public final class AwarenessStore {
	public static final String FILE_NAME = "awareness.json";
	public static final long MAX_BYTES = 64 * 1024;
	public static final int MAX_DISMISSED = 256;
	public static final int MAX_ACKNOWLEDGED = 64;
	public static final String FINGERPRINT = "fingerprint";
	public static final String FINGERPRINT_GPU_VENDOR = "gpuVendor";
	public static final String FINGERPRINT_GPU_RENDERER = "gpuRenderer";
	public static final String FINGERPRINT_GPU_DRIVER_RAW = "gpuDriverRaw";
	public static final String FINGERPRINT_BACKEND = "backend";
	public static final String FINGERPRINT_CPU_NAME = "cpuName";
	public static final String FINGERPRINT_TOTAL_RAM_MB = "totalRamMb";
	public static final String LAST_SEEN_RULES_REVISION = "lastSeenRulesRevision";
	public static final String LAST_SEEN_RECOMMENDATION_IDS = "lastSeenRecommendationIds";
	public static final String DISMISSED = "dismissed";
	public static final String ACKNOWLEDGED_REGRESSIONS = "acknowledgedRegressions";
	public static final String ACKNOWLEDGED_STARTUP_REGRESSIONS = "acknowledgedStartupRegressions";
	public static final String OPTIONS_AT_EXIT = "optionsAtExit";
	public static final int MAX_OPTIONS_AT_EXIT = 64;

	private static final Map<Path, AwarenessStore> SHARED = new HashMap<>();

	private final StateStore store;

	private AwarenessStore(Path file) {
		this.store = new StateStore(file, MAX_BYTES, AwarenessStore::withDefaults);
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public static synchronized AwarenessStore shared(Path configDir) {
		return SHARED.computeIfAbsent(file(configDir).toAbsolutePath().normalize(), AwarenessStore::new);
	}

	private static JsonObject withDefaults(JsonObject root) {
		if (!(root.get(DISMISSED) instanceof JsonArray)) {
			root.add(DISMISSED, new JsonArray());
		}
		if (!(root.get(ACKNOWLEDGED_REGRESSIONS) instanceof JsonArray)) {
			root.add(ACKNOWLEDGED_REGRESSIONS, new JsonArray());
		}
		return root;
	}

	public Path file() {
		return store.file();
	}

	public JsonObject read() {
		return store.read();
	}

	public boolean update(UnaryOperator<JsonObject> change) {
		return store.update(change);
	}

	// False when the file is from a newer RigTune or can't be read: update() then writes nothing.
	public boolean writable() {
		return store.writable();
	}

	// C3: the notice keys the player dismissed.
	public Set<String> dismissed() {
		Set<String> out = new LinkedHashSet<>();
		if (read().get(DISMISSED) instanceof JsonArray keys) {
			for (JsonElement key : keys) {
				if (key.isJsonPrimitive()) {
					out.add(key.getAsString());
				}
			}
		}
		return out;
	}

	// Remembers a dismissal (the newest MAX_DISMISSED are kept).
	public boolean dismiss(String key) {
		return update(root -> {
			JsonArray keys = root.getAsJsonArray(DISMISSED);
			for (int i = keys.size() - 1; i >= 0; i--) {
				if (keys.get(i).isJsonPrimitive() && keys.get(i).getAsString().equals(key)) {
					keys.remove(i);
				}
			}
			keys.add(key);
			while (keys.size() > MAX_DISMISSED) {
				keys.remove(0);
			}
			return root;
		});
	}

	// docs/v0.4/SPEC.md 7: the benchmark runs whose regression notice the player acknowledged.
	public Set<String> acknowledgedRegressions() {
		Set<String> out = new LinkedHashSet<>();
		if (read().get(ACKNOWLEDGED_REGRESSIONS) instanceof JsonArray ids) {
			for (JsonElement id : ids) {
				if (id.isJsonPrimitive()) {
					out.add(id.getAsString());
				}
			}
		}
		return out;
	}

	// Remembers an acknowledged regression (the newest MAX_ACKNOWLEDGED are kept; benchmarks.json keeps 50 runs).
	public boolean acknowledgeRegression(String runId) {
		return update(root -> {
			JsonArray ids = root.getAsJsonArray(ACKNOWLEDGED_REGRESSIONS);
			for (int i = ids.size() - 1; i >= 0; i--) {
				if (ids.get(i).isJsonPrimitive() && ids.get(i).getAsString().equals(runId)) {
					ids.remove(i);
				}
			}
			ids.add(runId);
			while (ids.size() > MAX_ACKNOWLEDGED) {
				ids.remove(0);
			}
			return root;
		});
	}

	// v0.5 C18: the startup regressions (by notice key) the player acknowledged.
	public Set<String> acknowledgedStartupRegressions() {
		Set<String> out = new LinkedHashSet<>();
		if (read().get(ACKNOWLEDGED_STARTUP_REGRESSIONS) instanceof JsonArray keys) {
			for (JsonElement key : keys) {
				if (key.isJsonPrimitive()) {
					out.add(key.getAsString());
				}
			}
		}
		return out;
	}

	// Remembers an acknowledged startup regression (the newest MAX_ACKNOWLEDGED are kept, like acknowledgeRegression).
	public boolean acknowledgeStartupRegression(String key) {
		return update(root -> {
			JsonArray keys = root.get(ACKNOWLEDGED_STARTUP_REGRESSIONS) instanceof JsonArray existing ? existing : new JsonArray();
			for (int i = keys.size() - 1; i >= 0; i--) {
				if (keys.get(i).isJsonPrimitive() && keys.get(i).getAsString().equals(key)) {
					keys.remove(i);
				}
			}
			keys.add(key);
			while (keys.size() > MAX_ACKNOWLEDGED) {
				keys.remove(0);
			}
			root.add(ACKNOWLEDGED_STARTUP_REGRESSIONS, keys);
			return root;
		});
	}

	// v0.5 4h: the snapshot stored at the last clean exit (empty when there is none). Values that aren't strings are left
	// out; at most MAX_OPTIONS_AT_EXIT keys.
	public Map<String, String> optionsAtExit() {
		return options(read().get(OPTIONS_AT_EXIT));
	}

	// Stores the snapshot (the first MAX_OPTIONS_AT_EXIT keys; null values left out), replacing the previous one.
	public boolean setOptionsAtExit(Map<String, String> values) {
		return update(root -> {
			JsonObject snapshot = new JsonObject();
			for (Map.Entry<String, String> entry : values.entrySet()) {
				if (snapshot.size() >= MAX_OPTIONS_AT_EXIT) {
					break;
				}
				if (entry.getKey() != null && entry.getValue() != null) {
					snapshot.addProperty(entry.getKey(), entry.getValue());
				}
			}
			root.add(OPTIONS_AT_EXIT, snapshot);
			return root;
		});
	}

	// The consuming read: the snapshot, removed from the file in the same update, so it is compared once. Null when there
	// is no snapshot, or when it couldn't be removed (a newer or unreadable file), so a start never compares one twice.
	public @Nullable Map<String, String> takeOptionsAtExit() {
		if (!read().has(OPTIONS_AT_EXIT)) {
			return null;
		}
		AtomicReference<JsonElement> taken = new AtomicReference<>();
		boolean written = update(root -> {
			taken.set(root.remove(OPTIONS_AT_EXIT));
			return root;
		});
		return written && taken.get() != null ? options(taken.get()) : null;
	}

	private static Map<String, String> options(@Nullable JsonElement element) {
		Map<String, String> out = new LinkedHashMap<>();
		if (element instanceof JsonObject snapshot) {
			for (Map.Entry<String, JsonElement> entry : snapshot.entrySet()) {
				if (out.size() >= MAX_OPTIONS_AT_EXIT) {
					break;
				}
				if (entry.getValue() instanceof JsonPrimitive value && value.isString()) {
					out.put(entry.getKey(), value.getAsString());
				}
			}
		}
		return out;
	}
}
