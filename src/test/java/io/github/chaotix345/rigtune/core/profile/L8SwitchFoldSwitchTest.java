package io.github.chaotix345.rigtune.core.profile;

import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md L8 (AC2H.3), the real prune path: a profile switch, enough Applies to fold it past MAX_ENTRIES, then
// another switch, whose label is recorded the way ProfileService records it (ProfileStore.recordSwitch pruning against
// Journal.idsWithFolded). The first switch's label survives and History's baseline row includes it.
class L8SwitchFoldSwitchTest {
	private static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

	@TempDir
	Path dir;

	private Journal journal() {
		return new Journal(dir, "0.5.0+mc26.2", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
	}

	private void write(Journal journal, String id, int minute) throws IOException {
		JournalEntry entry = new JournalEntry(id, START.plusSeconds(60L * minute).toString(), JournalEntry.APPLY, "0.5.0+mc26.2", "26.2", null,
				List.of(JournalChange.setting("vanilla.maxFps", String.valueOf(minute), String.valueOf(minute + 1), JournalChange.APPLIED, null)));
		assertTrue(journal.update(entries -> {
			List<JournalEntry> out = new ArrayList<>(entries);
			out.add(entry);
			return out;
		}));
	}

	@Test
	void theFirstLabelSurvivesTheRealPrunePath() throws IOException {
		Journal journal = journal();
		ProfileStore store = ProfileStore.shared(dir);
		write(journal, "switch-battery", 0);
		assertTrue(store.recordSwitch(new ProfileStore.Switch("switch-battery", null, "battery", "Battery"), Journal.idsWithFolded(journal.entries())));
		for (int i = 1; i <= Journal.MAX_ENTRIES; i++) {
			write(journal, "apply-" + i, i);
		}
		assertFalse(journal.entries().stream().anyMatch(e -> e.id().equals("switch-battery")), "the switch was folded");

		write(journal, "switch-max-fps", 60);
		assertTrue(store.recordSwitch(new ProfileStore.Switch("switch-max-fps", null, "max_fps", "Max FPS"), Journal.idsWithFolded(journal.entries())));

		assertEquals("Battery", store.labels().get("switch-battery"), "the folded switch keeps its label");
		HistoryModel.View view = HistoryModel.withProfiles(HistoryModel.build(journal.state(), journal.entries(), java.util.Map.of(),
				HistoryModel.Labels.RAW), store.labels());
		HistoryModel.Entry baseline = view.entries().getLast();
		assertTrue(baseline.id().startsWith(Journal.BASELINE), baseline.id());
		assertEquals(List.of("Battery"), baseline.includes());
		assertEquals("Max FPS", view.entries().getFirst().profile());
		// Pruning against the plain entry ids (0.4's journalIds()) would have dropped it.
		Set<String> plain = Set.copyOf(journal.entries().stream().map(JournalEntry::id).toList());
		assertFalse(plain.contains("switch-battery"));
	}
}
