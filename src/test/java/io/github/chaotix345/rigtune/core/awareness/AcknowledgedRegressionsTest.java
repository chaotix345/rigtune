package io.github.chaotix345.rigtune.core.awareness;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.4/SPEC.md 7 (plan review X-M1): the regression notice's acknowledgements live in awareness.json
// acknowledgedRegressions, written through AwarenessStore.update.
class AcknowledgedRegressionsTest {
	@TempDir
	Path dir;

	@Test
	void anAcknowledgementPersistsOnceAndStaysBounded() throws Exception {
		AwarenessStore store = AwarenessStore.shared(dir);
		assertEquals(Set.of(), store.acknowledgedRegressions());
		assertTrue(store.acknowledgeRegression("run-1"));
		assertTrue(store.acknowledgeRegression("run-1"));
		assertEquals(Set.of("run-1"), store.acknowledgedRegressions());
		assertEquals(List.of("run-1"), List.copyOf(JsonParser.parseString(Files.readString(AwarenessStore.file(dir), StandardCharsets.UTF_8))
				.getAsJsonObject().getAsJsonArray(AwarenessStore.ACKNOWLEDGED_REGRESSIONS).asList().stream().map(e -> e.getAsString()).toList()));
		for (int i = 0; i < AwarenessStore.MAX_ACKNOWLEDGED + 5; i++) {
			store.acknowledgeRegression("r" + i);
		}
		Set<String> kept = store.acknowledgedRegressions();
		assertEquals(AwarenessStore.MAX_ACKNOWLEDGED, kept.size());
		assertFalse(kept.contains("run-1"));
		assertTrue(kept.contains("r" + (AwarenessStore.MAX_ACKNOWLEDGED + 4)));
	}

	@Test
	void unknownFieldsSurviveAndBadEntriesAreIgnored() throws Exception {
		Path file = AwarenessStore.file(dir);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "{\"formatVersion\":1,\"future\":{\"x\":1},\"acknowledgedRegressions\":[\"a\",{\"odd\":true},\"b\"]}");
		AwarenessStore store = AwarenessStore.shared(dir);
		assertEquals(Set.of("a", "b"), store.acknowledgedRegressions());
		assertTrue(store.acknowledgeRegression("c"));
		assertTrue(Files.readString(file).contains("\"future\""));
		assertEquals(Set.of("a", "b", "c"), store.acknowledgedRegressions());
	}

	@Test
	void aNewerFileIsNeverWritten() throws Exception {
		Path file = AwarenessStore.file(dir);
		Files.createDirectories(file.getParent());
		String newer = "{\"formatVersion\":9,\"acknowledgedRegressions\":[\"a\"]}";
		Files.writeString(file, newer);
		AwarenessStore store = AwarenessStore.shared(dir);
		assertFalse(store.acknowledgeRegression("b"));
		assertEquals(newer, Files.readString(file));
		assertEquals(Set.of("a"), store.acknowledgedRegressions());
	}
}
