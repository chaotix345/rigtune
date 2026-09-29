package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.5/SPEC.md 4f (AC4f.3) and 4d (AC4d.4): the next start's toasts. "N of M changes failed" counts FAILED ops only,
// ABANDONED ones appear only in the dropped toast (0.4 counted both as failed: rw §2.4's follow-up); held mod changes get
// their own "waiting for your choice" toast and WARN, never "retried at the next exit".
class HelperToastsTest {
	private static OpResult result(Status status) {
		return new OpResult(Op.disableFile(Path.of("mods", "x.jar")), status, status.name());
	}

	private static ApplyResult of(Status... statuses) {
		List<OpResult> results = new ArrayList<>();
		Arrays.stream(statuses).forEach(s -> results.add(result(s)));
		return new ApplyResult("2026-09-27T10:00:00Z", results);
	}

	private static String key(Component c) {
		return ((TranslatableContents) c.getContents()).getKey();
	}

	private static List<Object> args(Component c) {
		return List.of(((TranslatableContents) c.getContents()).getArgs());
	}

	private static List<String> titles(List<HelperToasts.Toast> toasts) {
		return toasts.stream().map(t -> key(t.title()) + args(t.title())).toList();
	}

	@Test
	void abandonedOnlyShowsTheDroppedToastAlone() {
		assertEquals(List.of("rigtune.toast.abandoned.title[2]"), titles(HelperToasts.result(of(Status.ABANDONED, Status.ABANDONED))));
	}

	@Test
	void failedOnlyCountsTheFailedOps() {
		assertEquals(List.of("rigtune.toast.failed.title[1, 2]"), titles(HelperToasts.result(of(Status.OK, Status.FAILED))));
	}

	@Test
	void mixedCountsEachKindOnce() {
		assertEquals(List.of("rigtune.toast.failed.title[1, 4]", "rigtune.toast.abandoned.title[2]"),
				titles(HelperToasts.result(of(Status.OK, Status.FAILED, Status.ABANDONED, Status.ABANDONED))));
	}

	@Test
	void appliedAndDroppedCountTheAppliedOnes() {
		assertEquals(List.of("rigtune.toast.applied.title[2]", "rigtune.toast.abandoned.title[1]"),
				titles(HelperToasts.result(of(Status.OK, Status.SKIPPED_ALREADY_DONE, Status.ABANDONED))));
	}

	@Test
	void allAppliedIsTodaysToast() {
		List<HelperToasts.Toast> toasts = HelperToasts.result(of(Status.OK, Status.OK, Status.SKIPPED_ALREADY_DONE));

		assertEquals(List.of("rigtune.toast.applied.title[3]"), titles(toasts));
		assertEquals("rigtune.toast.applied.body", key(toasts.getFirst().body()));
	}

	@Test
	void anEmptyResultShowsNothing() {
		assertTrue(HelperToasts.result(of()).isEmpty());
	}

	// #21: an update is one change, as History and Apply count it: its disable and enable (one group) count once, by the
	// group's outcome.
	private static List<OpResult> update(String mod, Status disable, Status enable) {
		List<Op> ops = PendingActions.group(Op.disableFile(Path.of("mods", mod + "-1.jar")),
				Op.enableFile(Path.of("staging", mod + "-2.jar"), Path.of("mods", mod + "-2.jar")));
		return List.of(new OpResult(ops.get(0), disable, disable.name()), new OpResult(ops.get(1), enable, enable.name()));
	}

	@Test
	void anUpdateCountsOnce() {
		List<OpResult> results = new ArrayList<>(update("modmenu", Status.OK, Status.OK));
		results.addAll(update("sodium", Status.SKIPPED_ALREADY_DONE, Status.OK));

		assertEquals(List.of("rigtune.toast.applied.title[2]"), titles(HelperToasts.result(new ApplyResult("2026-09-29T10:00:00Z", results))));
	}

	@Test
	void anUpdateCountsByItsGroupsOutcome() {
		List<OpResult> results = new ArrayList<>(update("modmenu", Status.OK, Status.OK));
		results.addAll(update("sodium", Status.OK, Status.FAILED));
		results.addAll(update("lithium", Status.ABANDONED, Status.ABANDONED));
		results.add(result(Status.OK));

		assertEquals(new ApplyResult.Counts(2, 1, 1), new ApplyResult("2026-09-29T10:00:00Z", results).counts());
	}

	@Test
	void countsOfEveryKind() {
		assertEquals(new ApplyResult.Counts(2, 1, 3), of(Status.OK, Status.SKIPPED_ALREADY_DONE, Status.FAILED, Status.ABANDONED, Status.ABANDONED,
				Status.ABANDONED).counts());
	}

	// 4d: whatever the helper will retry keeps today's toast and WARN; held mod changes get their own.
	@Test
	void leftoversRetriedAtExitKeepTodaysWording() {
		List<HelperToasts.Toast> toasts = HelperToasts.leftover(3, 0);

		assertEquals(List.of("rigtune.toast.leftover.title[3]"), titles(toasts));
		assertEquals(List.of("3 staged RigTune change(s) were not applied; they will be retried at the next exit"), HelperToasts.warnLines(3, 0));
	}

	@Test
	void heldModChangesWaitForAChoice() {
		List<HelperToasts.Toast> toasts = HelperToasts.leftover(0, 2);

		assertEquals(List.of("rigtune.toast.held.title[2]"), titles(toasts));
		assertEquals("rigtune.toast.held.body", key(toasts.getFirst().body()));
		List<String> warn = HelperToasts.warnLines(0, 2);
		assertEquals(List.of("2 mod change(s) from an earlier Apply are waiting for your choice: this instance's launcher keeps its own list"
				+ " of mods, so RigTune holds them at exit (cancel them or let RigTune apply them from RigTune's notice)"), warn);
		assertFalse(warn.getFirst().contains("retried"));
	}

	@Test
	void bothKindsGetTheirOwnToast() {
		assertEquals(List.of("rigtune.toast.leftover.title[1]", "rigtune.toast.held.title[2]"), titles(HelperToasts.leftover(1, 2)));
		assertEquals(2, HelperToasts.warnLines(1, 2).size());
	}
}
