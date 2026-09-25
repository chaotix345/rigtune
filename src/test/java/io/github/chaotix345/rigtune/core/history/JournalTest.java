package io.github.chaotix345.rigtune.core.history;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.apply.ApplyLock;
import io.github.chaotix345.rigtune.core.apply.HeldLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JournalTest {
	@TempDir
	Path config;
	final List<String> warnings = new ArrayList<>();

	private Journal journal() {
		return new Journal(config, "0.2.0+mc26.2", "26.2", (message, error) -> warnings.add(message), Duration.ofMillis(200));
	}

	private static JournalChange vanilla(String key, String before, String after) {
		return JournalChange.setting(key, before, after, JournalChange.APPLIED, null);
	}

	private static JournalEntry entry(String id, JournalChange... changes) {
		return new JournalEntry(id, "2026-09-25T10:00:00Z", JournalEntry.APPLY, "0.2.0", "26.2", null, List.of(changes));
	}

	@Test
	void recordCreatesAnEntryAndAppendsToItBySameId() throws Exception {
		Journal journal = journal();
		journal.record("e1", JournalEntry.APPLY, List.of(vanilla("vanilla.renderDistance", "12", "16")));
		journal.record("e1", JournalEntry.APPLY, List.of(JournalChange.file(JournalChange.ENABLE, "lithium", "lithium.jar", JournalChange.STAGED, "op1", "g1")));
		journal.record("e2", JournalEntry.BENCHMARK, List.of(vanilla("vanilla.renderDistance", "16", "14")));

		List<JournalEntry> entries = journal().entries();
		assertEquals(List.of("e1", "e2"), entries.stream().map(JournalEntry::id).toList());
		JournalEntry first = entries.getFirst();
		assertEquals(JournalEntry.APPLY, first.kind());
		assertEquals("0.2.0+mc26.2", first.rigtuneVersion());
		assertEquals("26.2", first.mcVersion());
		assertNotNull(first.at());
		assertEquals(2, first.changes().size());
		assertEquals("op1", first.changes().get(1).opId());
		assertEquals(JournalEntry.BENCHMARK, entries.get(1).kind());
		JsonObject root = JsonParser.parseString(Files.readString(Journal.file(config))).getAsJsonObject();
		assertEquals(1, root.get("formatVersion").getAsInt());
		assertTrue(warnings.isEmpty(), warnings.toString());
	}

	@Test
	void anEmptyRecordWritesNothing() {
		journal().record("e1", JournalEntry.APPLY, List.of());
		assertFalse(Files.exists(Journal.file(config)));
	}

	@Test
	void capKeepsTheNewestFiftyAndDropsFinishedEntriesFirst() throws Exception {
		List<JournalEntry> many = new ArrayList<>();
		many.add(entry("staged-old", JournalChange.setting("sodium.a", "0", "1", JournalChange.STAGED, "op")));
		many.add(entry("applied-old", vanilla("vanilla.a", "1", "2")));
		for (int i = 0; i < 58; i++) {
			many.add(entry("done-" + i, vanilla("vanilla.b", "1", "2").withStatus(JournalChange.REVERTED)));
		}
		many.add(entry("newest", vanilla("vanilla.c", "1", "2")));

		assertTrue(journal().update(entries -> many));

		List<String> ids = journal().entries().stream().map(JournalEntry::id).toList();
		assertEquals(Journal.MAX_ENTRIES, ids.size());
		assertEquals("staged-old", ids.get(0));
		assertEquals("applied-old", ids.get(1));
		assertEquals("done-11", ids.get(2));
		assertEquals("newest", ids.getLast());
	}

	@Test
	void capDropsOldUnfinishedEntriesOnlyWhenItMust() {
		List<JournalEntry> many = new ArrayList<>();
		for (int i = 0; i < 55; i++) {
			many.add(entry("applied-" + i, vanilla("vanilla.a", "1", "2")));
		}
		many.addFirst(entry("staged", JournalChange.setting("sodium.a", "0", "1", JournalChange.STAGED, "op")));

		List<String> ids = Journal.cap(many).stream().map(JournalEntry::id).toList();

		assertEquals(Journal.MAX_ENTRIES, ids.size());
		assertEquals(List.of("staged", "applied-6"), ids.subList(0, 2));
	}

	@Test
	void aCorruptFileIsKeptAsBadAndTheJournalStartsFresh() throws Exception {
		Files.createDirectories(Journal.file(config).getParent());
		Files.writeString(Journal.file(config), "{not json");

		assertEquals(List.of(), journal().entries());
		journal().record("e1", JournalEntry.APPLY, List.of(vanilla("vanilla.a", "1", "2")));

		assertEquals("{not json", Files.readString(Journal.file(config).resolveSibling("history.json.bad")));
		assertEquals(List.of("e1"), journal().entries().stream().map(JournalEntry::id).toList());
	}

	@Test
	void aNewerFormatIsReadOnlyAndNeverOverwritten() throws Exception {
		Files.createDirectories(Journal.file(config).getParent());
		String newer = "{\"formatVersion\": 2, \"entries\": [], \"somethingNew\": true}";
		Files.writeString(Journal.file(config), newer);

		Journal journal = journal();
		assertTrue(journal.readOnly());
		journal.record("e1", JournalEntry.APPLY, List.of(vanilla("vanilla.a", "1", "2")));
		assertFalse(journal.update(entries -> entries));

		assertEquals(newer, Files.readString(Journal.file(config)));
		assertEquals(List.of(), journal.entries());
		assertEquals(1, warnings.size(), warnings.toString());
	}

	// Review L8: one bad element must not send the whole history to .bad.
	@Test
	void nullEntriesAndChangesAreDropped() throws Exception {
		Files.createDirectories(Journal.file(config).getParent());
		Files.writeString(Journal.file(config), """
				{"formatVersion": 1, "entries": [null, {"id": "e1", "kind": "apply", "changes": [null,
				  {"id": "c1", "type": "setting", "key": "vanilla.a", "before": "1", "after": "2", "status": "APPLIED"}]}]}
				""");

		List<JournalEntry> entries = journal().entries();

		assertEquals(1, entries.size());
		assertEquals(List.of("c1"), entries.getFirst().changes().stream().map(JournalChange::id).toList());
		assertFalse(Files.exists(Journal.file(config).resolveSibling("history.json.bad")));
	}

	@Test
	void updateGivesUpWhileAnotherHolderHasTheLock() throws Exception {
		try (HeldLock helper = HeldLock.hold(ApplyLock.defaultPath(config))) {
			assertFalse(journal().update(entries -> List.of(entry("e1"))));
			journal().record("e2", JournalEntry.APPLY, List.of(vanilla("vanilla.a", "1", "2")));
		}
		assertFalse(Files.exists(Journal.file(config)));
		assertEquals(1, warnings.size(), warnings.toString());
	}

	@Test
	void updateWorksInsideALockTheCallerHolds() throws Exception {
		try (ApplyLock staging = ApplyLock.acquire(ApplyLock.defaultPath(config), Duration.ZERO)) {
			assertNotNull(staging);
			assertTrue(journal().update(entries -> List.of(entry("e1"))));
		}
		assertEquals(1, journal().entries().size());
	}

	@Test
	void updateExistingNeverCreatesTheFile() throws Exception {
		assertFalse(journal().updateExisting(entries -> List.of(entry("e1"))));
		assertFalse(journal().exists());
		assertTrue(journal().update(entries -> entries));
		assertTrue(journal().exists());
		assertTrue(journal().updateExisting(entries -> List.of(entry("e1"))));
		assertEquals(1, journal().entries().size());
	}
}
