package io.github.chaotix345.rigtune.core.stutter;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.history.Journal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.9 (FixStoreTest): stutter-fixes.json on StateStore (X7): at most 10 records and 32 KiB (the oldest finished record
// goes first, never the active one), unknown fields survive at every depth, junk reads as absent, and a newer file is
// readable but never written.
class FixStoreTest {
	@TempDir
	Path dir;

	private FixStore store() {
		return FixStore.shared(dir);
	}

	private Path file() {
		return FixStore.file(dir);
	}

	private JsonObject onDisk() throws IOException {
		return JsonParser.parseString(Files.readString(file())).getAsJsonObject();
	}

	private void write(String json) throws IOException {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), json, StandardCharsets.UTF_8);
	}

	private static FixTracker.Record finished(String entryId, FixTracker.State state) {
		FixTracker.Record m = FixTrackerTest.measuring();
		return new FixTracker.Record(entryId, m.adviceId(), m.key(), m.from(), m.to(), m.appliedAt(), m.rulesRevision(), m.now(), state, m.before(),
				m.conditions(), m.after(), m.skipped(), m.lastSkip(), m.verdict(), m.dismissed());
	}

	private static FixTracker.Record compared() {
		FixTracker.Record m = FixTrackerTest.measuring();
		SessionOutcome after = FixComparisonTest.side(4, 420, 200, 1, 1, 0, 1, 1, 0, 0);
		return new FixTracker.Record("entry-c", m.adviceId(), m.key(), m.from(), m.to(), m.appliedAt(), m.rulesRevision(), m.now(), FixTracker.State.COMPARED,
				m.before(), m.conditions(), after, 2, new FixTracker.Skip("setting", List.of("vanilla.simulationDistance", "8", "12")),
				FixComparison.compare(m.before(), after), false);
	}

	private static List<String> ids(List<FixTracker.Record> records) {
		return records.stream().map(FixTracker.Record::entryId).toList();
	}

	@Test
	void missingIsEmpty() {
		assertEquals(List.of(), store().records());
		assertNull(store().active());
		assertTrue(store().writable());
	}

	@Test
	void recordsInEveryStateRoundTrip() throws IOException {
		FixTracker.Record staged = FixTrackerTest.staged();
		FixTracker.Record measuring = finished("entry-m", FixTracker.State.MEASURING);
		FixTracker.Record compared = compared();
		FixTracker.Record dismissed = finished("entry-d", FixTracker.State.EXPIRED).dismiss();
		for (FixTracker.Record r : List.of(compared, dismissed, measuring, staged)) {
			assertTrue(store().add(r));
		}
		List<FixTracker.Record> back = FixStore.shared(dir).records();
		assertEquals(List.of(compared, dismissed, measuring, staged), back);
		assertEquals(compared.verdict().kind().id(), onDisk().getAsJsonArray("fixes").get(0).getAsJsonObject().get("verdict").getAsString());
		assertEquals(1, onDisk().get("formatVersion").getAsInt());
	}

	// C20 review L12: the mark that a fix expired with its journal entry gone survives the file.
	@Test
	void anEntryGoneExpiryRoundTrips() {
		FixTracker.Record gone = FixTracker.advance(FixTrackerTest.measuring(), Journal.State.OK, List.of(), null, Instant.parse("2026-09-03T09:00:00Z"));
		assertTrue(store().add(gone));
		FixTracker.Record back = FixStore.shared(dir).records().getFirst();
		assertEquals(gone, back);
		assertFalse(back.undoable());
	}

	@Test
	void theActiveOneIsStagedOrMeasuringAndNotDismissed() {
		store().add(finished("a", FixTracker.State.COMPARED));
		assertNull(store().active());
		store().add(finished("b", FixTracker.State.MEASURING));
		assertEquals("b", store().active().entryId());
		assertTrue(store().dismiss("b"));
		assertNull(store().active());
		assertTrue(store().records().get(1).dismissed());
	}

	@Test
	void addingTheSameEntryAgainReplacesIt() {
		store().add(finished("a", FixTracker.State.MEASURING));
		store().add(finished("a", FixTracker.State.COMPARED));
		assertEquals(List.of(FixTracker.State.COMPARED), store().records().stream().map(FixTracker.Record::state).toList());
	}

	@Test
	void updateChangesOneRecordAndSaysWhetherItWasThere() {
		store().add(finished("a", FixTracker.State.MEASURING));
		store().add(finished("b", FixTracker.State.EXPIRED));
		assertTrue(store().update("a", r -> r.withState(FixTracker.State.REPLACED)));
		assertEquals(List.of(FixTracker.State.REPLACED, FixTracker.State.EXPIRED), store().records().stream().map(FixTracker.Record::state).toList());
		assertFalse(store().update("nope", r -> r.withState(FixTracker.State.UNDONE)));
		assertFalse(store().dismiss("nope"));
	}

	// At most 10: the oldest finished record goes first; the active one stays wherever it is.
	@Test
	void atMostTenRecordsNeverDroppingTheActiveOne() {
		store().add(finished("active", FixTracker.State.MEASURING));
		for (int i = 0; i < 11; i++) {
			assertTrue(store().add(finished("done-" + i, FixTracker.State.COMPARED)));
		}
		List<String> ids = ids(store().records());
		assertEquals(FixStore.MAX_RECORDS, ids.size());
		assertEquals("active", ids.getFirst());
		assertEquals("done-2", ids.get(1));
		assertEquals("done-10", ids.getLast());
	}

	// About 32 KiB: records with many settings don't fit three at a time; the oldest finished goes.
	@Test
	void theByteCapDropsTheOldestFinishedRecord() throws IOException {
		assertTrue(store().add(big("active", FixTracker.State.STAGED)));
		assertTrue(store().add(big("old", FixTracker.State.COMPARED)));
		assertTrue(store().add(big("new", FixTracker.State.EXPIRED)));
		assertEquals(List.of("active", "new"), ids(store().records()));
		assertTrue(Files.size(file()) <= FixStore.MAX_BYTES);
		// The active record alone over the cap can't be written; nothing changes.
		byte[] before = Files.readAllBytes(file());
		assertFalse(store().add(huge("too-big")));
		assertArrayEquals(before, Files.readAllBytes(file()));
	}

	private static FixTracker.Record withSettings(String entryId, FixTracker.State state, int keys, int valueLength) {
		FixTracker.Record m = FixTrackerTest.measuring();
		Map<String, String> settings = new LinkedHashMap<>();
		for (int i = 0; i < keys; i++) {
			settings.put("sodium.extra.key" + i, "v".repeat(valueLength));
		}
		FixConditions c = m.conditions();
		FixConditions many = new FixConditions(c.mc(), c.modSetHash(), c.heapMaxMb(), c.collector(), c.width(), c.height(), c.fullscreen(), c.world(),
				c.phaseTiming(), c.gcMeasured(), settings);
		return new FixTracker.Record(entryId, m.adviceId(), m.key(), m.from(), m.to(), m.appliedAt(), m.rulesRevision(), m.now(), state, m.before(), many,
				m.after(), m.skipped(), m.lastSkip(), m.verdict(), m.dismissed());
	}

	private static FixTracker.Record big(String entryId, FixTracker.State state) {
		return withSettings(entryId, state, 100, 80);
	}

	private static FixTracker.Record huge(String entryId) {
		return withSettings(entryId, FixTracker.State.MEASURING, 400, 100);
	}

	@Test
	void unknownFieldsSurviveAtEveryDepth() throws IOException {
		assertTrue(store().add(FixTrackerTest.measuring()));
		JsonObject root = onDisk();
		root.addProperty("fromTheFuture", 1);
		JsonObject rec = root.getAsJsonArray("fixes").get(0).getAsJsonObject();
		rec.addProperty("mine", true);
		rec.getAsJsonObject("before").add("extra", JsonParser.parseString("[1, 2]"));
		rec.getAsJsonObject("conditions").addProperty("gpu", "x");
		rec.getAsJsonObject("conditions").getAsJsonObject("settings").add("weird", JsonParser.parseString("{\"a\": 1}"));
		root.getAsJsonArray("fixes").add(JsonParser.parseString("{\"entryId\": \"someone-elses\", \"shape\": \"unknown\"}"));
		write(root.toString());
		assertTrue(FixStore.shared(dir).update("entry-1", r -> r.withState(FixTracker.State.COMPARED)));
		JsonObject after = onDisk();
		assertEquals(1, after.get("fromTheFuture").getAsInt());
		JsonObject back = after.getAsJsonArray("fixes").get(0).getAsJsonObject();
		assertEquals("compared", back.get("state").getAsString());
		assertTrue(back.get("mine").getAsBoolean());
		assertEquals(JsonParser.parseString("[1, 2]"), back.getAsJsonObject("before").get("extra"));
		assertEquals("x", back.getAsJsonObject("conditions").get("gpu").getAsString());
		assertEquals(JsonParser.parseString("{\"a\": 1}"), back.getAsJsonObject("conditions").getAsJsonObject("settings").get("weird"));
		assertEquals("unknown", after.getAsJsonArray("fixes").get(1).getAsJsonObject().get("shape").getAsString());
	}

	// Players edit these files: a value of the wrong type reads as absent; a record without what it needs is skipped (and
	// left in the file).
	@Test
	void junkReadsAsAbsent() throws IOException {
		assertTrue(store().add(FixTrackerTest.measuring()));
		JsonObject root = onDisk();
		JsonArray fixes = root.getAsJsonArray("fixes");
		JsonObject good = fixes.get(0).getAsJsonObject();
		good.getAsJsonObject("before").addProperty("lostMs", "lots");
		good.addProperty("skipped", "two");
		good.addProperty("dismissed", "no");
		fixes.add(7);
		fixes.add(JsonParser.parseString("null"));
		List<String> broken = new ArrayList<>();
		for (String field : List.of("entryId", "key", "from", "to", "appliedAt", "state", "before", "conditions")) {
			JsonObject copy = good.deepCopy();
			copy.addProperty("entryId", "no-" + field);
			if (field.equals("entryId")) {
				copy.remove("entryId");
			} else {
				copy.add(field, JsonParser.parseString("[\"not this\"]"));
			}
			fixes.add(copy);
			broken.add(field);
		}
		JsonObject otherKey = good.deepCopy();
		otherKey.addProperty("entryId", "other-key");
		otherKey.addProperty("key", "vanilla.maxFps");
		fixes.add(otherKey);
		JsonObject badState = good.deepCopy();
		badState.addProperty("entryId", "bad-state");
		badState.addProperty("state", "paused");
		fixes.add(badState);
		write(root.toString());
		List<FixTracker.Record> records = FixStore.shared(dir).records();
		assertEquals(List.of("entry-1"), ids(records), "only the good record; broken: " + broken);
		assertEquals(0, records.getFirst().before().lostMs());
		assertEquals(0, records.getFirst().skipped());
		assertFalse(records.getFirst().dismissed());
		assertEquals(FixTrackerTest.measuring().before().hitches(), records.getFirst().before().hitches());
	}

	// A record from the future (a clock set wrong, a hand edit) is skipped: more than a day ahead, or Instant.MAX's year.
	@Test
	void anAppliedAtInTheFutureIsSkipped() throws IOException {
		assertTrue(store().add(FixTrackerTest.measuring()));
		JsonObject root = onDisk();
		JsonArray fixes = root.getAsJsonArray("fixes");
		JsonObject good = fixes.get(0).getAsJsonObject();
		Instant now = Instant.now();
		for (String when : List.of("+1000000000-12-31T00:00:00Z", now.plusSeconds(2 * 86_400).toString(), now.plusSeconds(12 * 3600).toString())) {
			JsonObject copy = good.deepCopy();
			copy.addProperty("entryId", when);
			copy.addProperty("appliedAt", when);
			fixes.add(copy);
		}
		write(root.toString());
		assertEquals(List.of("entry-1", now.plusSeconds(12 * 3600).toString()), ids(FixStore.shared(dir).records()));
	}

	// from and to must be values of the key (ShareKeys): a hand-edited "1e99999999" or "NEVER" drops the record.
	@Test
	void fromAndToMustBeValuesOfTheKey() throws IOException {
		assertTrue(store().add(finished("rd", FixTracker.State.MEASURING)));
		assertTrue(store().add(FixTrackerTest.staged()));
		JsonObject root = onDisk();
		JsonArray fixes = root.getAsJsonArray("fixes");
		fixes.get(0).getAsJsonObject().addProperty("from", "1e99999999");
		JsonObject sodium = fixes.get(1).getAsJsonObject();
		JsonObject bad = sodium.deepCopy();
		bad.addProperty("entryId", "never");
		bad.addProperty("to", "NEVER");
		fixes.add(bad);
		write(root.toString());
		assertEquals(List.of("entry-1"), ids(FixStore.shared(dir).records()));
		assertEquals("sodium.performance.chunk_build_defer_mode", FixStore.shared(dir).records().getFirst().key());
	}

	// The header's promise: a record this version can't read is left in the file, even when pruning.
	@Test
	void unreadableRecordsAreNeverDropped() throws IOException {
		write("{\"formatVersion\": 1, \"fixes\": [7, {\"entryId\": \"from-the-future\", \"shape\": \"unknown\"}]}");
		for (int i = 0; i < 11; i++) {
			assertTrue(store().add(finished("done-" + i, FixTracker.State.COMPARED)));
		}
		JsonArray fixes = onDisk().getAsJsonArray("fixes");
		assertEquals(7, fixes.get(0).getAsInt());
		assertEquals("unknown", fixes.get(1).getAsJsonObject().get("shape").getAsString());
		List<String> ids = ids(store().records());
		assertEquals(FixStore.MAX_RECORDS, ids.size());
		assertEquals("done-1", ids.getFirst());
	}

	@Test
	void fixesThatIsntAListReadsEmpty() throws IOException {
		write("{\"formatVersion\": 1, \"fixes\": {\"a\": 1}}");
		assertEquals(List.of(), store().records());
		assertTrue(store().add(FixTrackerTest.staged()));
		assertEquals(List.of("entry-1"), ids(store().records()));
	}

	// A newer RigTune's file: its records can still be read, nothing is written, and offers stop (writable() false).
	@Test
	void aNewerFileIsReadOnly() throws IOException {
		store().add(FixTrackerTest.measuring());
		JsonObject root = onDisk();
		root.addProperty("formatVersion", 2);
		write(root.toString());
		byte[] before = Files.readAllBytes(file());
		FixStore store = FixStore.shared(dir);
		assertFalse(store.writable());
		assertEquals(List.of("entry-1"), ids(store.records()));
		assertFalse(store.add(FixTrackerTest.staged()));
		assertFalse(store.update("entry-1", r -> r.withState(FixTracker.State.UNDONE)));
		assertFalse(store.dismiss("entry-1"));
		assertArrayEquals(before, Files.readAllBytes(file()));
	}

	@Test
	void aCorruptFileIsMovedAsideAndTheStoreStartsEmpty() throws IOException {
		write("{ not json");
		assertEquals(List.of(), store().records());
		assertTrue(Files.exists(file().resolveSibling(FixStore.FILE_NAME + ".bad")));
		assertTrue(store().add(FixTrackerTest.staged()));
		assertEquals(Instant.parse("2026-09-02T09:14:00Z"), store().records().getFirst().appliedAt());
	}
}
