package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.history.Journal;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import io.github.chaotix345.rigtune.core.model.GpuInfo;
import io.github.chaotix345.rigtune.core.model.GraphicsBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
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

	// Review-11 COMPAT-2: the session's backend and GPU for a new run's context, from the probe's GpuInfo.
	@Test
	void compat2TheBackendAndGpuComeFromTheProbe() {
		assertEquals(new BenchmarkConditions.Graphics("VULKAN", "NVIDIA GeForce RTX 3050 Laptop GPU"), BenchmarkConditions.Graphics.of(
				new GpuInfo("NVIDIA", " NVIDIA GeForce RTX 3050 Laptop GPU ", "580.1", GraphicsBackend.VULKAN, 4096)));
		assertEquals(new BenchmarkConditions.Graphics(null, null), BenchmarkConditions.Graphics.of(
				new GpuInfo("unknown", "unknown", "unknown", GraphicsBackend.UNKNOWN, 0)), "the probe's placeholders are unknowns");
		assertEquals(new BenchmarkConditions.Graphics(null, null), BenchmarkConditions.Graphics.of(null), "not probed yet");
		// review-12 R12FEAT-1: recorded without Mesa's build versions.
		assertEquals(new BenchmarkConditions.Graphics("OPENGL", "llvmpipe"), BenchmarkConditions.Graphics.of(
				new GpuInfo("Mesa", "llvmpipe (LLVM 20.1.2, 256 bits)", "4.5 (Core Profile) Mesa 25.2.8", GraphicsBackend.OPENGL, 0)));
	}

	// Review (part 1 M3): one journal snapshot gives the run both its cursor and its staged ids.
	@Test
	void bh2OneJournalSnapshotGivesTheCursorAndTheStagedIds() {
		JournalEntry first = new JournalEntry("e1", "2026-09-20T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null,
				List.of(change("c1", JournalChange.APPLIED)));
		JournalEntry staged = new JournalEntry("e2", "2026-09-21T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null,
				List.of(change("c3", JournalChange.STAGED)));
		assertEquals(new BenchmarkConditions.JournalAtStart("e2", List.of("c3")),
				BenchmarkConditions.JournalAtStart.of(Journal.State.OK, List.of(first, staged)));
		assertEquals(new BenchmarkConditions.JournalAtStart(null, List.of()), BenchmarkConditions.JournalAtStart.of(Journal.State.MISSING, List.of()));
		assertEquals(new BenchmarkConditions.JournalAtStart(null, null), BenchmarkConditions.JournalAtStart.of(Journal.State.CORRUPT, List.of()));
	}

	// review 11 BENCH-8: one read of history.json gives both (a second read that failed after an OK first one gave [],
	// "nothing staged", for a history that has staged changes).
	@Test
	void bh2TheJournalIsReadOnce(@TempDir Path config) throws IOException {
		Journal journal = new Journal(config, "0.5.0", "26.2", (message, error) -> {
			throw new AssertionError(message, error);
		});
		journal.record("e1", JournalEntry.APPLY, List.of(change("c1", JournalChange.STAGED)));
		int before = journal.reads();

		BenchmarkConditions.JournalAtStart atStart = BenchmarkConditions.JournalAtStart.of(journal);

		assertEquals(before + 1, journal.reads());
		assertEquals("e1", atStart.cursor());
		assertEquals(1, atStart.staged().size());
	}

	// Review (part 1 M3): an unreadable history.json isn't "nothing staged" (the journal answers no entries then): left out.
	@Test
	void bh2AnUnreadableHistoryLeavesTheFieldOut() {
		JournalEntry staged = new JournalEntry("e2", "2026-09-21T10:00:00Z", JournalEntry.APPLY, "0.5.0", "26.2", null,
				List.of(change("c3", JournalChange.STAGED)));
		assertEquals(List.of("c3"), BenchmarkConditions.stagedIds(Journal.State.OK, List.of(staged)));
		assertEquals(List.of(), BenchmarkConditions.stagedIds(Journal.State.MISSING, List.of()), "no history.json: nothing staged");
		for (Journal.State state : List.of(Journal.State.CORRUPT, Journal.State.NEWER, Journal.State.UNREADABLE)) {
			assertNull(BenchmarkConditions.stagedIds(state, List.of()), state.name());
		}
	}
}
