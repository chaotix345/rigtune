package io.github.chaotix345.rigtune.core.footprint;

import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Run;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore.Summary;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.Saved;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 13 (AC13.1): startup-times.json keeps the last 30 launches; the hub shows the last one, the median of
// the last 10 and a mod-set note only when the hash changed since the previous launch.
class StartupTimesTest {
	@TempDir
	Path configDir;

	private static Run run(int i, long ms, String hash) {
		return new Run(String.format("2026-09-%02dT10:00:00Z", 1 + i % 28), ms, "26.2", "0.4.0+mc26.2", 83, hash);
	}

	private Path file() {
		return StartupTimesStore.file(configDir);
	}

	@Test
	void recordsAndReadsBackARun() throws IOException {
		StartupTimesStore store = new StartupTimesStore(configDir);
		assertEquals(Saved.OK, store.record(run(0, 14517, "abc")));
		assertEquals(List.of(run(0, 14517, "abc")), new StartupTimesStore(configDir).runs());
		String text = Files.readString(file());
		assertTrue(text.startsWith("{\n  \"formatVersion\": 1,\n  \"runs\": ["), text);
		assertTrue(text.contains("\"ms\": 14517"), text);
	}

	@Test
	void keepsOnlyTheLast30Runs() {
		StartupTimesStore store = new StartupTimesStore(configDir);
		for (int i = 0; i < 35; i++) {
			assertEquals(Saved.OK, store.record(run(i, 10_000 + i, "h")));
		}
		List<Run> runs = new StartupTimesStore(configDir).runs();
		assertEquals(StartupTimesStore.MAX_RUNS, runs.size());
		assertEquals(10_005, runs.getFirst().ms());
		assertEquals(10_034, runs.getLast().ms());
	}

	@Test
	void theFileStaysUnder16KiBWithLongStrings() throws IOException {
		StartupTimesStore store = new StartupTimesStore(configDir);
		String longVersion = "0.4.0+mc26.2-" + "x".repeat(400);
		for (int i = 0; i < 40; i++) {
			store.record(new Run("2026-09-01T10:00:00Z", 12_000 + i, "26.2", longVersion, 83, "h"));
		}
		assertTrue(Files.size(file()) <= StartupTimesStore.MAX_BYTES, "size " + Files.size(file()));
		List<Run> runs = store.runs();
		assertFalse(runs.isEmpty());
		assertEquals(12_039, runs.getLast().ms());
	}

	@Test
	void aCorruptFileIsMovedAsideAndStartsEmpty() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"formatVersion\": 1, \"runs\": [{\"ms\": \"soon\"}", StandardCharsets.UTF_8);
		StartupTimesStore store = new StartupTimesStore(configDir);
		assertEquals(List.of(), store.runs());
		assertTrue(Files.isRegularFile(file().resolveSibling(StartupTimesStore.FILE_NAME + ".bad")));
		assertEquals(Saved.OK, store.record(run(1, 13_000, "h")));
		assertEquals(1, store.runs().size());
	}

	@Test
	void aNewerFileIsReadButNeverOverwritten() throws IOException {
		Files.createDirectories(file().getParent());
		String newer = "{\"formatVersion\": 2, \"runs\": [{\"at\": \"2026-09-01T10:00:00Z\", \"ms\": 15000, \"mods\": 3}], \"future\": true}";
		Files.writeString(file(), newer, StandardCharsets.UTF_8);
		StartupTimesStore store = new StartupTimesStore(configDir);
		assertEquals(15_000, store.runs().getFirst().ms());
		assertEquals(Saved.READ_ONLY, store.record(run(1, 13_000, "h")));
		assertEquals(newer, Files.readString(file()));
	}

	@Test
	void unknownTopLevelFieldsSurviveARewrite() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"formatVersion\": 1, \"runs\": [], \"note\": \"kept\"}", StandardCharsets.UTF_8);
		new StartupTimesStore(configDir).record(run(1, 13_000, "h"));
		assertTrue(Files.readString(file()).contains("\"note\": \"kept\""));
	}

	@Test
	void invalidRunsInTheFileAreIgnored() throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "{\"formatVersion\": 1, \"runs\": [null, {\"ms\": 0, \"at\": \"x\"}, {\"ms\": 900}, "
				+ "{\"at\": \"2026-09-01T10:00:00Z\", \"ms\": 14000, \"mods\": 2}]}", StandardCharsets.UTF_8);
		List<Run> runs = new StartupTimesStore(configDir).runs();
		assertEquals(1, runs.size());
		assertEquals(14_000, runs.getFirst().ms());
	}

	@Test
	void summaryOfNoRunsIsEmpty() {
		Summary s = StartupTimesStore.summarize(List.of());
		assertNull(s.lastMs());
		assertNull(s.medianMs());
		assertEquals(0, s.runs());
		assertFalse(s.modSetChanged());
	}

	@Test
	void medianIsOverTheLast10Runs() {
		List<Run> runs = new ArrayList<>();
		// 12 runs: two very slow old ones fall out of the last 10.
		runs.add(run(0, 90_000, "h"));
		runs.add(run(1, 80_000, "h"));
		long[] recent = {14_000, 15_000, 13_000, 16_000, 14_500, 13_500, 15_500, 14_200, 30_000, 12_000};
		for (int i = 0; i < recent.length; i++) {
			runs.add(run(2 + i, recent[i], "h"));
		}
		Summary s = StartupTimesStore.summarize(runs);
		assertEquals(12_000L, s.lastMs());
		// sorted: 12000 13000 13500 14000 14200 14500 15000 15500 16000 30000 -> (14200 + 14500) / 2
		assertEquals(14_350L, s.medianMs());
		assertEquals(12, s.runs());
	}

	@Test
	void medianOfAnOddCountIsTheMiddleRun() {
		Summary s = StartupTimesStore.summarize(List.of(run(0, 14_000, "h"), run(1, 20_000, "h"), run(2, 15_000, "h")));
		assertEquals(15_000L, s.medianMs());
		assertEquals(15_000L, s.lastMs());
	}

	@Test
	void theModSetNoteOnlyWhenTheHashDiffersFromThePreviousLaunch() {
		assertFalse(StartupTimesStore.summarize(List.of(run(0, 1000, "a"))).modSetChanged());
		assertFalse(StartupTimesStore.summarize(List.of(run(0, 1000, "a"), run(1, 1000, "a"))).modSetChanged());
		assertTrue(StartupTimesStore.summarize(List.of(run(0, 1000, "a"), run(1, 1000, "b"))).modSetChanged());
		// Only the last two launches count.
		assertFalse(StartupTimesStore.summarize(List.of(run(0, 1000, "a"), run(1, 1000, "b"), run(2, 1000, "b"))).modSetChanged());
		// An unknown hash on either side is not a change.
		assertFalse(StartupTimesStore.summarize(List.of(run(0, 1000, null), run(1, 1000, "b"))).modSetChanged());
		assertFalse(StartupTimesStore.summarize(List.of(run(0, 1000, "a"), run(1, 1000, null))).modSetChanged());
	}
}
