package io.github.chaotix345.rigtune.core.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.store.StateStore;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

// config/rigtune/profiles.json (docs/v0.4/SPEC.md C1, item 4; plan review X-M1): one process-wide instance per file whose
// update() re-reads under a lock, because profile saves/switches (render thread) and the battery prompt ("RigTune power"
// thread) both write it. Unknown top-level and per-profile fields survive (the root stays a JsonObject). Shape:
// {"formatVersion": 1,
//  "profiles": [{"id": "p-<uuid>", "name", "templateId", "source": "baseline|saved|imported", "createdAt", "rigtuneVersion",
//                "mcVersion", "settings": {"vanilla.renderDistance": "12", ...}}]               (<= MAX_PROFILES)
//  "active": "p-<uuid>" | "template:<id>" | absent,
//  "switches": [{"entryId": "<journal entry id>", "profileId", "templateId", "name": "Battery"}]   (<= MAX_SWITCHES)
//  "battery": {"prompt": true, "previousProfile": null, "lastPromptAt": null, "snoozed": false}}
// profiles, switches and battery (prompt, snoozed) are always present. Feature workstreams (WS-P) add typed accessors.
public final class ProfileStore {
	public static final String FILE_NAME = "profiles.json";
	public static final long MAX_BYTES = 1024 * 1024;
	public static final int MAX_PROFILES = 50;
	public static final int MAX_SWITCHES = 50;
	public static final String PROFILES = "profiles";
	public static final String ACTIVE = "active";
	public static final String SWITCHES = "switches";
	public static final String BATTERY = "battery";
	public static final String BATTERY_PROMPT = "prompt";
	public static final String BATTERY_PREVIOUS_PROFILE = "previousProfile";
	public static final String BATTERY_LAST_PROMPT_AT = "lastPromptAt";
	public static final String BATTERY_SNOOZED = "snoozed";

	private static final Map<Path, ProfileStore> SHARED = new HashMap<>();

	private final StateStore store;

	private ProfileStore(Path file) {
		this.store = new StateStore(file, MAX_BYTES, ProfileStore::withDefaults);
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public static synchronized ProfileStore shared(Path configDir) {
		return SHARED.computeIfAbsent(file(configDir).toAbsolutePath().normalize(), ProfileStore::new);
	}

	private static JsonObject withDefaults(JsonObject root) {
		if (!(root.get(PROFILES) instanceof JsonArray)) {
			root.add(PROFILES, new JsonArray());
		}
		if (!(root.get(SWITCHES) instanceof JsonArray)) {
			root.add(SWITCHES, new JsonArray());
		}
		if (!(root.get(BATTERY) instanceof JsonObject)) {
			root.add(BATTERY, new JsonObject());
		}
		JsonObject battery = root.getAsJsonObject(BATTERY);
		if (!battery.has(BATTERY_PROMPT)) {
			battery.addProperty(BATTERY_PROMPT, true);
		}
		if (!battery.has(BATTERY_SNOOZED)) {
			battery.addProperty(BATTERY_SNOOZED, false);
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
}
