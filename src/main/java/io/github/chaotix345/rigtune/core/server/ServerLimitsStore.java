package io.github.chaotix345.rigtune.core.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.store.StateStore;
import org.jspecify.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

// config/rigtune/server-limits.json (docs/v0.4/SPEC.md C1, 8; plan review W-L1): the last limits each server sent, only so
// the notice can say "this server's limit changed since last time (was N)". The Recommender never reads it. Server
// addresses are not stored in readable form: each key is HMAC-SHA256 over the normalised address with a random 16-byte
// salt created once and kept in the file. At most MAX_SERVERS entries (the oldest lastSeen goes). Shape:
// {"formatVersion": 1, "salt": "<32 hex>", "servers": {"<64 hex>": {"viewDistance": 12, "simulationDistance": 10,
//  "kind": "REMOTE|LAN_GUEST|REALM", "lastSeen": "<ISO-8601>"}}}
// The file is untrusted (players edit it): every value is type-checked; a bad salt is replaced and the entries it keyed
// are dropped (they could never match again). JsonStateFile's rules apply (.bad, newer = read-only, cap).
public final class ServerLimitsStore {
	public static final String FILE_NAME = "server-limits.json";
	public static final long MAX_BYTES = 16 * 1024;
	public static final int MAX_SERVERS = 32;
	public static final int DEFAULT_PORT = 25565;
	static final String SALT = "salt";
	static final String SERVERS = "servers";
	static final String VIEW_DISTANCE = "viewDistance";
	static final String SIMULATION_DISTANCE = "simulationDistance";
	static final String KIND = "kind";
	static final String LAST_SEEN = "lastSeen";
	private static final Pattern SALT_HEX = Pattern.compile("[0-9a-f]{32}");
	private static final SecureRandom RANDOM = new SecureRandom();

	public record Entry(int viewDistance, int simulationDistance, ServerLimits.Kind kind, @Nullable Instant lastSeen) {
	}

	private final StateStore store;

	public ServerLimitsStore(Path configDir) {
		this.store = new StateStore(file(configDir), MAX_BYTES, ServerLimitsStore::withDefaults);
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public Path file() {
		return store.file();
	}

	// "host:port" in lower case (an IPv6 host in brackets, a trailing dot dropped, the default port written out), so
	// "Play.Example.com" and "play.example.com:25565" are the same server.
	public static String address(String host, int port) {
		String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
		while (h.endsWith(".")) {
			h = h.substring(0, h.length() - 1);
		}
		if (h.contains(":") && !h.startsWith("[")) {
			h = "[" + h + "]";
		}
		return h + ":" + (port > 0 && port <= 65535 ? port : DEFAULT_PORT);
	}

	// A LAN game by its host only (Open to LAN picks a new port every time).
	public static String lan(String host) {
		String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
		return "lan:" + h;
	}

	// A Realm by its world name (Realms addresses change between connections).
	public static String realm(String worldName) {
		return "realm:" + (worldName == null ? "" : worldName.strip());
	}

	public @Nullable Entry get(String address) {
		JsonObject root = store.read();
		String salt = salt(root);
		if (salt == null || !(root.get(SERVERS) instanceof JsonObject servers)) {
			return null;
		}
		return entry(servers.get(key(salt, address)));
	}

	// Stores what the server sent now; returns the entry it replaces (null when there was none, when the limits are the
	// player's own world's, or when nothing could be written).
	public @Nullable Entry remember(String address, ServerLimits limits) {
		if (limits == null || limits.kind() == ServerLimits.Kind.SINGLEPLAYER) {
			return null;
		}
		Entry[] previous = new Entry[1];
		boolean written = store.update(root -> {
			JsonObject servers = root.getAsJsonObject(SERVERS);
			String key = key(salt(root), address);
			previous[0] = entry(servers.get(key));
			JsonObject e = new JsonObject();
			e.addProperty(VIEW_DISTANCE, limits.viewDistance());
			e.addProperty(SIMULATION_DISTANCE, limits.simulationDistance());
			e.addProperty(KIND, limits.kind().name());
			e.addProperty(LAST_SEEN, Instant.ofEpochMilli(limits.lastSeenEpochMillis()).toString());
			servers.remove(key);
			servers.add(key, e);
			prune(servers);
			return root;
		});
		return written ? previous[0] : null;
	}

	public boolean writable() {
		return store.writable();
	}

	static String key(String saltHex, String address) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(HexFormat.of().parseHex(saltHex), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(address.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}

	private static JsonObject withDefaults(JsonObject root) {
		if (salt(root) == null) {
			byte[] salt = new byte[16];
			RANDOM.nextBytes(salt);
			root.addProperty(SALT, HexFormat.of().formatHex(salt));
			root.add(SERVERS, new JsonObject());
		}
		if (!(root.get(SERVERS) instanceof JsonObject)) {
			root.add(SERVERS, new JsonObject());
		}
		return root;
	}

	private static @Nullable String salt(JsonObject root) {
		return root.get(SALT) instanceof JsonPrimitive p && p.isString() && SALT_HEX.matcher(p.getAsString()).matches() ? p.getAsString() : null;
	}

	private static @Nullable Entry entry(@Nullable JsonElement element) {
		if (!(element instanceof JsonObject e)) {
			return null;
		}
		Integer view = distance(e.get(VIEW_DISTANCE));
		Integer simulation = distance(e.get(SIMULATION_DISTANCE));
		ServerLimits.Kind kind = kind(e.get(KIND));
		if (view == null || simulation == null || kind == null || kind == ServerLimits.Kind.SINGLEPLAYER) {
			return null;
		}
		return new Entry(view, simulation, kind, instant(e.get(LAST_SEEN)));
	}

	private static @Nullable Integer distance(@Nullable JsonElement e) {
		if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
			return null;
		}
		double value = p.getAsDouble();
		return value == Math.rint(value) && value >= 0 && value <= 256 ? (int) value : null;
	}

	private static ServerLimits.@Nullable Kind kind(@Nullable JsonElement e) {
		if (!(e instanceof JsonPrimitive p) || !p.isString()) {
			return null;
		}
		try {
			return ServerLimits.Kind.valueOf(p.getAsString());
		} catch (IllegalArgumentException ex) {
			return null;
		}
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

	// The oldest lastSeen go first (an entry without a readable one counts as oldest).
	private static void prune(JsonObject servers) {
		while (servers.size() > MAX_SERVERS) {
			String oldest = null;
			Instant oldestAt = null;
			List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(servers.entrySet());
			for (Map.Entry<String, JsonElement> entry : entries) {
				Instant at = entry.getValue() instanceof JsonObject o ? instant(o.get(LAST_SEEN)) : null;
				if (oldest == null || at == null || (oldestAt != null && at.isBefore(oldestAt))) {
					oldest = entry.getKey();
					oldestAt = at;
					if (at == null) {
						break;
					}
				}
			}
			servers.remove(oldest);
		}
	}
}
