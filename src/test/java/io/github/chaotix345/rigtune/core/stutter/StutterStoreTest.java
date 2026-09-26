package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.5 (StutterStoreTest): 64 KiB, 5 sessions, corrupt -> .bad and empty, a newer formatVersion ignored and untouched.
class StutterStoreTest {
	@TempDir
	Path dir;

	static StutterReport report(String startedAt, int worst, int notesPerSpike) {
		List<StutterReport.Worst> w = new ArrayList<>();
		for (int i = 0; i < worst; i++) {
			w.add(new StutterReport.Worst(10.0 + i, 80.0 - i, 7.1, Collections.nCopies(notesPerSpike, "gc:high:FULL:EXPLICIT")));
		}
		return new StutterReport(startedAt, StutterReport.MONITOR, "26.2", "G1", 4096, 900, 812.5, 97000, 119.4, 61.2,
				new long[]{1, 2, 3, 4, 5, 6, 7, 8, 9}, new long[]{10, 20, 30, 40, 50, 60, 70, 80, 90}, new StutterReport.Spikes(9, 2, 1, 0), 1810.0,
				Map.of("gc", 0.44, "unknown", 0.56), Map.of("worldSave", 7), w, new StutterReport.Facts(81, 2, 0, 0, 21.7), List.of("ram-stutter-gc-heap"), true,
				true, 10);
	}

	@Test
	void missingIsEmptyAndASessionRoundTrips() {
		StutterStore store = new StutterStore(dir);
		assertEquals(List.of(), store.sessions());
		assertNull(store.latest());
		StutterReport r = report("2026-09-26T10:00:00Z", 3, 1);
		assertEquals(JsonStateFile.Saved.OK, store.add(r));
		List<StutterReport> back = new StutterStore(dir).sessions();
		assertEquals(1, back.size());
		StutterReport got = back.getFirst();
		assertEquals(r.startedAt(), got.startedAt());
		assertEquals(r.worst(), got.worst());
		assertEquals(r.facts(), got.facts());
		assertEquals(r.causes(), got.causes());
		assertArrayEquals(r.histogramCounts(), got.histogramCounts());
		assertEquals(r.spikes(), got.spikes());
		assertEquals(List.of("ram-stutter-gc-heap"), got.advice());
	}

	@Test
	void keepsTheLastFiveSessionsWithTenWorstEach() {
		StutterStore store = new StutterStore(dir);
		for (int i = 1; i <= 7; i++) {
			assertEquals(JsonStateFile.Saved.OK, store.add(report("2026-09-2" + i + "T10:00:00Z", 15, 1)));
		}
		List<StutterReport> sessions = store.sessions();
		assertEquals(StutterStore.MAX_SESSIONS, sessions.size());
		assertEquals("2026-09-23T10:00:00Z", sessions.getFirst().startedAt(), "the two oldest dropped");
		assertEquals("2026-09-27T10:00:00Z", store.latest().startedAt());
		assertTrue(sessions.stream().allMatch(s -> s.worst().size() == StutterReport.MAX_WORST));
	}

	@Test
	void theFileStaysUnder64KiBDroppingTheOldestFirst() throws IOException {
		StutterStore store = new StutterStore(dir);
		for (int i = 1; i <= 5; i++) {
			store.add(report("2026-09-2" + i + "T10:00:00Z", 10, 60));
		}
		long size = Files.size(store.path());
		assertTrue(size <= StutterStore.MAX_BYTES, size + " bytes");
		List<StutterReport> sessions = store.sessions();
		assertTrue(sessions.size() < 5, "some didn't fit: " + sessions.size());
		assertEquals("2026-09-25T10:00:00Z", sessions.getLast().startedAt(), "the newest is always kept");
	}

	@Test
	void aCorruptFileIsMovedAsideAndStartsEmpty() throws IOException {
		Path file = StutterStore.file(dir);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"formatVersion\": 1, \"sessions\": [", StandardCharsets.UTF_8);
		StutterStore store = new StutterStore(dir);
		assertEquals(List.of(), store.sessions());
		assertTrue(Files.isRegularFile(file.resolveSibling("stutter.json.bad")));
		assertEquals(JsonStateFile.Saved.OK, store.add(report("2026-09-26T10:00:00Z", 1, 1)));
		assertEquals(1, store.sessions().size());
	}

	@Test
	void aNewerFileIsIgnoredAndNeverWritten() throws IOException {
		Path file = StutterStore.file(dir);
		Files.createDirectories(file.getParent());
		String newer = "{\"formatVersion\": 2, \"sessions\": [{\"startedAt\": \"x\", \"frames\": 5}], \"future\": true}";
		Files.writeString(file, newer, StandardCharsets.UTF_8);
		StutterStore store = new StutterStore(dir);
		assertEquals(List.of(), store.sessions());
		assertEquals(JsonStateFile.Saved.READ_ONLY, store.add(report("2026-09-26T10:00:00Z", 1, 1)));
		assertEquals(JsonStateFile.Saved.READ_ONLY, store.clear());
		assertEquals(newer, Files.readString(file, StandardCharsets.UTF_8));
	}

	@Test
	void unknownTopLevelFieldsSurviveAndClearKeepsThem() throws IOException {
		Path file = StutterStore.file(dir);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"formatVersion\": 1, \"sessions\": [null], \"fromTheFuture\": {\"a\": 1}}", StandardCharsets.UTF_8);
		StutterStore store = new StutterStore(dir);
		assertEquals(List.of(), store.sessions(), "a null session is skipped");
		store.add(report("2026-09-26T10:00:00Z", 1, 1));
		assertTrue(Files.readString(file).contains("fromTheFuture"));
		assertEquals(JsonStateFile.Saved.OK, store.clear());
		assertEquals(List.of(), store.sessions());
		assertTrue(Files.readString(file).contains("fromTheFuture"));
	}

	// A hand-edited file: missing fields and nulls read back as empty values, and the summary and screen code never meets
	// a null.
	@Test
	void aHandEditedSessionWithNullsReadsSafely() throws IOException {
		Path file = StutterStore.file(dir);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"formatVersion\": 1, \"sessions\": [{\"frames\": 5, \"worst\": [null, {\"ms\": 30, \"causes\": [null, \"gc:high\"]}], "
				+ "\"advice\": [null], \"causes\": {\"gc\": null}}]}", StandardCharsets.UTF_8);
		StutterReport r = new StutterStore(dir).latest();
		assertEquals(5, r.frames());
		assertEquals(1, r.worst().size());
		assertEquals(List.of("gc:high"), r.worst().getFirst().causes());
		assertEquals(List.of(), r.advice());
		assertTrue(StutterSummary.text(r, List.of()).startsWith("**RigTune Stutter Doctor** · session"));
	}

	@Test
	void clearWithoutAFileWritesNothing() {
		StutterStore store = new StutterStore(dir);
		assertEquals(JsonStateFile.Saved.OK, store.clear());
		assertTrue(!Files.exists(store.path()));
	}
}
