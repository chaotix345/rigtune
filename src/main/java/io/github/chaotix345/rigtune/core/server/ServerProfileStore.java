package io.github.chaotix345.rigtune.core.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.store.StateStore;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

// config/rigtune/server-profiles.json, docs/v0.5/SPEC.md 7 (C16), sp §2.3-2.4: the profile the player asked RigTune to
// offer on each server. Server addresses are not stored in readable form: each entry is keyed by ServerLimitsStore's
// HMAC-SHA256 over the normalised address (its package-private key(), ServerLimitsStore unchanged) with this file's own
// random 16-byte salt, made on the first remember, so the keys can't be linked to server-limits.json's. At most
// MAX_SERVERS entries: a new one past that is refused (FULL), never evicted, since each is the player's choice. Shape:
// {"formatVersion": 1, "salt": "<32 hex>", "servers": {"<64 hex>": {"profile": "p-<uuid>|template:<id>",
//  "kind": "REMOTE|LAN_GUEST|REALM", "setAt": "<ISO-8601>", "lastSeen": "<ISO-8601>"}}}
// The file is untrusted (players edit it): an entry with a bad profile id or kind is ignored (not offered, listed or
// counted) and goes only with Forget all; a missing or bad salt keys nothing, and the next remember replaces it and drops
// the entries it keyed. Nothing is written for a server that isn't remembered, and a write happens only when something
// changes. The render thread (remember, forget) and Probes.EXECUTOR (the join lookup) both write it: StateStore's rules
// (X7), one instance per file per process.
public final class ServerProfileStore {
	public static final String FILE_NAME = "server-profiles.json";
	public static final long MAX_BYTES = 16 * 1024;
	public static final int MAX_SERVERS = 32;
	static final String SALT = "salt";
	static final String SERVERS = "servers";
	static final String PROFILE = "profile";
	static final String KIND = "kind";
	static final String SET_AT = "setAt";
	static final String LAST_SEEN = "lastSeen";
	private static final Pattern SALT_HEX = Pattern.compile("[0-9a-f]{32}");
	// ProfileStore's id shapes (a saved or imported profile, a template), redeclared so ProfileStore doesn't change.
	private static final Pattern PROFILE_ID = Pattern.compile("p-[A-Za-z0-9-]{1,64}|template:[a-z_]{1,32}");
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<Path, ServerProfileStore> SHARED = new HashMap<>();

	// READ_ONLY: the file is from a newer RigTune (or can't be read now); FAILED: refused (not a server, not a profile id)
	// or the write failed. Forgetting what isn't there is OK.
	public enum Result { OK, FULL, READ_ONLY, FAILED }

	// key: the entry's HMAC key (64 hex); setAt/lastSeen null when the file doesn't hold a readable time.
	public record Entry(String key, String profile, ServerLimits.Kind kind, @Nullable Instant setAt, @Nullable Instant lastSeen) {
	}

	private final StateStore store;

	private ServerProfileStore(Path file) {
		this.store = new StateStore(file, MAX_BYTES, root -> root);
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public static synchronized ServerProfileStore shared(Path configDir) {
		return SHARED.computeIfAbsent(file(configDir).toAbsolutePath().normalize(), ServerProfileStore::new);
	}

	public Path file() {
		return store.file();
	}

	// A copy of the content; empty when there's no usable file. A newer file is returned as it is (read-only).
	public JsonObject read() {
		return store.read();
	}

	public boolean update(UnaryOperator<JsonObject> change) {
		return store.update(change);
	}

	// False when the file is from a newer RigTune or can't be read: nothing is written then.
	public boolean writable() {
		return store.writable();
	}

	// address: normalised (ServerLimitsStore.address, lan, realm), never stored.
	public @Nullable Entry get(String address) {
		JsonObject root = store.read();
		String key = key(root, address);
		return key == null ? null : entry(key, servers(root).get(key));
	}

	// The join lookup: the remembered entry with lastSeen now (written only for a remembered server; a newer file still
	// answers, unchanged), else null with nothing written.
	public @Nullable Entry joined(String address, Instant now) {
		Entry found = get(address);
		if (found == null || now == null) {
			return found;
		}
		Instant seen = now.truncatedTo(ChronoUnit.SECONDS);
		Entry[] touched = {null};
		boolean written = store.update(root -> {
			if (servers(root).get(found.key()) instanceof JsonObject e && entry(found.key(), e) != null) {
				e.addProperty(LAST_SEEN, seen.toString());
				touched[0] = entry(found.key(), e);
			}
			return root;
		});
		return written && touched[0] != null ? touched[0] : found;
	}

	// Offer profileId on this server from now on (replacing its profile in place when it's already remembered).
	public Result remember(String address, ServerLimits.Kind kind, String profileId, Instant now) {
		if (blank(address) || kind == null || kind == ServerLimits.Kind.SINGLEPLAYER || now == null) {
			RigTune.LOGGER.warn("Not remembering a profile here: RigTune offers profiles on servers, LAN games and Realms only");
			return Result.FAILED;
		}
		if (profileId == null || !PROFILE_ID.matcher(profileId).matches()) {
			RigTune.LOGGER.warn("Not remembering a profile here: not a profile id");
			return Result.FAILED;
		}
		if (!store.writable()) {
			return Result.READ_ONLY;
		}
		if (full(store.read(), address)) {
			return Result.FULL;
		}
		String at = now.truncatedTo(ChronoUnit.SECONDS).toString();
		boolean[] full = {false};
		boolean written = store.update(root -> {
			if (salt(root) == null) {
				byte[] salt = new byte[16];
				RANDOM.nextBytes(salt);
				root.addProperty(SALT, HexFormat.of().formatHex(salt));
				root.add(SERVERS, new JsonObject());
			}
			if (!(root.get(SERVERS) instanceof JsonObject)) {
				root.add(SERVERS, new JsonObject());
			}
			if (full(root, address)) {
				full[0] = true;
				return root;
			}
			JsonObject servers = root.getAsJsonObject(SERVERS);
			String key = ServerLimitsStore.key(salt(root), address);
			JsonObject e = servers.get(key) instanceof JsonObject existing ? existing : new JsonObject();
			e.addProperty(PROFILE, profileId);
			e.addProperty(KIND, kind.name());
			e.addProperty(SET_AT, at);
			e.addProperty(LAST_SEEN, at);
			servers.add(key, e);
			return root;
		});
		if (full[0]) {
			return Result.FULL;
		}
		return written ? Result.OK : Result.FAILED;
	}

	public Result forget(String address) {
		return forgetKey(keyOf(address));
	}

	// key: an entry's key (a row of the list).
	public Result forgetKey(@Nullable String key) {
		if (!store.writable()) {
			return Result.READ_ONLY;
		}
		if (key == null || !servers(store.read()).has(key)) {
			return Result.OK;
		}
		return store.update(root -> {
			servers(root).remove(key);
			return root;
		}) ? Result.OK : Result.FAILED;
	}

	// Forgets every server mapped to profileId (the profile was deleted); the number forgotten, 0 when none or not written.
	public int forgetProfile(@Nullable String profileId) {
		if (profileId == null || !store.writable() || entries(store.read()).stream().noneMatch(e -> e.profile().equals(profileId))) {
			return 0;
		}
		int[] removed = {0};
		boolean written = store.update(root -> {
			JsonObject servers = servers(root);
			for (String key : new ArrayList<>(servers.keySet())) {
				Entry e = entry(key, servers.get(key));
				if (e != null && e.profile().equals(profileId)) {
					servers.remove(key);
					removed[0]++;
				}
			}
			return root;
		});
		return written ? removed[0] : 0;
	}

	// Every entry, the ones this version can't read too; the salt stays.
	public Result forgetAll() {
		if (!store.writable()) {
			return Result.READ_ONLY;
		}
		JsonElement servers = store.read().get(SERVERS);
		if (servers == null || servers instanceof JsonObject o && o.size() == 0) {
			return Result.OK;
		}
		return store.update(root -> {
			root.add(SERVERS, new JsonObject());
			return root;
		}) ? Result.OK : Result.FAILED;
	}

	// The remembered servers this version can read, the most recently joined first (no readable lastSeen last).
	public List<Entry> entries() {
		return entries(store.read());
	}

	// This server's key (the list marks its row), or null without a salt: nothing is written.
	public @Nullable String keyOf(String address) {
		return key(store.read(), address);
	}

	private static List<Entry> entries(JsonObject root) {
		if (salt(root) == null) {
			return List.of();
		}
		List<Entry> out = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : servers(root).entrySet()) {
			Entry entry = entry(e.getKey(), e.getValue());
			if (entry != null) {
				out.add(entry);
			}
		}
		out.sort(Comparator.comparing(Entry::lastSeen, Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Entry::key));
		return out;
	}

	// A new server when the file already holds MAX_SERVERS readable ones (a remembered server can always be set again).
	private static boolean full(JsonObject root, String address) {
		String key = key(root, address);
		boolean known = key != null && entry(key, servers(root).get(key)) != null;
		return !known && entries(root).size() >= MAX_SERVERS;
	}

	private static @Nullable String key(JsonObject root, @Nullable String address) {
		String salt = salt(root);
		return salt == null || blank(address) ? null : ServerLimitsStore.key(salt, address);
	}

	private static boolean blank(@Nullable String address) {
		return address == null || address.isBlank();
	}

	private static @Nullable String salt(JsonObject root) {
		return root.get(SALT) instanceof JsonPrimitive p && p.isString() && SALT_HEX.matcher(p.getAsString()).matches() ? p.getAsString() : null;
	}

	// The servers object, or an empty one (not in the file) when it's missing or not an object.
	private static JsonObject servers(JsonObject root) {
		return root.get(SERVERS) instanceof JsonObject servers ? servers : new JsonObject();
	}

	private static @Nullable Entry entry(String key, @Nullable JsonElement element) {
		if (!(element instanceof JsonObject e) || !(e.get(PROFILE) instanceof JsonPrimitive p) || !p.isString()
				|| !PROFILE_ID.matcher(p.getAsString()).matches()) {
			return null;
		}
		ServerLimits.Kind kind = kind(e.get(KIND));
		return kind == null ? null : new Entry(key, p.getAsString(), kind, instant(e.get(SET_AT)), instant(e.get(LAST_SEEN)));
	}

	private static ServerLimits.@Nullable Kind kind(@Nullable JsonElement e) {
		if (!(e instanceof JsonPrimitive p) || !p.isString()) {
			return null;
		}
		for (ServerLimits.Kind kind : ServerLimits.Kind.values()) {
			if (kind != ServerLimits.Kind.SINGLEPLAYER && kind.name().equals(p.getAsString())) {
				return kind;
			}
		}
		return null;
	}

	private static @Nullable Instant instant(@Nullable JsonElement e) {
		if (!(e instanceof JsonPrimitive p) || !p.isString()) {
			return null;
		}
		try {
			return Instant.parse(p.getAsString());
		} catch (DateTimeParseException ex) {
			return null;
		}
	}
}
