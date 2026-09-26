package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.store.StateStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

// config/rigtune/awareness.json (docs/v0.4/SPEC.md C1, items 7 and 9; plan review X-M1): one process-wide instance per
// file whose update() re-reads under a lock, because several threads write it (notice dismissals on the render thread,
// the hardware fingerprint after the probe, acknowledged regressions after a benchmark). Shape:
// {"formatVersion": 1,
//  "fingerprint": {"gpuVendor", "gpuRenderer", "gpuDriverRaw", "backend", "cpuName", "totalRamMb"},  absent = first run
//  "lastSeenRulesRevision": 13, "lastSeenRecommendationIds": ["set:vanilla.renderDistance", ...],  absent = no baseline
//  "dismissed": ["<notice key>", ...], "acknowledgedRegressions": ["<benchmark run id>", ...]}      always present
// Feature workstreams add typed accessors here; the contracts commit implements only the notice dismissals.
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
}
