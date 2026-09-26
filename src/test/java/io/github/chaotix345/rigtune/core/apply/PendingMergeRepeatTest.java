package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// docs/research/v0.4/audit-apply-pipeline.md M1 (coordinator decision for WS-P): a patch op only repeats the LAST pending op
// for its file and key; an identical older one that a later op overrode is no repeat.
class PendingMergeRepeatTest {
	private static final String KEY = "performance.chunk_build_defer_mode";

	@TempDir
	Path dir;

	private PendingActions empty() {
		return PendingActions.create(1, dir.resolve("mods"), dir.resolve("config"), List.of());
	}

	private Op patch(String value) {
		return Op.patchJson(dir.resolve("config").resolve("sodium-options.json"), Map.of(KEY, value));
	}

	private static List<String> values(PendingActions plan) {
		return plan.ops().stream().map(op -> op.patches().get(KEY)).toList();
	}

	@Test
	void aToBToAToBKeepsTheLastB() {
		PendingActions plan = empty();
		Op b1 = patch("ALWAYS");
		plan = plan.merge(List.of(b1)).plan();
		plan = plan.merge(List.of(patch("ONE_FRAME"))).plan();
		Op b3 = patch("ALWAYS");
		PendingActions.Merged merged = plan.merge(List.of(b3));
		assertEquals(List.of("ALWAYS", "ONE_FRAME", "ALWAYS"), values(merged.plan()));
		assertEquals(Map.of(b3.id(), b3.id()), merged.survivingIds());
		// A fourth switch back to A repeats nothing either; the helper applies them in order.
		assertEquals(List.of("ALWAYS", "ONE_FRAME", "ALWAYS", "ONE_FRAME"), values(merged.plan().merge(List.of(patch("ONE_FRAME"))).plan()));
	}

	@Test
	void anImmediateRepeatIsStillDropped() {
		Op first = patch("ALWAYS");
		PendingActions plan = empty().merge(List.of(first)).plan();
		Op again = patch("ALWAYS");
		PendingActions.Merged merged = plan.merge(List.of(again));
		assertEquals(List.of("ALWAYS"), values(merged.plan()));
		assertEquals(Map.of(again.id(), first.id()), merged.survivingIds());
		// A later op for another key of the same file doesn't stop the repeat.
		Op other = Op.patchJson(dir.resolve("config").resolve("sodium-options.json"), Map.of("performance.use_entity_culling", "false"));
		PendingActions withOther = plan.merge(List.of(other)).plan();
		Op third = patch("ALWAYS");
		PendingActions.Merged repeat = withOther.merge(List.of(third));
		assertEquals(2, repeat.plan().ops().size());
		assertEquals(Map.of(third.id(), first.id()), repeat.survivingIds());
	}

	@Test
	void anotherFilesOpForTheSameKeyDoesntCount() {
		Op first = patch("ALWAYS");
		PendingActions plan = empty().merge(List.of(first)).plan();
		Op elsewhere = Op.patchJson(dir.resolve("config").resolve("other.json"), Map.of(KEY, "ONE_FRAME"));
		plan = plan.merge(List.of(elsewhere)).plan();
		Op again = patch("ALWAYS");
		assertEquals(Map.of(again.id(), first.id()), plan.merge(List.of(again)).survivingIds());
	}
}
