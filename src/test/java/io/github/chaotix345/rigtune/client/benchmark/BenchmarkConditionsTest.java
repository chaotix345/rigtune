package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BenchmarkConditionsTest {
	private static JournalChange change(String id, String status) {
		return new JournalChange(id, JournalChange.SETTING, "sodium.performance.chunk_build_defer_mode", "ALWAYS", "ONE_FRAME", null, null, null, null,
				status, "op-" + id, null, null);
	}

	// docs/v0.5/SPEC.md BH-2 (C1): the ids of the history.json changes still staged for the next start when a run starts, in
	// journal order; none is an empty list; more than 64 leaves the field out (null), and the fallback applies.
	@Test
	void bh2StagedIdsAreTheStagedChangesCappedAt64() {
		JournalEntry applied = new JournalEntry("e1", "2026-09-20T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null,
				List.of(change("c1", JournalChange.APPLIED), change("c2", JournalChange.STAGED)));
		JournalEntry staged = new JournalEntry("e2", "2026-09-21T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null,
				List.of(change("c3", JournalChange.STAGED), change("c4", JournalChange.DISCARDED), change(null, JournalChange.STAGED),
						new JournalChange("c5", JournalChange.FILE, null, null, null, JournalChange.ENABLE, "lithium", "lithium-0.18.jar", null,
								JournalChange.STAGED, "op-5", "g-5", null)));
		assertEquals(List.of("c2", "c3", "c5"), BenchmarkConditions.stagedIds(List.of(applied, staged)), "a change without an id can't be listed");
		assertEquals(List.of(), BenchmarkConditions.stagedIds(List.of(new JournalEntry("e0", "2026-09-19T10:00:00Z", JournalEntry.APPLY, "0.5.0",
				"26.2", null, List.of(change("c0", JournalChange.APPLIED))))));
		List<JournalChange> many = new ArrayList<>();
		for (int i = 0; i < 64; i++) {
			many.add(change("s" + i, JournalChange.STAGED));
		}
		JournalEntry sixtyFour = new JournalEntry("e3", "2026-09-22T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null, many);
		assertEquals(64, BenchmarkConditions.stagedIds(List.of(sixtyFour)).size());
		assertNull(BenchmarkConditions.stagedIds(List.of(sixtyFour, staged)), "65 staged: left out");
	}
}
