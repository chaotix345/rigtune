package io.github.chaotix345.rigtune.core.history;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

// review 11 PERF-2 (the WS-H part): the worker's readers share one parse of history.json while the file is unchanged.
class JournalCacheTest {
	@TempDir
	Path config;

	private Journal journal() {
		return new Journal(config, "0.5.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	private static List<JournalChange> change(String id) {
		return List.of(JournalChange.setting("sodium.a", "1", "2", JournalChange.APPLIED, id));
	}

	@Test
	void anUnchangedFileIsParsedOnce() throws IOException {
		Journal journal = journal();
		journal.record("e1", JournalEntry.APPLY, change("c1"));
		int before = journal.reads();

		Journal.Snapshot first = JournalCache.snapshot(journal);
		Journal.Snapshot second = JournalCache.snapshot(journal);

		assertEquals(Journal.State.OK, first.state());
		assertEquals(List.of("e1"), first.entries().stream().map(JournalEntry::id).toList());
		assertSame(first, second);
		assertEquals(before + 1, journal.reads());
	}

	// A write through the same Journal is seen whatever the file's timestamps say.
	@Test
	void aWriteThroughTheJournalIsSeen() throws IOException {
		Journal journal = journal();
		journal.record("e1", JournalEntry.APPLY, change("c1"));
		JournalCache.snapshot(journal);

		journal.record("e2", JournalEntry.APPLY, change("c2"));

		assertEquals(List.of("e1", "e2"), JournalCache.snapshot(journal).entries().stream().map(JournalEntry::id).toList());
	}

	// Another process's write (the helper, another Journal): a new size, or the same size with a new time.
	@Test
	void aWriteFromElsewhereIsSeen() throws IOException {
		Journal journal = journal();
		journal.record("e1", JournalEntry.APPLY, change("c1"));
		JournalCache.snapshot(journal);

		journal().record("e2", JournalEntry.APPLY, change("c2"));
		assertEquals(List.of("e1", "e2"), JournalCache.snapshot(journal).entries().stream().map(JournalEntry::id).toList());

		Path file = Journal.file(config);
		FileTime time = Files.getLastModifiedTime(file);
		String text = Files.readString(file, StandardCharsets.UTF_8);
		Files.writeString(file, text.replace("\"e2\"", "\"e3\""), StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(time.toMillis() + 2000));
		assertEquals(List.of("e1", "e3"), JournalCache.snapshot(journal).entries().stream().map(JournalEntry::id).toList());
	}

	@Test
	void aMissingFileIsMissingUntilItIsThere() throws IOException {
		Journal journal = journal();
		assertEquals(Journal.State.MISSING, JournalCache.snapshot(journal).state());

		journal.record("e1", JournalEntry.APPLY, change("c1"));

		assertEquals(Journal.State.OK, JournalCache.snapshot(journal).state());
		Files.delete(Journal.file(config));
		assertEquals(Journal.State.MISSING, JournalCache.snapshot(journal).state());
	}

	@Test
	void eachJournalHasItsOwn() throws IOException {
		Journal journal = journal();
		journal.record("e1", JournalEntry.APPLY, change("c1"));
		Journal other = journal();

		JournalCache.snapshot(journal);
		JournalCache.snapshot(other);

		assertEquals(1, other.reads());
	}
}
