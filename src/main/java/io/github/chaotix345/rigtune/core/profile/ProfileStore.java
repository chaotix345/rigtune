package io.github.chaotix345.rigtune.core.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.model.SettingKeys;
import io.github.chaotix345.rigtune.core.store.StateStore;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

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

	// WS-P typed accessors (docs/v0.4/SPEC.md 4, AC4.7). The file is untrusted (players edit it): every read type-checks,
	// ids must look like ours, names are sanitised again, settings keep only managed keys with safe values. Writes change
	// only the members they own, so unknown fields at any depth survive.

	public static final String ID = "id";
	public static final String NAME = "name";
	public static final String TEMPLATE_ID = "templateId";
	public static final String SOURCE = "source";
	public static final String CREATED_AT = "createdAt";
	public static final String RIGTUNE_VERSION = "rigtuneVersion";
	public static final String MC_VERSION = "mcVersion";
	public static final String SETTINGS = "settings";
	public static final String ENTRY_ID = "entryId";
	public static final String ACTIVE_ENTRY = "activeEntry";
	public static final String PROFILE_ID = "profileId";
	public static final String SOURCE_BASELINE = "baseline";
	public static final String SOURCE_SAVED = "saved";
	public static final String SOURCE_IMPORTED = "imported";
	public static final String TEMPLATE_PREFIX = "template:";
	private static final Pattern PROFILE_ID_SHAPE = Pattern.compile("p-[A-Za-z0-9-]{1,64}");
	private static final Pattern ENTRY_ID_SHAPE = Pattern.compile("[A-Za-z0-9._:-]{1,80}");
	private static final Pattern TEMPLATE_ID_SHAPE = Pattern.compile("[a-z_]{1,32}");

	// name: sanitised, null when nothing is left (shown as "Unnamed profile"). settings: managed keys only.
	public record Profile(String id, @Nullable String name, @Nullable String templateId, String source, @Nullable String createdAt,
			@Nullable String rigtuneVersion, @Nullable String mcVersion, Map<String, String> settings) {
		public Profile {
			settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
		}
	}

	// A journal entry that was a profile switch, and the label History shows for it.
	public record Switch(String entryId, @Nullable String profileId, @Nullable String templateId, @Nullable String name) {
	}

	public record Battery(boolean prompt, @Nullable String previousProfile, @Nullable String lastPromptAt, boolean snoozed) {
	}

	public static String newProfileId() {
		return "p-" + UUID.randomUUID();
	}

	public List<Profile> profiles() {
		return profiles(read());
	}

	public @Nullable Profile profile(String id) {
		return profiles().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
	}

	public @Nullable Profile baseline() {
		return profiles().stream().filter(p -> SOURCE_BASELINE.equals(p.source())).findFirst().orElse(null);
	}

	// Adds the profile, or replaces the one with its id (its unknown fields kept). A new profile over MAX_PROFILES is refused;
	// a baseline replaces any older baseline.
	public boolean saveProfile(Profile profile) {
		if (!PROFILE_ID_SHAPE.matcher(profile.id()).matches()) {
			return false;
		}
		boolean[] refused = new boolean[1];
		boolean written = update(root -> {
			JsonArray list = root.getAsJsonArray(PROFILES);
			JsonObject existing = null;
			for (int i = list.size() - 1; i >= 0; i--) {
				if (!(list.get(i) instanceof JsonObject object)) {
					continue;
				}
				if (profile.id().equals(string(object, ID))) {
					existing = object;
				} else if (SOURCE_BASELINE.equals(profile.source()) && SOURCE_BASELINE.equals(string(object, SOURCE))) {
					list.remove(i);
				}
			}
			if (existing == null) {
				if (countProfiles(list) >= MAX_PROFILES) {
					refused[0] = true;
					return root;
				}
				existing = new JsonObject();
				list.add(existing);
			}
			write(existing, profile);
			return root;
		});
		return written && !refused[0];
	}

	public boolean rename(String id, @Nullable String name) {
		String clean = ProfileNames.sanitise(name);
		return update(root -> {
			for (JsonElement element : root.getAsJsonArray(PROFILES)) {
				if (element instanceof JsonObject object && id.equals(string(object, ID))) {
					object.addProperty(NAME, clean);
				}
			}
			return root;
		});
	}

	// Removes the profile; `active` and the battery's previous profile stop pointing at it.
	public boolean delete(String id) {
		return update(root -> {
			JsonArray list = root.getAsJsonArray(PROFILES);
			for (int i = list.size() - 1; i >= 0; i--) {
				if (list.get(i) instanceof JsonObject object && id.equals(string(object, ID))) {
					list.remove(i);
				}
			}
			if (id.equals(string(root, ACTIVE))) {
				root.remove(ACTIVE);
				root.remove(ACTIVE_ENTRY);
			}
			JsonObject battery = root.getAsJsonObject(BATTERY);
			if (id.equals(string(battery, BATTERY_PREVIOUS_PROFILE))) {
				battery.add(BATTERY_PREVIOUS_PROFILE, null);
			}
			return root;
		});
	}

	// "p-<uuid>", "template:<id>" or null.
	public @Nullable String active() {
		String active = string(read(), ACTIVE);
		return validActive(active) ? active : null;
	}

	public boolean setActive(@Nullable String id) {
		return setActive(id, null);
	}

	// entryId: the journal entry of the switch that made it active (null when the switch changed nothing): once that entry
	// is undone, the profile no longer counts as active (ActiveProfile.inEffect).
	public boolean setActive(@Nullable String id, @Nullable String entryId) {
		return update(root -> {
			if (id == null || !validActive(id)) {
				root.remove(ACTIVE);
				root.remove(ACTIVE_ENTRY);
			} else {
				root.addProperty(ACTIVE, id);
				if (entryId != null && ENTRY_ID_SHAPE.matcher(entryId).matches()) {
					root.addProperty(ACTIVE_ENTRY, entryId);
				} else {
					root.remove(ACTIVE_ENTRY);
				}
			}
			return root;
		});
	}

	// The journal entry of the switch that made the active profile active, or null.
	public @Nullable String activeEntry() {
		String entry = string(read(), ACTIVE_ENTRY);
		return entry != null && ENTRY_ID_SHAPE.matcher(entry).matches() ? entry : null;
	}

	public List<Switch> switches() {
		List<Switch> out = new ArrayList<>();
		if (!(read().get(SWITCHES) instanceof JsonArray list)) {
			return out;
		}
		for (JsonElement element : list) {
			if (element instanceof JsonObject object) {
				String entryId = string(object, ENTRY_ID);
				if (entryId != null && ENTRY_ID_SHAPE.matcher(entryId).matches()) {
					out.add(new Switch(entryId, string(object, PROFILE_ID), string(object, TEMPLATE_ID), ProfileNames.sanitise(string(object, NAME))));
				}
			}
		}
		return out;
	}

	// Journal entry id -> profile name, for History ("Profile: Battery"). The last switch recorded for an id wins.
	public Map<String, String> labels() {
		Map<String, String> out = new LinkedHashMap<>();
		for (Switch s : switches()) {
			if (s.name() != null) {
				out.put(s.entryId(), s.name());
			}
		}
		return out;
	}

	// Records a switch's label; journalIds (null = unknown, nothing pruned): labels for entries the journal no longer has
	// are dropped. At most MAX_SWITCHES are kept, the newest last.
	public boolean recordSwitch(Switch record, @Nullable Set<String> journalIds) {
		if (!ENTRY_ID_SHAPE.matcher(record.entryId()).matches()) {
			return false;
		}
		return update(root -> {
			JsonArray list = root.getAsJsonArray(SWITCHES);
			JsonObject object = new JsonObject();
			object.addProperty(ENTRY_ID, record.entryId());
			object.addProperty(PROFILE_ID, record.profileId());
			object.addProperty(TEMPLATE_ID, record.templateId());
			object.addProperty(NAME, ProfileNames.sanitise(record.name()));
			list.add(object);
			prune(list, journalIds);
			return root;
		});
	}

	// Drops the labels of entries the journal no longer has; writes nothing when none is stale.
	public boolean prune(Set<String> journalIds) {
		boolean stale = switches().stream().anyMatch(s -> !journalIds.contains(s.entryId()));
		return !stale || update(root -> {
			prune(root.getAsJsonArray(SWITCHES), journalIds);
			return root;
		});
	}

	public Battery battery() {
		JsonObject battery = read().get(BATTERY) instanceof JsonObject object ? object : null;
		return new Battery(bool(battery, BATTERY_PROMPT, true), string(battery, BATTERY_PREVIOUS_PROFILE), string(battery, BATTERY_LAST_PROMPT_AT),
				bool(battery, BATTERY_SNOOZED, false));
	}

	// A Battery offer was shown at `at` (ISO-8601).
	public boolean batteryOffered(String at) {
		return update(root -> {
			root.getAsJsonObject(BATTERY).addProperty(BATTERY_LAST_PROMPT_AT, at);
			return root;
		});
	}

	// The profile that was active when Battery was switched to (offered back on the next battery -> AC edge).
	public boolean rememberPrevious(@Nullable String previous) {
		return update(root -> {
			root.getAsJsonObject(BATTERY).addProperty(BATTERY_PREVIOUS_PROFILE, previous);
			return root;
		});
	}

	// "Don't offer again".
	public boolean snoozeBattery(boolean snoozed) {
		return update(root -> {
			root.getAsJsonObject(BATTERY).addProperty(BATTERY_SNOOZED, snoozed);
			return root;
		});
	}

	private static boolean validActive(@Nullable String active) {
		return active != null && (PROFILE_ID_SHAPE.matcher(active).matches()
				|| active.startsWith(TEMPLATE_PREFIX) && TEMPLATE_ID_SHAPE.matcher(active.substring(TEMPLATE_PREFIX.length())).matches());
	}

	private static void prune(JsonArray list, @Nullable Set<String> journalIds) {
		for (int i = list.size() - 1; i >= 0; i--) {
			String entryId = list.get(i) instanceof JsonObject object ? string(object, ENTRY_ID) : null;
			if (entryId == null || journalIds != null && !journalIds.contains(entryId)) {
				list.remove(i);
			}
		}
		while (list.size() > MAX_SWITCHES) {
			list.remove(0);
		}
	}

	private static List<Profile> profiles(JsonObject root) {
		List<Profile> out = new ArrayList<>();
		if (!(root.get(PROFILES) instanceof JsonArray list)) {
			return out;
		}
		for (JsonElement element : list) {
			if (!(element instanceof JsonObject object)) {
				continue;
			}
			String id = string(object, ID);
			if (id == null || !PROFILE_ID_SHAPE.matcher(id).matches()) {
				continue;
			}
			String source = string(object, SOURCE);
			String templateId = string(object, TEMPLATE_ID);
			out.add(new Profile(id, ProfileNames.sanitise(string(object, NAME)),
					templateId != null && TEMPLATE_ID_SHAPE.matcher(templateId).matches() ? templateId : null,
					source == null ? SOURCE_SAVED : source, string(object, CREATED_AT), string(object, RIGTUNE_VERSION), string(object, MC_VERSION),
					settings(object.get(SETTINGS))));
			if (out.size() >= MAX_PROFILES) {
				break;
			}
		}
		return out;
	}

	private static Map<String, String> settings(@Nullable JsonElement element) {
		Map<String, String> out = new LinkedHashMap<>();
		if (element instanceof JsonObject object) {
			for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
				ShareKeys.Key key = ShareKeys.byKey(entry.getKey());
				if (key != null && entry.getValue() instanceof JsonPrimitive primitive) {
					String value = primitive.getAsString();
					Integer wire = value.length() <= 64 && SettingKeys.safeValue(value) ? key.encode(value) : null;
					if (wire != null) {
						// The table's own spelling ("always" -> ALWAYS, " true" -> true): what reaches the writers.
						out.put(entry.getKey(), key.decode(wire, 60));
					}
				}
			}
		}
		return out;
	}

	private static void write(JsonObject object, Profile profile) {
		object.addProperty(ID, profile.id());
		object.addProperty(NAME, ProfileNames.sanitise(profile.name()));
		object.addProperty(TEMPLATE_ID, profile.templateId());
		object.addProperty(SOURCE, profile.source());
		object.addProperty(CREATED_AT, profile.createdAt());
		object.addProperty(RIGTUNE_VERSION, profile.rigtuneVersion());
		object.addProperty(MC_VERSION, profile.mcVersion());
		// Keys this version doesn't manage (a later RigTune's) stay; the managed ones are replaced.
		JsonObject settings = object.get(SETTINGS) instanceof JsonObject existing ? existing : new JsonObject();
		for (String key : List.copyOf(settings.keySet())) {
			if (ShareKeys.managed(key)) {
				settings.remove(key);
			}
		}
		profile.settings().forEach((key, value) -> {
			if (ShareKeys.managed(key) && SettingKeys.safeValue(value)) {
				settings.addProperty(key, value);
			}
		});
		object.add(SETTINGS, settings);
	}

	private static int countProfiles(JsonArray list) {
		int n = 0;
		for (JsonElement element : list) {
			if (element instanceof JsonObject) {
				n++;
			}
		}
		return n;
	}

	private static @Nullable String string(@Nullable JsonObject object, String key) {
		return object != null && object.get(key) instanceof JsonPrimitive p && p.isString() ? p.getAsString() : null;
	}

	private static boolean bool(@Nullable JsonObject object, String key, boolean fallback) {
		return object != null && object.get(key) instanceof JsonPrimitive p && p.isBoolean() ? p.getAsBoolean() : fallback;
	}
}
