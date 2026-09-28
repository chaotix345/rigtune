package io.github.chaotix345.rigtune.core.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore.Entry;
import io.github.chaotix345.rigtune.core.server.ServerProfileStore.Result;
import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 7 (AC7.1-AC7.3), sp §2.3-2.4: server-profiles.json keys each remembered server by an HMAC of its
// normalised address with the file's own salt (never the address); at most 32 servers, a 33rd is refused; joining touches
// lastSeen only for a remembered server; X7's rules (corrupt -> .bad, newer -> read-only, over 4 x the cap left alone,
// unknown fields kept); every value type-checked.
class ServerProfileStoreTest {
	private static final Instant T0 = Instant.parse("2026-09-20T10:00:00Z");
	private static final Instant T1 = Instant.parse("2026-09-21T11:30:00Z");
	private static final Instant T2 = Instant.parse("2026-09-22T12:45:00Z");
	private static final Instant T3 = Instant.parse("2026-09-23T20:15:00Z");
	private static final String PLAY = ServerLimitsStore.address("play.example.com", 25565);
	private static final String OTHER = ServerLimitsStore.address("mc.example.net", 25565);
	private static final String LAN = ServerLimitsStore.lan("192.168.1.20");
	private static final String REALM = ServerLimitsStore.realm("Weekend SMP");
	private static final ServerLimits.Kind REMOTE = ServerLimits.Kind.REMOTE;

	@TempDir
	Path config;

	private ServerProfileStore store() {
		return ServerProfileStore.shared(config);
	}

	private Path file() {
		return ServerProfileStore.file(config);
	}

	private JsonObject json() throws IOException {
		return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private void write(String text) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), text, StandardCharsets.UTF_8);
	}

	private static String server(int i) {
		return ServerLimitsStore.address("server" + i + ".example", 25565);
	}

	@Test
	void theFirstRememberCreatesTheSaltAndOneEntry() throws IOException {
		ServerProfileStore store = store();
		assertNull(store.keyOf(PLAY));
		assertEquals(Result.OK, store.remember(PLAY, REMOTE, "template:max_fps", T0));
		JsonObject root = json();
		assertEquals(1, root.get("formatVersion").getAsInt());
		String salt = root.get("salt").getAsString();
		assertTrue(salt.matches("[0-9a-f]{32}"), salt);
		JsonObject servers = root.getAsJsonObject("servers");
		assertEquals(1, servers.size());
		String key = servers.keySet().iterator().next();
		assertTrue(key.matches("[0-9a-f]{64}"), key);
		assertEquals(ServerLimitsStore.key(salt, PLAY), key);
		assertEquals(JsonParser.parseString("{\"profile\": \"template:max_fps\", \"kind\": \"REMOTE\", \"setAt\": \"2026-09-20T10:00:00Z\", "
				+ "\"lastSeen\": \"2026-09-20T10:00:00Z\"}"), servers.get(key));
		assertEquals(new Entry(key, "template:max_fps", REMOTE, T0, T0), store.get(PLAY));
		assertEquals(key, store.keyOf(PLAY));
		assertEquals(List.of(new Entry(key, "template:max_fps", REMOTE, T0, T0)), store.entries());
	}

	@Test
	void timesAreKeptToTheSecond() throws IOException {
		assertEquals(Result.OK, store().remember(PLAY, REMOTE, "p-1", T0.plusNanos(123_456_789)));
		assertEquals("2026-09-20T10:00:00Z", json().getAsJsonObject("servers").entrySet().iterator().next().getValue().getAsJsonObject().get("setAt")
				.getAsString());
	}

	@Test
	void theFileHoldsNoPlaintextAndItsKeysDifferFromServerLimits() throws IOException {
		ServerProfileStore store = store();
		String local = ServerLimitsStore.address("localhost", 41234);
		assertEquals(Result.OK, store.remember(local, REMOTE, "p-evening", T0));
		assertEquals(Result.OK, store.remember(ServerLimitsStore.address("127.0.0.1", 25565), REMOTE, "p-evening", T0));
		assertEquals(Result.OK, store.remember(ServerLimitsStore.realm("Secret Realm"), ServerLimits.Kind.REALM, "template:quality", T0));
		assertEquals(Result.OK, store.remember(LAN, ServerLimits.Kind.LAN_GUEST, "template:battery", T0));
		String text = Files.readString(file(), StandardCharsets.UTF_8);
		// The hex (salt and keys) is random: leave it out, so a digit run inside it can't look like a port.
		String withoutHex = text.replaceAll("[0-9a-f]{32,64}", "#");
		for (String plain : List.of("localhost", "127.0.0.1", "41234", "25565", "Secret", "realm:", "lan:", "192.168", "example")) {
			assertFalse(withoutHex.contains(plain), plain + " in " + text);
		}
		new ServerLimitsStore(config).remember(local, new ServerLimits(8, 8, REMOTE, T0.toEpochMilli()));
		JsonObject limits = JsonParser.parseString(Files.readString(ServerLimitsStore.file(config), StandardCharsets.UTF_8)).getAsJsonObject();
		assertNotEquals(limits.get("salt").getAsString(), json().get("salt").getAsString(), "its own salt, not server-limits.json's");
		String limitsKey = limits.getAsJsonObject("servers").keySet().iterator().next();
		assertNotEquals(limitsKey, store.keyOf(local), "the two files' keys for one server can't be linked");
		assertNotNull(store.get(local));
	}

	@Test
	void oneServerHowEverItsAddressIsWritten() {
		ServerProfileStore store = store();
		assertEquals(Result.OK, store.remember(ServerLimitsStore.address("Play.Example.com", 0), REMOTE, "p-1", T0));
		assertEquals("p-1", store.get(ServerLimitsStore.address("play.example.com", 25565)).profile());
		assertEquals("p-1", store.get(ServerLimitsStore.address(" PLAY.example.com. ", -1)).profile());
		assertNull(store.get(ServerLimitsStore.address("play.example.com", 25566)), "another port is another server");
		assertEquals(Result.OK, store.remember(LAN, ServerLimits.Kind.LAN_GUEST, "p-2", T0));
		assertEquals(Result.OK, store.remember(REALM, ServerLimits.Kind.REALM, "p-3", T0));
		assertEquals("p-2", store.get(ServerLimitsStore.lan(" 192.168.1.20 ")).profile());
		assertEquals("p-3", store.get(ServerLimitsStore.realm(" Weekend SMP ")).profile());
		assertNull(store.get(ServerLimitsStore.address("192.168.1.20", 25565)), "a LAN game isn't the same host's server");
		assertNull(store.get(ServerLimitsStore.address("Weekend SMP", 25565)));
		assertNotEquals(store.keyOf(LAN), store.keyOf(ServerLimitsStore.address("192.168.1.20", 25565)));
		assertEquals(3, store.entries().size());
	}

	@Test
	void joinedTouchesLastSeenOnlyForARememberedServer() throws IOException {
		ServerProfileStore store = store();
		assertNull(store.joined(PLAY, T0));
		assertFalse(Files.exists(file()), "joining a server nobody remembered writes nothing");
		assertEquals(Result.OK, store.remember(PLAY, REMOTE, "template:quality", T0));
		Files.setLastModifiedTime(file(), FileTime.from(T0));
		byte[] before = Files.readAllBytes(file());
		assertNull(store.joined(OTHER, T1));
		assertArrayEquals(before, Files.readAllBytes(file()));
		assertEquals(FileTime.from(T0), Files.getLastModifiedTime(file()), "not rewritten");
		Entry joined = store.joined(PLAY, T2);
		assertEquals(new Entry(store.keyOf(PLAY), "template:quality", REMOTE, T0, T2), joined);
		assertEquals(joined, store.get(PLAY));
		assertEquals("2026-09-22T12:45:00Z", json().getAsJsonObject("servers").get(store.keyOf(PLAY)).getAsJsonObject().get("lastSeen").getAsString());
	}

	@Test
	void aNewThirtyThirdServerIsFullAndTheFileUnchanged() throws IOException {
		ServerProfileStore store = store();
		String longId = "p-" + "a".repeat(64);
		for (int i = 0; i < ServerProfileStore.MAX_SERVERS; i++) {
			assertEquals(Result.OK, store.remember(server(i), ServerLimits.Kind.LAN_GUEST, longId, T0.plusSeconds(i)), "server " + i);
		}
		byte[] full = Files.readAllBytes(file());
		assertTrue(full.length <= ServerProfileStore.MAX_BYTES, full.length + " bytes");
		assertEquals(Result.FULL, store.remember(server(99), REMOTE, "template:max_fps", T1));
		assertArrayEquals(full, Files.readAllBytes(file()), "nothing evicted, nothing written");
		assertNull(store.get(server(99)));
		assertEquals(Result.OK, store.remember(server(5), REMOTE, "template:max_fps", T1), "re-remembering one of the 32 still works");
		assertEquals(ServerProfileStore.MAX_SERVERS, store.entries().size());
		assertEquals("template:max_fps", store.get(server(5)).profile());
	}

	@Test
	void reRememberingReplacesInPlace() throws IOException {
		ServerProfileStore store = store();
		assertEquals(Result.OK, store.remember(PLAY, REMOTE, "p-1", T0));
		assertEquals(Result.OK, store.remember(OTHER, REMOTE, "p-2", T0));
		String key = store.keyOf(PLAY);
		JsonObject root = json();
		root.getAsJsonObject("servers").getAsJsonObject(key).addProperty("note", "kept");
		write(root.toString());
		assertEquals(Result.OK, store.remember(PLAY, REMOTE, "template:battery", T1));
		JsonObject servers = json().getAsJsonObject("servers");
		assertEquals(List.of(key, store.keyOf(OTHER)), new ArrayList<>(servers.keySet()), "same place");
		JsonObject entry = servers.getAsJsonObject(key);
		assertEquals("template:battery", entry.get("profile").getAsString());
		assertEquals("2026-09-21T11:30:00Z", entry.get("setAt").getAsString());
		assertEquals("2026-09-21T11:30:00Z", entry.get("lastSeen").getAsString());
		assertEquals("kept", entry.get("note").getAsString());
		assertEquals(2, store.entries().size());
	}

	@Test
	void singleplayerAndMalformedIdsAreRefused() {
		ServerProfileStore store = store();
		assertEquals(Result.FAILED, store.remember(PLAY, ServerLimits.Kind.SINGLEPLAYER, "template:max_fps", T0));
		assertEquals(Result.FAILED, store.remember(PLAY, null, "template:max_fps", T0));
		assertEquals(Result.FAILED, store.remember(null, REMOTE, "template:max_fps", T0));
		assertEquals(Result.FAILED, store.remember("  ", REMOTE, "template:max_fps", T0));
		assertEquals(Result.FAILED, store.remember(PLAY, REMOTE, "template:max_fps", null));
		for (String bad : new String[]{null, "", "p-", "p-with space", "p-" + "a".repeat(65), "template:", "template:Max", "template:" + "a".repeat(33),
				"max_fps", "P-1", "p-1\n", "template:max_fps "}) {
			assertEquals(Result.FAILED, store.remember(PLAY, REMOTE, bad, T0), String.valueOf(bad));
		}
		assertFalse(Files.exists(file()), "nothing written");
		assertEquals(Result.OK, store.remember(PLAY, REMOTE, "p-" + "A1-".repeat(21) + "z", T0));
		assertEquals(Result.OK, store.remember(OTHER, REMOTE, "template:" + "a_".repeat(16), T0));
	}

	@Test
	void forgetForgetKeyForgetProfileForgetAll() throws IOException {
		ServerProfileStore store = store();
		store.remember(PLAY, REMOTE, "p-1", T0);
		store.remember(OTHER, REMOTE, "p-1", T0);
		store.remember(LAN, ServerLimits.Kind.LAN_GUEST, "template:quality", T0);
		String salt = json().get("salt").getAsString();
		assertEquals(Result.OK, store.forget(PLAY));
		assertNull(store.get(PLAY));
		byte[] before = Files.readAllBytes(file());
		assertEquals(Result.OK, store.forget(PLAY), "forgetting what isn't there");
		assertEquals(Result.OK, store.forgetKey("0".repeat(64)));
		assertArrayEquals(before, Files.readAllBytes(file()), "writes nothing");
		assertEquals(1, store.forgetProfile("p-1"));
		assertNull(store.get(OTHER));
		assertEquals(0, store.forgetProfile("p-1"));
		assertEquals(0, store.forgetProfile("template:max_fps"), "not mapped");
		assertEquals(0, store.forgetProfile(null));
		assertEquals(Result.OK, store.forgetKey(store.keyOf(LAN)));
		assertEquals(List.of(), store.entries());
		store.remember(PLAY, REMOTE, "template:max_fps", T1);
		store.remember(REALM, ServerLimits.Kind.REALM, "p-2", T1);
		assertEquals(Result.OK, store.forgetAll());
		assertEquals(List.of(), store.entries());
		assertEquals(new JsonObject(), json().getAsJsonObject("servers"));
		assertEquals(salt, json().get("salt").getAsString(), "the salt stays");
		before = Files.readAllBytes(file());
		assertEquals(Result.OK, store.forgetAll());
		assertArrayEquals(before, Files.readAllBytes(file()), "nothing to forget, nothing written");
	}

	@Test
	void forgetAllRemovesEntriesThisVersionCantRead() throws IOException {
		store().remember(PLAY, REMOTE, "p-1", T0);
		JsonObject root = json();
		root.getAsJsonObject("servers").add("junk", JsonParser.parseString("{\"profile\": 5}"));
		write(root.toString());
		assertEquals(1, store().entries().size());
		assertEquals(Result.OK, store().forgetAll());
		assertEquals(new JsonObject(), json().getAsJsonObject("servers"));
	}

	@Test
	void entriesAreTypeCheckedNewestFirstAndOnlyValidOnesCount() throws IOException {
		ServerProfileStore store = store();
		store.remember(PLAY, REMOTE, "p-1", T0);
		store.remember(OTHER, REMOTE, "p-2", T1);
		store.remember(LAN, ServerLimits.Kind.LAN_GUEST, "p-3", T2);
		store.joined(PLAY, T3);
		assertEquals(List.of(store.keyOf(PLAY), store.keyOf(LAN), store.keyOf(OTHER)), store.entries().stream().map(Entry::key).toList());
		JsonObject root = json();
		String salt = root.get("salt").getAsString();
		JsonObject servers = root.getAsJsonObject("servers");
		String[] bad = {"{\"profile\": \"max_fps\", \"kind\": \"REMOTE\"}", "{\"profile\": \"p-1\", \"kind\": \"SINGLEPLAYER\"}",
				"{\"profile\": \"p-1\", \"kind\": \"MARS\"}", "{\"profile\": 5, \"kind\": \"REMOTE\"}", "{\"profile\": \"p-1\"}",
				"{\"kind\": \"REALM\"}", "{\"profile\": [\"p-1\"], \"kind\": \"REMOTE\"}", "[1]", "7", "null"};
		for (int i = 0; i < bad.length; i++) {
			servers.add(ServerLimitsStore.key(salt, "bad" + i + ":1"), JsonParser.parseString(bad[i]));
		}
		servers.add(ServerLimitsStore.key(salt, "undated:1"), JsonParser.parseString("{\"profile\": \"p-4\", \"kind\": \"REALM\", \"setAt\": 3, "
				+ "\"lastSeen\": \"yesterday\"}"));
		write(root.toString());
		for (int i = 0; i < bad.length; i++) {
			assertNull(store.get("bad" + i + ":1"), bad[i]);
			assertNull(store.joined("bad" + i + ":1", T3), bad[i]);
		}
		assertEquals(new Entry(ServerLimitsStore.key(salt, "undated:1"), "p-4", ServerLimits.Kind.REALM, null, null), store.get("undated:1"));
		List<Entry> entries = store.entries();
		assertEquals(4, entries.size(), "the bad ones aren't listed");
		assertNull(entries.getLast().lastSeen(), "no readable lastSeen sorts last");
		for (int i = 0; i < ServerProfileStore.MAX_SERVERS - 4; i++) {
			assertEquals(Result.OK, store.remember(server(i), REMOTE, "p-5", T0), "the bad ones don't count: " + i);
		}
		assertEquals(Result.FULL, store.remember(server(99), REMOTE, "p-5", T0));
		// Kept until Forget all, except the null one: JsonStateFile's Gson leaves a null member out of every write.
		assertEquals(bad.length - 1 + ServerProfileStore.MAX_SERVERS, json().getAsJsonObject("servers").size());
	}

	@Test
	void aBadOrMissingSaltIsReplacedAndItsEntriesDropped() throws IOException {
		String entry = "{\"" + "a".repeat(64) + "\": {\"profile\": \"p-1\", \"kind\": \"REMOTE\"}}";
		for (String salt : new String[]{"\"salt\": \"nothex\", ", "\"salt\": \"" + "A".repeat(32) + "\", ", "\"salt\": 7, ", ""}) {
			write("{\"formatVersion\": 1, " + salt + "\"future\": {\"x\": 1}, \"servers\": " + entry + "}");
			ServerProfileStore store = store();
			assertEquals(List.of(), store.entries(), salt);
			assertNull(store.keyOf(PLAY), salt);
			assertNull(store.joined(PLAY, T0), salt);
			assertEquals(Result.OK, store.remember(PLAY, REMOTE, "p-2", T1), salt);
			JsonObject root = json();
			assertTrue(root.get("salt").getAsString().matches("[0-9a-f]{32}"), salt);
			assertEquals(List.of(ServerLimitsStore.key(root.get("salt").getAsString(), PLAY)), new ArrayList<>(root.getAsJsonObject("servers").keySet()),
					salt + ": the old salt's entries are dropped");
			assertEquals(JsonParser.parseString("{\"x\": 1}"), root.get("future"), salt + ": unknown fields survive");
		}
	}

	@Test
	void aCorruptFileIsMovedToBadAndTheStoreStartsEmpty() throws IOException {
		write("{broken");
		ServerProfileStore store = store();
		assertNull(store.get(PLAY));
		assertEquals(List.of(), store.entries());
		assertEquals("{broken", Files.readString(file().resolveSibling("server-profiles.json.bad"), StandardCharsets.UTF_8));
		assertEquals(Result.OK, store.remember(PLAY, REMOTE, "p-1", T0));
		assertEquals("p-1", store.get(PLAY).profile());
	}

	@Test
	void aNewerFileStillOffersButRefusesWrites() throws IOException {
		ServerProfileStore store = store();
		store.remember(PLAY, REMOTE, "p-1", T0);
		JsonObject root = json();
		root.addProperty("formatVersion", 2);
		write(root.toString());
		byte[] newer = Files.readAllBytes(file());
		assertFalse(store.writable());
		assertEquals(new Entry(store.keyOf(PLAY), "p-1", REMOTE, T0, T0), store.get(PLAY));
		assertEquals(new Entry(store.keyOf(PLAY), "p-1", REMOTE, T0, T0), store.joined(PLAY, T1), "offers still work");
		assertEquals(1, store.entries().size());
		assertEquals(Result.READ_ONLY, store.remember(OTHER, REMOTE, "p-1", T1));
		assertEquals(Result.READ_ONLY, store.remember(PLAY, REMOTE, "p-2", T1));
		assertEquals(Result.READ_ONLY, store.forget(PLAY));
		assertEquals(Result.READ_ONLY, store.forgetKey(store.keyOf(PLAY)));
		assertEquals(Result.READ_ONLY, store.forgetAll());
		assertEquals(0, store.forgetProfile("p-1"));
		assertArrayEquals(newer, Files.readAllBytes(file()));
	}

	@Test
	void aFileOverFourTimesTheCapIsLeftAlone() throws IOException {
		byte[] big = ("{\"formatVersion\": 1, \"pad\": \"" + "x".repeat((int) (4 * ServerProfileStore.MAX_BYTES)) + "\"}").getBytes(StandardCharsets.UTF_8);
		Files.createDirectories(file().getParent());
		Files.write(file(), big);
		ServerProfileStore store = store();
		assertEquals(List.of(), store.entries());
		assertNull(store.joined(PLAY, T0));
		// Not a newer RigTune's file as far as anyone knows: "couldn't save that (the log says why)", never "read-only".
		assertEquals(Result.FAILED, store.remember(PLAY, REMOTE, "p-1", T0));
		assertEquals(Result.FAILED, store.forget(PLAY));
		assertEquals(Result.FAILED, store.forgetKey("a".repeat(64)));
		assertEquals(Result.FAILED, store.forgetAll());
		assertEquals(0, store.forgetProfile("p-1"));
		assertArrayEquals(big, Files.readAllBytes(file()));
		assertFalse(Files.exists(file().resolveSibling("server-profiles.json.bad")));
	}

	@Test
	void aServerForgottenBetweenTheLookupAndTheWriteIsNotAnswered() throws IOException {
		ServerProfileStore store = store();
		store.remember(PLAY, REMOTE, "p-1", T0);
		store.remember(OTHER, REMOTE, "p-2", T0);
		ServerProfileStore.beforeUpdate = () -> assertEquals(Result.OK, store.forget(PLAY));
		try {
			assertNull(store.joined(PLAY, T1), "forgotten meanwhile: no offer");
		} finally {
			ServerProfileStore.beforeUpdate = null;
		}
		assertNull(store.get(PLAY));
		assertEquals(List.of(store.keyOf(OTHER)), new ArrayList<>(json().getAsJsonObject("servers").keySet()), "the rest as it was");
		assertEquals("2026-09-20T10:00:00Z", json().getAsJsonObject("servers").getAsJsonObject(store.keyOf(OTHER)).get("lastSeen").getAsString());
	}

	@Test
	void twoRemembersRacingForTheLastPlaceGiveOneOkAndOneFull() throws IOException {
		ServerProfileStore store = store();
		for (int i = 0; i < ServerProfileStore.MAX_SERVERS - 1; i++) {
			assertEquals(Result.OK, store.remember(server(i), REMOTE, "p-1", T0));
		}
		Result[] other = new Result[1];
		ServerProfileStore.beforeUpdate = () -> other[0] = store.remember(server(40), REMOTE, "p-2", T1);
		try {
			assertEquals(Result.FULL, store.remember(server(41), REMOTE, "p-3", T1), "the 32nd place went meanwhile");
		} finally {
			ServerProfileStore.beforeUpdate = null;
		}
		assertEquals(Result.OK, other[0]);
		assertEquals(ServerProfileStore.MAX_SERVERS, store.entries().size());
		assertEquals("p-2", store.get(server(40)).profile());
		assertNull(store.get(server(41)));
		assertEquals(ServerProfileStore.MAX_SERVERS, json().getAsJsonObject("servers").size());
	}

	@Test
	void aValidSaltWithServersThatIsntAnObject() throws IOException {
		String salt = "0123456789abcdef0123456789abcdef";
		for (String servers : new String[]{"[]", "5", "\"x\"", "null"}) {
			write("{\"formatVersion\": 1, \"salt\": \"" + salt + "\", \"servers\": " + servers + "}");
			ServerProfileStore store = store();
			assertEquals(List.of(), store.entries(), servers);
			assertNull(store.get(PLAY), servers);
			assertNull(store.joined(PLAY, T0), servers);
			assertEquals(ServerLimitsStore.key(salt, PLAY), store.keyOf(PLAY), servers);
			assertEquals(Result.OK, store.forget(PLAY), servers);
			assertEquals(Result.OK, store.remember(PLAY, REMOTE, "p-1", T0), servers);
			JsonObject root = json();
			assertEquals(salt, root.get("salt").getAsString(), servers + ": the salt stays");
			assertEquals(List.of(ServerLimitsStore.key(salt, PLAY)), new ArrayList<>(root.getAsJsonObject("servers").keySet()), servers);
		}
	}

	@Test
	void aRememberThatWouldPassTheCapFailsAndLeavesTheFile() throws IOException {
		store().remember(OTHER, REMOTE, "p-1", T0);
		JsonObject root = json();
		// A player-added field that leaves too little room for one more entry.
		int room = (int) ServerProfileStore.MAX_BYTES - JsonStateFile.GSON.toJson(root).getBytes(StandardCharsets.UTF_8).length - 100;
		root.addProperty("notes", "n".repeat(room));
		write(root.toString());
		byte[] before = Files.readAllBytes(file());
		assertTrue(store().writable());
		assertEquals(Result.FAILED, store().remember(PLAY, REMOTE, "p-" + "a".repeat(64), T1));
		assertArrayEquals(before, Files.readAllBytes(file()));
		assertNull(store().get(PLAY));
		assertEquals("p-1", store().get(OTHER).profile());
	}

	@Test
	void forgetKeyWithoutAKeyAndOnAnEntryThisVersionCantRead() throws IOException {
		ServerProfileStore store = store();
		store.remember(PLAY, REMOTE, "p-1", T0);
		byte[] before = Files.readAllBytes(file());
		assertEquals(Result.OK, store.forgetKey(null));
		assertArrayEquals(before, Files.readAllBytes(file()), "nothing written");
		JsonObject root = json();
		String salt = root.get("salt").getAsString();
		String unreadable = ServerLimitsStore.key(salt, OTHER);
		root.getAsJsonObject("servers").add(unreadable, JsonParser.parseString("{\"profile\": \"p-1\", \"kind\": \"MARS\"}"));
		write(root.toString());
		assertNull(store.get(OTHER));
		assertEquals(Result.OK, store.forgetKey(unreadable), "a key names whatever is there");
		assertFalse(json().getAsJsonObject("servers").has(unreadable));
		assertEquals("p-1", store.get(PLAY).profile());
	}

	@Test
	void unknownFieldsSurviveAtTheRootAndPerEntry() throws IOException {
		ServerProfileStore store = store();
		store.remember(PLAY, REMOTE, "p-1", T0);
		store.remember(OTHER, REMOTE, "p-2", T0);
		JsonObject root = json();
		root.add("future", JsonParser.parseString("{\"deep\": [1, {\"y\": true}]}"));
		root.getAsJsonObject("servers").getAsJsonObject(store.keyOf(PLAY)).add("more", JsonParser.parseString("{\"z\": [\"w\"]}"));
		write(root.toString());
		store.joined(PLAY, T1);
		store.forget(OTHER);
		store.remember(LAN, ServerLimits.Kind.LAN_GUEST, "p-3", T2);
		JsonObject after = json();
		assertEquals(JsonParser.parseString("{\"deep\": [1, {\"y\": true}]}"), after.get("future"));
		assertEquals(JsonParser.parseString("{\"z\": [\"w\"]}"), after.getAsJsonObject("servers").getAsJsonObject(store.keyOf(PLAY)).get("more"));
	}

	// ServerProfileService's notice re-check: the entry a pending offer came from, by its key.
	@Test
	void anEntryByItsKey() {
		ServerProfileStore store = store();
		assertNull(store.entry("a".repeat(64)));
		store.remember(PLAY, REMOTE, "p-1", T0);
		String key = store.keyOf(PLAY);
		assertEquals(new Entry(key, "p-1", REMOTE, T0, T0), store.entry(key));
		assertNull(store.entry("b".repeat(64)));
		assertNull(store.entry(null));
		store.forget(PLAY);
		assertNull(store.entry(key));
	}

	// One read per screen: the snapshot answers from what it read, whatever happens to the file after.
	@Test
	void aSnapshotIsOneRead() throws IOException {
		ServerProfileStore store = store();
		store.remember(PLAY, REMOTE, "p-1", T0);
		store.remember(OTHER, REMOTE, "p-2", T1);
		ServerProfileStore.Snapshot snapshot = store.snapshot();
		String play = store.keyOf(PLAY);
		Files.delete(file());
		assertTrue(snapshot.writable());
		assertEquals(play, snapshot.keyOf(PLAY));
		assertEquals(play, snapshot.entries().getLast().key(), "newest first: Other (T1), then Play (T0)");
		assertEquals(2, snapshot.entries().size());
		assertEquals("p-1", snapshot.get(PLAY).profile());
		assertNull(store.keyOf(PLAY), "the file is gone: the store itself answers from the disk");
		write("{\"formatVersion\": 2, \"future\": 1}");
		assertFalse(store.snapshot().writable(), "a newer file: read-only");
		write("{broken");
		ServerProfileStore.Snapshot corrupt = store.snapshot();
		assertTrue(corrupt.writable() && corrupt.entries().isEmpty(), "a corrupt file is moved aside: empty, writable");
	}

	@Test
	void keyOfWritesNothingAndIsNullWithoutASalt() throws IOException {
		ServerProfileStore store = store();
		assertNull(store.keyOf(PLAY));
		assertNull(store.get(PLAY));
		assertEquals(List.of(), store.entries());
		assertFalse(Files.exists(file()));
		store.remember(OTHER, REMOTE, "p-1", T0);
		Files.setLastModifiedTime(file(), FileTime.from(T0));
		String key = store.keyOf(PLAY);
		assertTrue(key.matches("[0-9a-f]{64}"), key);
		assertNull(store.get(PLAY), "a key for a server that isn't remembered");
		assertNull(store.keyOf(null));
		assertNull(store.keyOf(" "));
		assertEquals(FileTime.from(T0), Files.getLastModifiedTime(file()));
	}
}
