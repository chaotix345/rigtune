package io.github.chaotix345.rigtune.core;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md C1 (PLAN contracts item 10): the optional fields 0.5 adds to existing files. Each round-trips, is
// null when absent (older files), isn't written while null, the old constructors still build the 0.4 shape, and the
// pinned 0.2.0/0.3.0 readers still read a file that carries them. stutter.json's fields: StutterReportSettingsTest.
class V05OptionalFieldsTest {
	@TempDir
	Path dir;

	private static Journal journal(Path dir) {
		return new Journal(dir, "0.5.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	@Test
	void journalEntryFoldedEntryIds() throws Exception {
		JournalChange setting = JournalChange.setting("vanilla.renderDistance", "12", "8", JournalChange.APPLIED, null);
		JournalEntry plain = new JournalEntry("entry-1", "2026-09-26T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null, List.of(setting));
		assertNull(plain.foldedEntryIds());
		JournalEntry folded = plain.withFoldedEntryIds(List.of("entry-0", "entry-00"));
		assertEquals(List.of("entry-0", "entry-00"), folded.foldedEntryIds());
		List<String> withNull = new ArrayList<>(List.of("a"));
		withNull.add(null);
		assertEquals(List.of("a"), plain.withFoldedEntryIds(withNull).foldedEntryIds(), "a hand-edited null is dropped");

		Journal journal = journal(dir);
		assertTrue(journal.update(entries -> List.of(plain)));
		assertFalse(Files.readString(Journal.file(dir)).contains("foldedEntryIds"), "a null isn't written");
		assertTrue(journal.update(entries -> List.of(folded)));
		assertTrue(Files.readString(Journal.file(dir)).contains("foldedEntryIds"));
		assertEquals(List.of("entry-0", "entry-00"), journal(dir).entries().getFirst().foldedEntryIds());

		var old = new io.github.chaotix345.rigtune.v030.core.history.Journal(dir, "0.3.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		assertEquals(io.github.chaotix345.rigtune.v030.core.history.Journal.State.OK, old.state());
		assertEquals(List.of("entry-1"), old.entries().stream().map(io.github.chaotix345.rigtune.v030.core.history.JournalEntry::id).toList());
	}

	@Test
	void benchmarkContextRunFields() throws Exception {
		BenchmarkRecord.Context old = new BenchmarkRecord.Context(true, false, null, 2560, 1440, true, 1).withModSet("hash", "entry-1");
		assertNull(old.worldFresh());
		assertNull(old.dhGenerating());
		assertNull(old.stagedAtStart());
		BenchmarkRecord.Context context = old.withWorldFresh(true).withDhGenerating(false).withStagedAtStart(List.of("rec-1", "rec-2"));
		assertEquals("hash", context.modSetHash());
		assertEquals(context, context.withModSet("hash", "entry-1"), "withModSet keeps the 0.5 fields");
		assertTrue(context.sameConditions(old), "the 0.5 fields aren't conditions");

		Path file = dir.resolve("benchmarks.json");
		BenchmarkHistory.empty().with(run("run-0", old)).save(file);
		assertFalse(Files.readString(file).contains("worldFresh"), "nulls aren't written");
		BenchmarkHistory.empty().with(run("run-1", context)).save(file);
		BenchmarkRecord.Context back = BenchmarkHistory.load(file).runs().getFirst().context();
		assertEquals(Boolean.TRUE, back.worldFresh());
		assertEquals(Boolean.FALSE, back.dhGenerating());
		assertEquals(List.of("rec-1", "rec-2"), back.stagedAtStart());

		var v030 = io.github.chaotix345.rigtune.v030.core.benchmark.BenchmarkHistory.load(file);
		assertFalse(v030.unreadable());
		assertEquals(2560, v030.runs().getFirst().context().width());
		var v020 = io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkHistory.load(file);
		assertFalse(v020.unreadable());
		assertEquals(List.of("run-1"), v020.runs().stream().map(io.github.chaotix345.rigtune.v020.core.benchmark.BenchmarkRecord::id).toList());
		assertFalse(Files.exists(dir.resolve("benchmarks.json.bad")));
	}

	private static BenchmarkRecord run(String id, BenchmarkRecord.Context context) {
		return new BenchmarkRecord(id, "2026-09-26T10:00:00Z", "0.5.0", "26.2", "MEASURE", "BENCHMARK_WORLD", BenchmarkRecord.SINGLE, null, 144, true,
				Map.of(), new BenchmarkRecord.Result(300, 200, 6, 3, 0.02), Map.of(), Map.of(), null, false, context);
	}
}
