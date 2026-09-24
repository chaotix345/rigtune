package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyLockTest {
	@Test
	void secondHolderWaitsThenGivesUp(@TempDir Path dir) throws Exception {
		Path file = ApplyLock.defaultPath(dir);
		try (ApplyLock first = ApplyLock.acquire(file, Duration.ZERO)) {
			assertNotNull(first);
			long start = System.nanoTime();
			assertNull(ApplyLock.acquire(file, Duration.ofMillis(200)));
			assertTrue(System.nanoTime() - start >= Duration.ofMillis(200).toNanos());
		}
		try (ApplyLock again = ApplyLock.acquire(file, Duration.ZERO)) {
			assertNotNull(again);
		}
		assertEquals(ApplyLock.defaultPath(dir), ApplyLock.besidePlan(PendingActions.defaultPath(dir)));
	}

	private static Process helper(long gamePid, Path pending, Path log) throws Exception {
		List<Path> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
				.filter(s -> !s.isBlank())
				.map(Path::of)
				.toList();
		return new ProcessBuilder(HelperLauncher.buildCommand(HelperLauncher.currentJava(), classpath, gamePid, pending))
				.redirectErrorStream(true)
				.redirectOutput(log.toFile())
				.start();
	}

	static long deadPid() throws Exception {
		Process finished = new ProcessBuilder(HelperLauncher.currentJava().toString(), "-version")
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
		assertTrue(finished.waitFor(60, TimeUnit.SECONDS));
		return finished.pid();
	}

	private static boolean heldElsewhere(Path lockFile) throws Exception {
		try (ApplyLock probe = ApplyLock.acquire(lockFile, Duration.ZERO)) {
			return probe == null;
		}
	}

	@Test
	void helperHoldsTheLockWhileWaitingAndAKilledHelperReleasesIt(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path config = Files.createDirectories(dir.resolve("config"));
		Path jar = Files.writeString(mods.resolve("a.jar"), "a");
		Path pending = PendingActions.defaultPath(config);
		PendingActions.create(1, mods, config, List.of(Op.disableFile(jar))).save(pending);
		Path lockFile = ApplyLock.defaultPath(config);

		// The helper waits for this (live) test JVM, as it would for a game that is still closing.
		Process helper = helper(ProcessHandle.current().pid(), pending, dir.resolve("helper.log"));
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
			while (!heldElsewhere(lockFile) && System.nanoTime() < deadline && helper.isAlive()) {
				Thread.sleep(100);
			}
			assertTrue(heldElsewhere(lockFile), Files.readString(dir.resolve("helper.log")));
			assertNull(ApplyLock.acquire(lockFile, Duration.ofMillis(300)));
		} finally {
			helper.destroyForcibly();
			assertTrue(helper.waitFor(30, TimeUnit.SECONDS));
		}

		try (ApplyLock lock = ApplyLock.acquire(lockFile, Duration.ofSeconds(10))) {
			assertNotNull(lock, "a killed helper's lock is released by the OS");
		}
		assertTrue(Files.exists(jar));
		assertEquals(1, PendingActions.load(pending).ops().size());
	}

	@Test
	void helperWaitsForStagingToReleaseTheLock(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path config = Files.createDirectories(dir.resolve("config"));
		Path jar = Files.writeString(mods.resolve("a.jar"), "a");
		Path pending = PendingActions.defaultPath(config);
		PendingActions.create(1, mods, config, List.of(Op.disableFile(jar))).save(pending);
		Path log = dir.resolve("helper.log");

		Process helper;
		try (ApplyLock staging = ApplyLock.acquire(ApplyLock.defaultPath(config), Duration.ZERO)) {
			assertNotNull(staging);
			helper = helper(deadPid(), pending, log);
			assertFalse(helper.waitFor(3, TimeUnit.SECONDS), "the helper must not run while staging holds the lock");
			assertTrue(Files.exists(jar));
			Op stagedMeanwhile = Op.disableFile(mods.resolve("b.jar"));
			PendingActions plan = PendingActions.load(pending);
			List<Op> ops = new ArrayList<>(plan.ops());
			ops.add(stagedMeanwhile);
			plan.withOps(ops).save(pending);
		}

		boolean exited = helper.waitFor(90, TimeUnit.SECONDS);
		if (!exited) {
			helper.destroyForcibly();
		}
		String output = Files.readString(log);
		assertTrue(exited, output);
		assertEquals(0, helper.exitValue(), output);
		assertTrue(Files.exists(mods.resolve("a.jar.disabled")), output);
		assertFalse(Files.exists(pending), "both ops ran, since the helper read the plan after staging finished: " + output);
	}
}
