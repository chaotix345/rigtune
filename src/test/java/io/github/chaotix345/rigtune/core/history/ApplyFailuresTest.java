package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.history.ApplyFailures.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// docs/v0.3/SPEC.md 3e, AC3.5 and review B-M1: what last-apply.json says failed, with "attempt n of 3" = attempts + 1.
class ApplyFailuresTest {
	@TempDir
	Path game;

	private ApplyResult fixture(String resource) throws IOException {
		Path mods = game.resolve("mods");
		Path config = game.resolve("config");
		return ApplyResult.load(V010Fixtures.install(resource, config.resolve("rigtune").resolve("last-apply.json"), mods, config));
	}

	private List<Path> dirs() {
		return List.of(game.resolve("mods"), game.resolve("config"));
	}

	// The user's real 0.1.0 run: the DH update group failed on a sharing violation, first attempt.
	@Test
	void theRealV010FailureGivesOneWarningPerFailedOp() throws IOException {
		List<Failure> failures = ApplyFailures.of(fixture("real-instance/last-apply.json"), dirs());

		assertEquals(2, failures.size(), failures.toString());
		Failure disable = failures.get(0);
		assertEquals("041919d9-18c9-4b04-89c4-f15e4e4a80c5", disable.opId());
		assertEquals(Status.FAILED, disable.status());
		assertEquals(PendingActions.Type.DISABLE_FILE, disable.type());
		assertEquals("fabric-26.2.jar", disable.file());
		assertNull(disable.modId());
		assertEquals(1, disable.attempt());
		assertEquals("Gave up after 10 attempt(s): java.nio.file.FileSystemException: fabric-26.2.jar -> fabric-26.2.jar.disabled: "
				+ "The process cannot access the file because it is being used by another process", disable.reason());
		Failure enable = failures.get(1);
		assertEquals(PendingActions.Type.ENABLE_FILE, enable.type());
		assertEquals("distanthorizons", enable.modId());
		assertEquals("DistantHorizons-3.3.2-26.2-fabric-neoforge.jar", enable.file());
		assertEquals(1, enable.attempt());
		assertEquals("Not applied because disabling fabric-26.2.jar failed", enable.reason());

		List<String> lines = failures.stream().map(f -> ApplyFailures.warnLine(f, "2026-09-24T23:09:01.530708800Z")).toList();
		assertEquals("RigTune's helper couldn't apply a change (run finished 2026-09-24T23:09:01.530708800Z, restart attempt 1 of 3; it's retried at the "
				+ "next exit): DISABLE_FILE fabric-26.2.jar: " + disable.reason(), lines.get(0));
		assertEquals("RigTune's helper couldn't apply a change (run finished 2026-09-24T23:09:01.530708800Z, restart attempt 1 of 3; it's retried at the "
				+ "next exit): ENABLE_FILE distanthorizons (DistantHorizons-3.3.2-26.2-fabric-neoforge.jar): Not applied because disabling "
				+ "fabric-26.2.jar failed", lines.get(1));
		lines.forEach(line -> assertFalse(line.contains(game.toString()), line));
	}

	@Test
	void theHandWrittenFixtureHasOneFailure() throws IOException {
		List<Failure> failures = ApplyFailures.of(fixture("last-apply.json"), dirs());

		assertEquals(1, failures.size(), failures.toString());
		assertEquals("ferritecore", failures.getFirst().modId());
		assertEquals(1, failures.getFirst().attempt());
		assertEquals("ferritecore-8.0.0.jar already exists; not overwriting it", failures.getFirst().reason());
	}

	@Test
	void aCapturedRunThatWorkedHasNone() throws IOException {
		assertTrue(ApplyFailures.of(fixture("captured/last-apply.json"), dirs()).isEmpty());
	}

	@Test
	void abandonedOpsAreDroppedAndRetriedOnesCountTheirAttempt() {
		Op retried = Op.disableFile(game.resolve("mods").resolve("a.jar")).withAttempts(1);
		Op dropped = Op.enableFile(game.resolve("mods").resolve("b.jar.rigtune-pending"), game.resolve("mods").resolve("b.jar")).withModId("b").withAttempts(2);
		Op done = Op.disableFile(game.resolve("mods").resolve("c.jar"));
		ApplyResult result = new ApplyResult("2026-09-26T10:00:00Z", List.of(new OpResult(retried, Status.FAILED, "busy"),
				new OpResult(dropped, Status.ABANDONED, "Gave up after 3 failed attempts: busy"), new OpResult(done, Status.OK, "Disabled c.jar")));

		Map<String, Failure> byOp = ApplyFailures.byOpId(result, dirs());

		assertEquals(2, byOp.size());
		assertEquals(2, byOp.get(retried.id()).attempt());
		assertEquals(Status.ABANDONED, byOp.get(dropped.id()).status());
		assertEquals("RigTune's helper dropped a change (run finished 2026-09-26T10:00:00Z): ENABLE_FILE b (b.jar): Gave up after 3 failed attempts: busy",
				ApplyFailures.warnLine(byOp.get(dropped.id()), result.finishedAt()));
	}

	@Test
	void pathsOutsideTheFoldersAndOddMessagesAreKept() {
		Path elsewhere = game.resolveSibling("other").resolve("x.jar");
		Op op = Op.disableFile(elsewhere);
		ApplyResult result = new ApplyResult("t", List.of(new OpResult(op, Status.FAILED, elsewhere + " is locked\nby another program"),
				new OpResult(null, Status.FAILED, "no op"), new OpResult(Op.disableFile(game.resolve("mods").resolve("y.jar")), Status.FAILED, null)));

		List<Failure> failures = ApplyFailures.of(result, dirs());

		assertEquals(2, failures.size());
		assertEquals(elsewhere + " is locked by another program", failures.get(0).reason());
		assertEquals("?", failures.get(1).reason());
	}

	@Test
	void noResultHasNoFailures() {
		assertTrue(ApplyFailures.of(null, dirs()).isEmpty());
	}
}
