package io.github.chaotix345.rigtune.core.benchmark;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkHistoryTest {
	@TempDir
	Path configDir;

	private Path file() {
		return BenchmarkHistory.defaultPath(configDir);
	}

	static BenchmarkRecord record(String id, String mode, String scene, String phase, String pairId, String mc) {
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(12, 10, 200.0, 120.0, 9.5));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(8, 8, null, null, null));
		Map<String, BenchmarkRecord.Cost> costs = new LinkedHashMap<>();
		costs.put(BenchmarkRecord.DISTANT_HORIZONS, new BenchmarkRecord.Cost(150, 90, 170, 101));
		return new BenchmarkRecord(id, "2026-09-25T10:00:00Z", "0.2.0+mc" + mc, mc, mode, scene, phase, pairId, 170, true, knobs,
				new BenchmarkRecord.Result(210.5, 121.25, 9.25, 2, 0.02), costs,
				scene.equals("BENCHMARK_WORLD") ? new BenchmarkRecord.World("rigtune-benchmark", 8675309L) : null, false);
	}

	static BenchmarkRecord single(String id) {
		return record(id, "TUNE", "CURRENT", BenchmarkRecord.SINGLE, null, "26.2");
	}

	@Test
	void defaultPathIsInTheRigTuneFolder() {
		assertEquals(configDir.resolve("rigtune").resolve("benchmarks.json"), file());
	}

	@Test
	void missingFileIsEmpty() {
		assertTrue(BenchmarkHistory.load(file()).runs().isEmpty());
	}

	@Test
	void roundTripKeepsEveryField() throws IOException {
		BenchmarkRecord a = record("a", "MEASURE", "BENCHMARK_WORLD", BenchmarkRecord.BEFORE, "p1", "26.3");
		BenchmarkRecord b = single("b");
		BenchmarkHistory.empty().with(a).with(b).save(file());
		assertEquals(List.of(a, b), BenchmarkHistory.load(file()).runs());
	}

	@Test
	void capsAtFiftyDroppingOldest() {
		BenchmarkHistory history = BenchmarkHistory.empty();
		for (int i = 0; i < 55; i++) {
			history = history.with(single("r" + i));
		}
		assertEquals(50, history.runs().size());
		assertEquals("r5", history.runs().getFirst().id());
		assertEquals("r54", history.latest().orElseThrow().id());
	}

	@Test
	void corruptFileIsMovedAsideAndEmpty() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"schemaVersion\": 1, \"runs\": [ {\"id\": ");
		assertTrue(BenchmarkHistory.load(file()).runs().isEmpty());
		assertFalse(Files.exists(file()));
		assertTrue(Files.exists(file().resolveSibling("benchmarks.json.bad")));
	}

	@Test
	void invalidUtf8CountsAsCorrupt() throws IOException {
		Files.createDirectories(file().getParent());
		Files.write(file(), new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xC3});
		BenchmarkHistory history = BenchmarkHistory.load(file());
		assertTrue(history.runs().isEmpty());
		assertFalse(history.unreadable());
		assertTrue(Files.exists(file().resolveSibling("benchmarks.json.bad")));
	}

	@Test
	void corruptFileThatCantBeMovedAsideIsNeverOverwritten() throws IOException {
		Path bad = file().resolveSibling("benchmarks.json.bad");
		Files.createDirectories(bad);
		Files.writeString(bad.resolve("in-the-way.txt"), "x");
		Files.writeString(file(), "{broken");
		BenchmarkHistory history = BenchmarkHistory.load(file());
		assertTrue(history.runs().isEmpty());
		assertTrue(history.unreadable());
		assertThrows(IOException.class, () -> history.with(single("a")).save(file()));
		assertEquals("{broken", Files.readString(file()));
	}

	@Test
	void readableHistoryIsNotFlagged() throws IOException {
		BenchmarkHistory.empty().with(single("a")).save(file());
		assertFalse(BenchmarkHistory.load(file()).unreadable());
	}

	@Test
	void wrongShapeCountsAsCorrupt() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "[1, 2, 3]");
		assertTrue(BenchmarkHistory.load(file()).runs().isEmpty());
		assertTrue(Files.exists(file().resolveSibling("benchmarks.json.bad")));
	}

	@Test
	void newerSchemaIsIgnoredAndNotOverwrittenByLoad() throws IOException {
		Files.createDirectories(file().getParent());
		String newer = "{\"schemaVersion\": 2, \"runs\": [], \"somethingNew\": true}";
		Files.writeString(file(), newer);
		assertTrue(BenchmarkHistory.load(file()).runs().isEmpty());
		assertEquals(newer, Files.readString(file()));
		assertFalse(Files.exists(file().resolveSibling("benchmarks.json.bad")));
	}

	@Test
	void saveReplacesNewerSchemaAfterASuccessfulRun() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"schemaVersion\": 2, \"runs\": []}");
		BenchmarkHistory.load(file()).with(single("a")).save(file());
		assertEquals(List.of(single("a")), BenchmarkHistory.load(file()).runs());
	}

	@Test
	void unknownFieldsIgnored() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"schemaVersion\": 1, \"extra\": 5, \"runs\": [{\"id\": \"x\", \"mode\": \"TUNE\", \"future\": [1]}]}");
		List<BenchmarkRecord> runs = BenchmarkHistory.load(file()).runs();
		assertEquals(1, runs.size());
		assertEquals("x", runs.getFirst().id());
		assertTrue(runs.getFirst().knobs().isEmpty());
		assertTrue(runs.getFirst().costs().isEmpty());
	}

	@Test
	void savedFileHasSchemaVersion1() throws IOException {
		BenchmarkHistory.empty().with(single("a")).save(file());
		JsonObject json = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8)).getAsJsonObject();
		assertEquals(1, json.get("schemaVersion").getAsInt());
		assertEquals(1, json.getAsJsonArray("runs").size());
		assertEquals("CURRENT", json.getAsJsonArray("runs").get(0).getAsJsonObject().get("scene").getAsString());
	}

	@Test
	void openBeforeFindsUnpairedBeforeOfSameSceneAndVersion() {
		BenchmarkRecord before = record("b1", "MEASURE", "CURRENT", BenchmarkRecord.BEFORE, "p1", "26.2");
		BenchmarkHistory history = BenchmarkHistory.empty().with(before).with(single("t"));
		assertEquals(Optional.of(before), history.openBefore("CURRENT", "26.2"));
		assertEquals(Optional.of(before), history.before("p1"));
	}

	@Test
	void openBeforeIgnoresPairedAndOtherScene() {
		BenchmarkHistory history = BenchmarkHistory.empty()
				.with(record("b1", "MEASURE", "CURRENT", BenchmarkRecord.BEFORE, "p1", "26.2"))
				.with(record("a1", "MEASURE", "CURRENT", BenchmarkRecord.AFTER, "p1", "26.2"))
				.with(record("b2", "MEASURE", "BENCHMARK_WORLD", BenchmarkRecord.BEFORE, "p2", "26.2"))
				.with(record("b3", "MEASURE", "CURRENT", BenchmarkRecord.BEFORE, "p3", "26.3"));
		assertEquals(Optional.empty(), history.openBefore("CURRENT", "26.2"));
		assertEquals("b2", history.openBefore("BENCHMARK_WORLD", "26.2").orElseThrow().id());
		assertEquals("b3", history.openBefore("CURRENT", "26.3").orElseThrow().id());
	}

	@Test
	void chartReturnsNewestOfSceneOldestFirst() {
		BenchmarkHistory history = BenchmarkHistory.empty();
		for (int i = 0; i < 14; i++) {
			history = history.with(record("c" + i, "TUNE", "CURRENT", BenchmarkRecord.SINGLE, null, "26.2"));
			history = history.with(record("w" + i, "TUNE", "BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, "26.2"));
		}
		history = history.with(record("other", "TUNE", "CURRENT", BenchmarkRecord.SINGLE, null, "26.3"));
		List<BenchmarkRecord> chart = history.chart("CURRENT", "26.2", 10);
		assertEquals(10, chart.size());
		assertEquals("c4", chart.getFirst().id());
		assertEquals("c13", chart.getLast().id());
		assertEquals(List.of("other"), history.chart("CURRENT", "26.3", 10).stream().map(BenchmarkRecord::id).toList());
	}

	@Test
	void chartSkipsRunsWithoutAResult() {
		BenchmarkRecord empty = new BenchmarkRecord("e", "t", "v", "26.2", "TUNE", "CURRENT", BenchmarkRecord.SINGLE, null, 60, false,
				Map.of(), null, Map.of(), null, true);
		BenchmarkHistory history = BenchmarkHistory.empty().with(single("a")).with(empty);
		assertEquals(List.of(single("a")), history.chart("CURRENT", "26.2", 10));
	}
}
