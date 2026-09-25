package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.core.history.JournalChange;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VanillaChangesTest {
	// Review M7: graphicsPreset rewrites a dozen options, so every option that changed is recorded, not just the
	// requested keys.
	@Test
	void everyChangedOptionIsRecordedIncludingPresetSideEffects() {
		Map<String, String> before = new LinkedHashMap<>();
		before.put("graphicsPreset", "custom");
		before.put("renderDistance", "12");
		before.put("ao", "true");
		before.put("lang", "en_us");
		Map<String, String> after = new LinkedHashMap<>(before);
		after.put("graphicsPreset", "fast");
		after.put("renderDistance", "8");
		after.put("ao", "false");

		List<JournalChange> changes = VanillaChanges.diff(before, after);

		assertEquals(List.of("vanilla.graphicsPreset", "vanilla.renderDistance", "vanilla.ao"), changes.stream().map(JournalChange::key).toList());
		assertEquals("custom", changes.getFirst().before());
		assertEquals("fast", changes.getFirst().after());
		assertTrue(changes.stream().allMatch(c -> JournalChange.APPLIED.equals(c.status()) && c.opId() == null));
	}

	@Test
	void nothingChangedRecordsNothing() {
		Map<String, String> same = Map.of("renderDistance", "12");
		assertTrue(VanillaChanges.diff(same, same).isEmpty());
	}

	@Test
	void anOptionThatAppearsOrDisappearsIsRecorded() {
		List<JournalChange> changes = VanillaChanges.diff(Map.of("a", "1"), Map.of("b", "2"));

		assertEquals(List.of("vanilla.a", "vanilla.b"), changes.stream().map(JournalChange::key).sorted().toList());
	}
}
