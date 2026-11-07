package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PendingNet;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// #46: the launch toast's count in net changes per settings key (NetChanges), and whether a queue changes anything
// (PendingNet). The field case: Max FPS -> Quality -> My settings staged four ops that ended where they started.
class NetChangesTest {
	private static final Path DH = Path.of("config", "DistantHorizons.toml");
	private static final Path SODIUM = Path.of("config", "sodium-options.json");
	private static final String RADIUS = "client.advanced.graphics.quality.lodChunkRenderDistanceRadius";
	private static final String DEFER = "performance.chunk_build_defer_mode";

	private final List<OpResult> results = new ArrayList<>();
	private final List<JournalEntry> entries = new ArrayList<>();

	// One switch: its op, applied by the helper, and its journal entry (before -> after, chained on the staged values).
	private Op patch(Path file, String key, String before, String after) {
		Op op = file == DH ? Op.patchToml(file, Map.of(key, after)) : Op.patchJson(file, Map.of(key, after));
		results.add(new OpResult(op, Status.OK, "ok"));
		String prefix = file == DH ? "dh." : "sodium.";
		entries.add(new JournalEntry("e-" + entries.size(), "2026-11-07T00:00:00Z", JournalEntry.APPLY, "0.5.2", "26.2", null,
				List.of(JournalChange.setting(prefix + key, before, after, JournalChange.APPLIED, op.id()))));
		return op;
	}

	private ApplyResult.Counts counts() {
		return NetChanges.counts(new ApplyResult("2026-11-07T00:00:00Z", results), entries);
	}

	@Test
	void theFieldCaseNetsToNothing() {
		patch(DH, RADIUS, "256", "160");
		patch(DH, RADIUS, "160", "256");
		patch(SODIUM, DEFER, "ALWAYS", "ONE_FRAME");
		patch(SODIUM, DEFER, "ONE_FRAME", "ALWAYS");
		assertEquals(new ApplyResult.Counts(0, 0, 0), counts());
		assertEquals(new ApplyResult.Counts(4, 0, 0), new ApplyResult("x", results).counts(), "the plain count, as 0.5.1 showed it");
	}

	@Test
	void aToBToAToBIsOneAndTwoKeysAreTwo() {
		patch(SODIUM, DEFER, "ALWAYS", "ONE_FRAME");
		patch(SODIUM, DEFER, "ONE_FRAME", "ALWAYS");
		patch(SODIUM, DEFER, "ALWAYS", "ONE_FRAME");
		assertEquals(new ApplyResult.Counts(1, 0, 0), counts());
		patch(DH, RADIUS, "256", "160");
		assertEquals(new ApplyResult.Counts(2, 0, 0), counts());
	}

	// A patch the journal knows nothing of counts one, as before; file changes and failed groups count as before.
	@Test
	void unjournaledPatchesFilesAndFailuresCountAsBefore() {
		results.add(new OpResult(Op.patchJson(SODIUM, Map.of(DEFER, "ALWAYS")), Status.OK, "ok"));
		List<Op> update = PendingActions.group(Op.disableFile(Path.of("mods", "x-1.jar")), Op.enableFile(Path.of("mods", "x-2.jar.rigtune-pending"),
				Path.of("mods", "x-2.jar")));
		update.forEach(op -> results.add(new OpResult(op, Status.OK, "ok")));
		results.add(new OpResult(Op.patchToml(DH, Map.of(RADIUS, "128")), Status.FAILED, "locked"));
		assertEquals(new ApplyResult.Counts(2, 1, 0), counts());
	}

	@Test
	void aQueueChangesSomethingUnlessEveryKeyEndsOnItsFileValue() {
		Map<String, String> disk = Map.of(RADIUS, "256", DEFER, "ALWAYS");
		List<Op> backAndForth = List.of(Op.patchToml(DH, Map.of(RADIUS, "160")), Op.patchToml(DH, Map.of(RADIUS, "256")),
				Op.patchJson(SODIUM, Map.of(DEFER, "ONE_FRAME")), Op.patchJson(SODIUM, Map.of(DEFER, "ALWAYS")));
		assertFalse(PendingNet.changesSomething(backAndForth, (op, key) -> disk.get(key)));
		List<Op> oneMore = new ArrayList<>(backAndForth);
		oneMore.add(Op.patchJson(SODIUM, Map.of(DEFER, "ONE_FRAME")));
		assertTrue(PendingNet.changesSomething(oneMore, (op, key) -> disk.get(key)));
		assertTrue(PendingNet.changesSomething(backAndForth, (op, key) -> null), "a file that can't be read hides nothing");
		assertTrue(PendingNet.changesSomething(List.of(Op.disableFile(Path.of("mods", "x.jar"))), (op, key) -> disk.get(key)), "a mod file");
		assertFalse(PendingNet.changesSomething(List.of(), (op, key) -> disk.get(key)));
	}
}
