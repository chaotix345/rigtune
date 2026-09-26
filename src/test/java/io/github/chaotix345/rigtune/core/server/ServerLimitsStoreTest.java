package io.github.chaotix345.rigtune.core.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 8 (AC8.2, plan review W-L1): the key is stable per address, case-normalised and differs by port; the
// file holds no plaintext host; 32-entry cap; corrupt -> .bad; newer formatVersion -> read-only.
class ServerLimitsStoreTest {
	private static final long T0 = Instant.parse("2026-09-20T10:00:00Z").toEpochMilli();

	@TempDir
	Path config;

	private static ServerLimits remote(int view, int simulation, long at) {
		return new ServerLimits(view, simulation, ServerLimits.Kind.REMOTE, at);
	}

	@Test
	void addressNormalisation() {
		assertEquals("play.example.com:25565", ServerLimitsStore.address("Play.Example.COM", 25565));
		assertEquals("play.example.com:25565", ServerLimitsStore.address(" play.example.com. ", 0));
		assertEquals("play.example.com:25566", ServerLimitsStore.address("play.example.com", 25566));
		assertEquals("[::1]:25565", ServerLimitsStore.address("::1", -1));
		assertEquals("realm:My World", ServerLimitsStore.realm(" My World "));
		assertEquals("lan:192.168.1.20", ServerLimitsStore.lan(" 192.168.1.20 "));
	}

	@Test
	void keyIsStablePerAddressCaseNormalisedAndDiffersByPort() throws IOException {
		ServerLimitsStore store = new ServerLimitsStore(config);
		assertNull(store.remember(ServerLimitsStore.address("Play.Example.com", 25565), remote(10, 8, T0)));
		ServerLimitsStore.Entry again = new ServerLimitsStore(config).get(ServerLimitsStore.address("play.example.com", 0));
		assertEquals(new ServerLimitsStore.Entry(10, 8, ServerLimits.Kind.REMOTE, Instant.ofEpochMilli(T0)), again);
		assertNull(store.get(ServerLimitsStore.address("play.example.com", 25566)));
		JsonObject root = read();
		String salt = root.get("salt").getAsString();
		String key = root.getAsJsonObject("servers").keySet().iterator().next();
		assertEquals(64, key.length());
		assertEquals(ServerLimitsStore.key(salt, "play.example.com:25565"), key);
		assertNotEquals(ServerLimitsStore.key(salt, "play.example.com:25566"), key);
		assertNotEquals(ServerLimitsStore.key(salt, "realm:play.example.com"), key);
	}

	@Test
	void theFileHoldsNoPlaintextHostAndEachFileHasItsOwnSalt(@TempDir Path other) throws IOException {
		new ServerLimitsStore(config).remember(ServerLimitsStore.address("localhost", 41234), remote(6, 5, T0));
		new ServerLimitsStore(config).remember(ServerLimitsStore.address("127.0.0.1", 25565), remote(6, 5, T0));
		new ServerLimitsStore(config).remember(ServerLimitsStore.realm("Secret Realm"), new ServerLimits(8, 6, ServerLimits.Kind.REALM, T0));
		String text = Files.readString(ServerLimitsStore.file(config), StandardCharsets.UTF_8);
		for (String plain : new String[]{"localhost", "127.0.0.1", "41234", "Secret", "realm:"}) {
			assertFalse(text.contains(plain), plain + " in " + text);
		}
		new ServerLimitsStore(other).remember(ServerLimitsStore.address("localhost", 41234), remote(6, 5, T0));
		String otherKey = JsonParser.parseString(Files.readString(ServerLimitsStore.file(other))).getAsJsonObject().getAsJsonObject("servers")
				.keySet().iterator().next();
		assertFalse(text.contains(otherKey), "another instance's salt gives another key for the same address");
	}

	@Test
	void rememberReturnsTheLastLimits() {
		ServerLimitsStore store = new ServerLimitsStore(config);
		String address = ServerLimitsStore.address("mc.example.net", 25565);
		assertNull(store.remember(address, remote(12, 10, T0)));
		ServerLimitsStore.Entry previous = store.remember(address, remote(8, 6, T0 + 1000));
		assertNotNull(previous);
		assertEquals(12, previous.viewDistance());
		assertEquals(8, store.get(address).viewDistance());
		assertNull(store.remember(address, new ServerLimits(4, 4, ServerLimits.Kind.SINGLEPLAYER, T0)), "singleplayer limits are never stored");
		assertEquals(8, store.get(address).viewDistance());
	}

	@Test
	void capKeepsTheNewest32() throws IOException {
		ServerLimitsStore store = new ServerLimitsStore(config);
		for (int i = 0; i < 40; i++) {
			store.remember(ServerLimitsStore.address("server" + i + ".example", 25565), remote(10, 10, T0 + i * 60_000L));
		}
		assertEquals(ServerLimitsStore.MAX_SERVERS, read().getAsJsonObject("servers").size());
		for (int i = 0; i < 8; i++) {
			assertNull(store.get(ServerLimitsStore.address("server" + i + ".example", 25565)), "pruned " + i);
		}
		for (int i = 8; i < 40; i++) {
			assertNotNull(store.get(ServerLimitsStore.address("server" + i + ".example", 25565)), "kept " + i);
		}
		assertTrue(Files.size(ServerLimitsStore.file(config)) < ServerLimitsStore.MAX_BYTES);
	}

	@Test
	void corruptMovesAside() throws IOException {
		Files.createDirectories(ServerLimitsStore.file(config).getParent());
		Files.writeString(ServerLimitsStore.file(config), "{broken", StandardCharsets.UTF_8);
		ServerLimitsStore store = new ServerLimitsStore(config);
		assertNull(store.get(ServerLimitsStore.address("a", 1)));
		assertNull(store.remember(ServerLimitsStore.address("a", 1), remote(6, 5, T0)));
		assertTrue(Files.isRegularFile(config.resolve("rigtune").resolve("server-limits.json.bad")));
		assertEquals(6, store.get(ServerLimitsStore.address("a", 1)).viewDistance());
	}

	@Test
	void newerIsReadOnly() throws IOException {
		Files.createDirectories(ServerLimitsStore.file(config).getParent());
		String newer = "{\"formatVersion\": 2, \"salt\": \"00112233445566778899aabbccddeeff\", \"servers\": {}}";
		Files.writeString(ServerLimitsStore.file(config), newer, StandardCharsets.UTF_8);
		ServerLimitsStore store = new ServerLimitsStore(config);
		assertFalse(store.writable());
		assertNull(store.remember(ServerLimitsStore.address("a", 1), remote(6, 5, T0)));
		assertEquals(newer, Files.readString(ServerLimitsStore.file(config), StandardCharsets.UTF_8));
	}

	@Test
	void aBadSaltIsReplacedAndItsEntriesDropped() throws IOException {
		Files.createDirectories(ServerLimitsStore.file(config).getParent());
		Files.writeString(ServerLimitsStore.file(config), "{\"formatVersion\": 1, \"salt\": \"nothex\", \"future\": 1, "
				+ "\"servers\": {\"abc\": {\"viewDistance\": 3, \"simulationDistance\": 3, \"kind\": \"REMOTE\", \"lastSeen\": \"x\"}}}", StandardCharsets.UTF_8);
		ServerLimitsStore store = new ServerLimitsStore(config);
		store.remember(ServerLimitsStore.address("a", 1), remote(6, 5, T0));
		JsonObject root = read();
		assertTrue(root.get("salt").getAsString().matches("[0-9a-f]{32}"));
		assertEquals(1, root.getAsJsonObject("servers").size());
		assertEquals(1, root.get("future").getAsInt(), "unknown fields survive");
		assertEquals(1, root.get("formatVersion").getAsInt());
	}

	@Test
	void badEntriesReadAsMissing() throws IOException {
		ServerLimitsStore store = new ServerLimitsStore(config);
		store.remember(ServerLimitsStore.address("a", 1), remote(6, 5, T0));
		JsonObject root = read();
		String salt = root.get("salt").getAsString();
		JsonObject servers = root.getAsJsonObject("servers");
		String[] bad = {"{\"viewDistance\": \"6\", \"simulationDistance\": 5, \"kind\": \"REMOTE\"}",
				"{\"viewDistance\": 6.5, \"simulationDistance\": 5, \"kind\": \"REMOTE\"}",
				"{\"viewDistance\": 6, \"simulationDistance\": 5, \"kind\": \"SINGLEPLAYER\"}",
				"{\"viewDistance\": 6, \"simulationDistance\": 5, \"kind\": \"MARS\"}", "[1]", "7"};
		for (int i = 0; i < bad.length; i++) {
			servers.add(ServerLimitsStore.key(salt, "bad" + i + ":1"), JsonParser.parseString(bad[i]));
		}
		root.addProperty("formatVersion", 1);
		Files.writeString(ServerLimitsStore.file(config), root.toString(), StandardCharsets.UTF_8);
		for (int i = 0; i < bad.length; i++) {
			assertNull(store.get("bad" + i + ":1"), bad[i]);
		}
		assertEquals(6, store.get(ServerLimitsStore.address("a", 1)).viewDistance());
	}

	private JsonObject read() throws IOException {
		return JsonParser.parseString(Files.readString(ServerLimitsStore.file(config), StandardCharsets.UTF_8)).getAsJsonObject();
	}
}
