package io.github.chaotix345.rigtune.core.tryit;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md AC6.10 (X7): tryit.json's typed model on WS-K's shell. The open try and the closed ones, every value
// type-checked (players edit the file), unknown fields kept at any depth, `recent` at most 10 and within the 16 KiB cap,
// a newer file never written. WS-K's V05StoreShellsTest covers the shell's own contract.
class TryItStoreTest {
	@TempDir
	Path config;

	static TryIt sample(String entryId) {
		Map<String, String> before = new LinkedHashMap<>();
		before.put("vanilla.renderDistance", "12");
		before.put("sodium.performance.chunk_build_defer_mode", "ALWAYS");
		return TryIt.of(entryId, "setting:sodium.performance.chunk_build_defer_mode", "sodium.performance.chunk_build_defer_mode", "ALWAYS",
				"ONE_FRAME", TryIt.Kind.RESTART, BenchmarkRequest.Scene.BENCHMARK_WORLD, "2026-09-20T10:00:00Z", "session-a", "0.5.0", "26.2",
				before);
	}

	private TryItStore store() {
		return TryItStore.shared(config);
	}

	private Path file() {
		return TryItStore.file(config);
	}

	private void write(String text) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), text, StandardCharsets.UTF_8);
	}

	private JsonObject onDisk() throws IOException {
		return JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static TryIt.Closed closed(TryIt t, TryIt.Decision decision, String at) {
		return TryIt.Closed.of(t, decision, "better", 11.4, 8.0, 6.2, at);
	}

	@Test
	void aMissingFileHasNoTryAndIsWritable() {
		assertNull(store().current());
		assertEquals(List.of(), store().recent());
		assertTrue(store().writable());
		assertFalse(Files.exists(file()), "reading writes nothing");
	}

	@Test
	void theIdsAreMinted() {
		TryIt t = sample("e-1");
		assertTrue(t.id().startsWith(TryIt.ID_PREFIX), t.id());
		assertTrue(t.pairId().startsWith(TryIt.PAIR_PREFIX), t.pairId());
		assertEquals("tryit-", TryIt.PAIR_PREFIX);
		assertFalse(t.id().equals(sample("e-1").id()), "a new id each time");
		assertNull(t.settingsAfter());
		assertNull(t.afterSession());
		assertNull(t.afterRunId());
	}

	@Test
	void everyFieldRoundTrips() throws IOException {
		TryIt t = sample("e-1").withAfter(Map.of("vanilla.renderDistance", "12"), "session-b").withAfterRun("run-7");
		assertTrue(store().open(t));
		assertEquals(t, store().current());
		JsonObject current = onDisk().getAsJsonObject("current");
		assertEquals(1, onDisk().get("formatVersion").getAsInt());
		assertEquals("restart", current.get("kind").getAsString());
		assertEquals("BENCHMARK_WORLD", current.get("scene").getAsString());
		assertEquals("12", current.getAsJsonObject("settingsBefore").get("vanilla.renderDistance").getAsString());
		assertEquals("session-b", current.get("afterSession").getAsString());

		TryIt now = TryIt.of("e-2", "setting:vanilla.renderDistance", "vanilla.renderDistance", "16", "12", TryIt.Kind.NOW,
				BenchmarkRequest.Scene.CURRENT, "2026-09-20T10:00:00Z", "session-a", "0.5.0", "26.2", Map.of());
		assertTrue(store().close(t.id(), closed(t, TryIt.Decision.KEPT, "2026-09-21T10:00:00Z")));
		assertTrue(store().open(now));
		assertEquals(now, store().current());
		assertEquals("now", onDisk().getAsJsonObject("current").get("kind").getAsString());
	}

	@Test
	void aSecondTryCantOpenWhileOneIsOpen() throws IOException {
		TryIt a = sample("e-1");
		assertTrue(store().open(a));
		byte[] bytes = Files.readAllBytes(file());
		assertFalse(store().open(sample("e-2")));
		assertEquals(a, store().current());
		assertArrayEquals(bytes, Files.readAllBytes(file()), "a refused open writes nothing");
	}

	@Test
	void changeUpdatesOnlyTheOpenTry() {
		TryIt a = sample("e-1");
		assertFalse(store().change(a.id(), t -> t.withAfterRun("x")), "no try open");
		assertTrue(store().open(a));
		assertFalse(store().change("t-other", t -> t.withAfterRun("x")));
		assertTrue(store().change(a.id(), t -> t.withAfter(Map.of("vanilla.renderDistance", "10"), "session-b")));
		TryIt changed = store().current();
		assertNotNull(changed);
		assertEquals(Map.of("vanilla.renderDistance", "10"), changed.settingsAfter());
		assertEquals("session-b", changed.afterSession());
		assertEquals(a.settingsBefore(), changed.settingsBefore());
	}

	@Test
	void closeMovesTheTryToRecentNewestFirst() throws IOException {
		TryIt a = sample("e-1");
		assertFalse(store().close(a.id(), closed(a, TryIt.Decision.KEPT, "2026-09-21T10:00:00Z")), "nothing open");
		assertTrue(store().open(a));
		assertFalse(store().close("t-other", closed(a, TryIt.Decision.KEPT, "2026-09-21T10:00:00Z")));
		assertTrue(store().close(a.id(), closed(a, TryIt.Decision.REVERTED, "2026-09-21T10:00:00Z")));
		assertNull(store().current());
		assertFalse(onDisk().has("current"));
		TryIt b = sample("e-2");
		assertTrue(store().open(b));
		assertTrue(store().close(b.id(), closed(b, TryIt.Decision.CANCELLED, "2026-09-22T10:00:00Z")));
		List<TryIt.Closed> recent = store().recent();
		assertEquals(List.of(b.id(), a.id()), recent.stream().map(TryIt.Closed::id).toList());
		TryIt.Closed first = recent.getFirst();
		assertEquals(TryIt.Decision.CANCELLED, first.decision());
		assertEquals("sodium.performance.chunk_build_defer_mode", first.key());
		assertEquals("ALWAYS", first.from());
		assertEquals("ONE_FRAME", first.to());
		assertEquals("better", first.verdict());
		assertEquals(11.4, first.lowPercent());
		assertEquals(8.0, first.avgPercent());
		assertEquals(6.2, first.floorPercent());
		assertEquals("2026-09-22T10:00:00Z", first.at());
		assertEquals("cancelled", onDisk().getAsJsonArray("recent").get(0).getAsJsonObject().get("decision").getAsString());
		assertEquals(recent.getLast(), closed(a, TryIt.Decision.REVERTED, "2026-09-21T10:00:00Z"));
	}

	@Test
	void recentKeepsTheNewestTen() {
		for (int i = 0; i < 12; i++) {
			TryIt t = sample("e-" + i);
			assertTrue(store().open(t));
			assertTrue(store().close(t.id(), closed(t, TryIt.Decision.KEPT, "2026-09-2" + (i % 10) + "T10:00:00Z")));
		}
		List<TryIt.Closed> recent = store().recent();
		assertEquals(TryItStore.MAX_RECENT, recent.size());
		assertEquals(10, TryItStore.MAX_RECENT);
	}

	@Test
	void recentIsPrunedToTheByteCapOldestFirst() throws IOException {
		// Newest first: t-old0 is the newest of the seeded rows, t-old7 the oldest.
		JsonArray recent = new JsonArray();
		String filler = "x".repeat(1900);
		for (int i = 0; i < 8; i++) {
			JsonObject row = new JsonObject();
			row.addProperty("id", "t-old" + i);
			row.addProperty("key", "vanilla.renderDistance");
			row.addProperty("decision", "kept");
			row.addProperty("at", "2026-09-1" + (8 - i) + "T10:00:00Z");
			row.addProperty("note", filler);
			recent.add(row);
		}
		JsonObject root = new JsonObject();
		root.addProperty("formatVersion", 1);
		root.add("recent", recent);
		write(root.toString());
		assertTrue(Files.size(file()) < TryItStore.MAX_BYTES, "the seed itself fits");

		TryIt t = sample("e-1");
		assertTrue(store().open(t), "opening makes room");
		assertTrue(Files.size(file()) <= TryItStore.MAX_BYTES, Files.size(file()) + " bytes");
		assertTrue(store().close(t.id(), closed(t, TryIt.Decision.KEPT, "2026-09-25T10:00:00Z")));
		assertTrue(Files.size(file()) <= TryItStore.MAX_BYTES, Files.size(file()) + " bytes");
		List<String> ids = store().recent().stream().map(TryIt.Closed::id).toList();
		assertEquals(t.id(), ids.getFirst(), "the newest stays");
		assertEquals("t-old0", ids.get(1));
		assertTrue(ids.size() < 9, ids.toString());
		assertFalse(ids.contains("t-old7"), "the oldest go first");
	}

	@Test
	void aNewerFileIsReadOnly() throws IOException {
		TryIt a = sample("e-1");
		JsonObject root = new JsonObject();
		root.addProperty("formatVersion", 2);
		root.add("current", a.toJson(new JsonObject()));
		write(root.toString());
		byte[] bytes = Files.readAllBytes(file());
		assertFalse(store().writable());
		assertFalse(store().open(sample("e-2")));
		assertFalse(store().change(a.id(), t -> t.withAfterRun("x")));
		assertFalse(store().close(a.id(), closed(a, TryIt.Decision.KEPT, "2026-09-21T10:00:00Z")));
		assertArrayEquals(bytes, Files.readAllBytes(file()), "never written");
	}

	@Test
	void aCorruptFileIsMovedAsideAndATryCanStart() throws IOException {
		write("{ not json");
		assertNull(store().current());
		assertTrue(Files.exists(file().resolveSibling("tryit.json.bad")));
		assertTrue(store().open(sample("e-1")));
	}

	@Test
	void handEditedWrongTypesAreIgnored() throws IOException {
		String good = sample("e-1").toJson(new JsonObject()).toString();
		for (String bad : List.of("\"kind\": 3", "\"kind\": \"later\"", "\"scene\": \"MOON\"", "\"pairId\": \"abc\"", "\"pairId\": 7",
				"\"entryId\": null", "\"entryId\": \"\"", "\"id\": {}", "\"key\": [1]", "\"session\": 1")) {
			JsonObject current = JsonParser.parseString(good).getAsJsonObject();
			JsonObject edit = JsonParser.parseString("{" + bad + "}").getAsJsonObject();
			edit.entrySet().forEach(e -> current.add(e.getKey(), e.getValue()));
			write("{\"formatVersion\": 1, \"current\": " + current + "}");
			assertNull(store().current(), bad);
		}
		write("{\"formatVersion\": 1, \"current\": [1, 2]}");
		assertNull(store().current());

		JsonObject current = JsonParser.parseString(good).getAsJsonObject();
		current.add("settingsBefore", JsonParser.parseString("{\"vanilla.renderDistance\": 12, \"vanilla.particles\": \"1\", \"vanilla.biomeBlendRadius\": null}"));
		current.addProperty("settingsAfter", "x");
		current.addProperty("afterRunId", 5);
		current.add("from", JsonParser.parseString("[\"a\"]"));
		write("{\"formatVersion\": 1, \"current\": " + current + ", \"recent\": [3, \"x\", {\"id\": \"t-1\"}, {\"id\": \"t-2\", \"key\": \"vanilla.renderDistance\","
				+ " \"decision\": \"kept\", \"at\": \"2026-09-20T10:00:00Z\", \"lowPercent\": \"high\", \"verdict\": 2}, {\"id\": \"t-3\","
				+ " \"key\": \"vanilla.renderDistance\", \"decision\": \"sometimes\", \"at\": \"2026-09-20T10:00:00Z\"}]}");
		TryIt read = store().current();
		assertNotNull(read);
		assertEquals(Map.of("vanilla.particles", "1"), read.settingsBefore());
		assertNull(read.settingsAfter());
		assertNull(read.afterRunId());
		assertNull(read.from());
		List<TryIt.Closed> recent = store().recent();
		assertEquals(List.of("t-2"), recent.stream().map(TryIt.Closed::id).toList());
		assertNull(recent.getFirst().lowPercent());
		assertNull(recent.getFirst().verdict());
	}

	@Test
	void unknownFieldsAtAnyDepthSurvive() throws IOException {
		TryIt a = sample("e-1");
		JsonObject current = a.toJson(new JsonObject());
		current.add("future", JsonParser.parseString("{\"deep\": {\"x\": 1}}"));
		current.getAsJsonObject("settingsBefore").addProperty("mod.future.key", "7");
		JsonObject row = new JsonObject();
		row.addProperty("id", "t-old");
		row.addProperty("key", "vanilla.renderDistance");
		row.addProperty("decision", "kept");
		row.addProperty("at", "2026-09-19T10:00:00Z");
		row.add("extra", JsonParser.parseString("{\"y\": [1, 2]}"));
		JsonArray recent = new JsonArray();
		recent.add(row);
		JsonObject root = new JsonObject();
		root.addProperty("formatVersion", 1);
		root.add("current", current);
		root.add("recent", recent);
		root.add("topLevel", JsonParser.parseString("{\"z\": true}"));
		write(root.toString());

		assertTrue(store().change(a.id(), t -> t.withAfter(Map.of("vanilla.renderDistance", "10"), "session-b")));
		JsonObject disk = onDisk();
		assertEquals(1, disk.getAsJsonObject("current").getAsJsonObject("future").getAsJsonObject("deep").get("x").getAsInt());
		assertEquals("7", disk.getAsJsonObject("current").getAsJsonObject("settingsBefore").get("mod.future.key").getAsString());
		assertEquals("10", disk.getAsJsonObject("current").getAsJsonObject("settingsAfter").get("vanilla.renderDistance").getAsString());

		assertTrue(store().close(a.id(), closed(a, TryIt.Decision.KEPT, "2026-09-21T10:00:00Z")));
		disk = onDisk();
		assertTrue(disk.getAsJsonObject("topLevel").get("z").getAsBoolean());
		JsonObject kept = disk.getAsJsonArray("recent").get(1).getAsJsonObject();
		assertEquals("t-old", kept.get("id").getAsString());
		assertEquals(2, kept.getAsJsonObject("extra").getAsJsonArray("y").get(1).getAsInt());
	}

	@Test
	void aSnapshotKeepsOnlyManagedKeysWithSafeValues() {
		Map<String, String> before = new LinkedHashMap<>();
		before.put("vanilla.renderDistance", "12");
		before.put("vanilla.graphicsPreset", "fancy");
		before.put("vanilla.fov", "70");
		before.put("vanilla.maxFps", "260\n");
		before.put("iris.enableShaders", "true");
		TryIt t = TryIt.of("e-1", null, "vanilla.renderDistance", "12", "10", TryIt.Kind.NOW, BenchmarkRequest.Scene.CURRENT, null, "s", null,
				null, before);
		assertEquals(Map.of("vanilla.renderDistance", "12", "iris.enableShaders", "true"), t.settingsBefore());
		assertEquals(Map.of("vanilla.renderDistance", "12"), t.withAfter(Map.of("vanilla.renderDistance", "12", "vanilla.fov", "80"), "s")
				.settingsAfter());
		assertEquals(List.of("vanilla.renderDistance", "iris.enableShaders"), List.copyOf(t.settingsBefore().keySet()), "order kept");
	}
}
