package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md C1 (PLAN contracts item 10): awareness.json's optional acknowledgedStartupRegressions (C18) and the
// options snapshot at exit (4h), through AwarenessStore.update like the 0.4 fields.
class AwarenessStoreV05Test {
	@TempDir
	Path dir;

	@Test
	void startupRegressionAcknowledgementsAreOptionalBoundedAndPersist() throws Exception {
		AwarenessStore store = AwarenessStore.shared(dir);
		assertTrue(store.dismiss("k"));
		assertFalse(Files.readString(AwarenessStore.file(dir)).contains(AwarenessStore.ACKNOWLEDGED_STARTUP_REGRESSIONS), "absent until the first one");
		assertEquals(Set.of(), store.acknowledgedStartupRegressions());
		assertTrue(store.acknowledgeStartupRegression("startup.regression.1"));
		assertTrue(store.acknowledgeStartupRegression("startup.regression.1"));
		assertEquals(Set.of("startup.regression.1"), store.acknowledgedStartupRegressions());
		for (int i = 0; i < AwarenessStore.MAX_ACKNOWLEDGED + 3; i++) {
			store.acknowledgeStartupRegression("r" + i);
		}
		Set<String> kept = store.acknowledgedStartupRegressions();
		assertEquals(AwarenessStore.MAX_ACKNOWLEDGED, kept.size());
		assertFalse(kept.contains("startup.regression.1"));
		assertEquals(Set.of(), store.acknowledgedRegressions(), "the benchmark acknowledgements are a separate list");
	}

	@Test
	void theOptionsSnapshotIsCappedTypeCheckedAndConsumedOnce() throws Exception {
		AwarenessStore store = AwarenessStore.shared(dir);
		assertEquals(Map.of(), store.optionsAtExit());
		assertNull(store.takeOptionsAtExit(), "no snapshot: nothing to compare");
		assertFalse(Files.exists(AwarenessStore.file(dir)), "and nothing written");

		Map<String, String> many = new LinkedHashMap<>();
		for (int i = 0; i < AwarenessStore.MAX_OPTIONS_AT_EXIT + 10; i++) {
			many.put("vanilla.k" + i, "v" + i);
		}
		many.put("vanilla.null", null);
		assertTrue(store.setOptionsAtExit(many));
		assertEquals(AwarenessStore.MAX_OPTIONS_AT_EXIT, store.optionsAtExit().size());
		assertEquals("v0", store.optionsAtExit().get("vanilla.k0"));

		Map<String, String> taken = store.takeOptionsAtExit();
		assertEquals(AwarenessStore.MAX_OPTIONS_AT_EXIT, taken.size());
		assertNull(store.takeOptionsAtExit(), "consumed");
		assertFalse(JsonParser.parseString(Files.readString(AwarenessStore.file(dir))).getAsJsonObject().has(AwarenessStore.OPTIONS_AT_EXIT));
	}

	@Test
	void aHandEditedSnapshotKeepsOnlyStringsAndUnknownFieldsSurvive() throws Exception {
		Path file = AwarenessStore.file(dir);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"formatVersion\":1,\"future\":{\"x\":1},\"optionsAtExit\":{\"vanilla.a\":\"1\",\"vanilla.b\":2,\"vanilla.c\":{}}}");
		AwarenessStore store = AwarenessStore.shared(dir);
		assertEquals(Map.of("vanilla.a", "1"), store.optionsAtExit());
		assertEquals(Map.of("vanilla.a", "1"), store.takeOptionsAtExit());
		assertTrue(Files.readString(file).contains("\"future\""));
	}

	@Test
	void aNewerFileIsNeverWrittenAndItsSnapshotIsNotTaken() throws Exception {
		Path file = AwarenessStore.file(dir);
		Files.createDirectories(file.getParent());
		String newer = "{\"formatVersion\":9,\"optionsAtExit\":{\"vanilla.a\":\"1\"}}";
		Files.writeString(file, newer);
		AwarenessStore store = AwarenessStore.shared(dir);
		assertFalse(store.acknowledgeStartupRegression("x"));
		assertFalse(store.setOptionsAtExit(Map.of("vanilla.a", "2")));
		assertNull(store.takeOptionsAtExit(), "it couldn't be consumed, so it isn't compared");
		assertEquals(newer, Files.readString(file));
	}
}
