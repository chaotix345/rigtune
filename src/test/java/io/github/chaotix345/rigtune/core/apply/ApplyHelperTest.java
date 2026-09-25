package io.github.chaotix345.rigtune.core.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
