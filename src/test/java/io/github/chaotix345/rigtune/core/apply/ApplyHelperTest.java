package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Runs in the helper's own JVM with only our jar + Gson on the classpath, so these tests (like ApplyHelper itself)
// touch no RigTune.LOGGER, Fabric or Minecraft class.
class ApplyHelperTest {
	@TempDir
	Path dir;

	// Comfortably outside any real pid on this machine, so ProcessHandle.of(pid) is empty and waitForGame goes
	// straight to the settle delay without needing to fake a real process exiting.
	private static final String ABSENT_PID = "999999999";

	@Test
	void waitsTheSettleDelayAfterTheGameIsGoneBeforeTakingTheLock() {
		Path pending = dir.resolve("pending.json");
		List<Long> slept = new ArrayList<>();
		ApplyHelper.Sleeper sleeper = millis -> {
			slept.add(millis);
			return true;
		};

		int code = ApplyHelper.run(new String[] {ABSENT_PID, pending.toString()}, sleeper);

		assertEquals(0, code, "nothing pending, so the run should succeed once the settle delay has passed");
		assertEquals(List.of(ApplyHelper.SETTLE_MILLIS), slept);
	}

	@Test
	void anInterruptedSettleDelayStopsBeforeTakingTheLock() {
		Path pending = dir.resolve("pending.json");
		ApplyHelper.Sleeper sleeper = millis -> false;

		int code = ApplyHelper.run(new String[] {ABSENT_PID, pending.toString()}, sleeper);

		assertEquals(3, code);
	}

	// Review 4, security-1: an Error during the run is logged and the helper exits non-zero, leaving pending.json for
	// the next exit instead of crashing.
	@Test
	void anErrorDuringTheRunIsLoggedAndLeavesThePlan() throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path config = Files.createDirectories(dir.resolve("config"));
		Path pending = PendingActions.defaultPath(config);
		Files.writeString(mods.resolve("old.jar"), "old");
		PendingActions.create(1, mods, config, List.of(PendingActions.Op.disableFile(mods.resolve("old.jar")))).save(pending);
		String plan = Files.readString(pending);
		ApplyExecutor failing = new ApplyExecutor(1, 0, (from, to) -> {
			throw new OutOfMemoryError("simulated");
		});
		ByteArrayOutputStream log = new ByteArrayOutputStream();
		PrintStream out = System.out;
		System.setOut(new PrintStream(log, true, StandardCharsets.UTF_8));
		int code;
		try {
			code = ApplyHelper.run(new String[] {ABSENT_PID, pending.toString()}, millis -> true, failing);
		} finally {
			System.setOut(out);
		}

		assertEquals(1, code);
		assertTrue(log.toString(StandardCharsets.UTF_8).contains("Apply failed: java.lang.OutOfMemoryError: simulated"), log.toString(StandardCharsets.UTF_8));
		assertEquals(plan, Files.readString(pending));
		assertEquals("old", Files.readString(mods.resolve("old.jar")));
	}
}
