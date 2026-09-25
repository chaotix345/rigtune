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
	// Acquires on another thread, like a second holder in this JVM that isn't nested in the first.
	private static ApplyLock onOtherThread(Path file, Duration wait) throws Exception {
		ApplyLock[] out = new ApplyLock[1];
		Thread thread = new Thread(() -> {
			try {
				out[0] = ApplyLock.acquire(file, wait);
				if (out[0] != null) {
					out[0].close();
				}
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		});
		thread.start();
		thread.join();
		return out[0];
	}

	@Test
	void otherThreadWaitsThenGivesUp(@TempDir Path dir) throws Exception {
		Path file = ApplyLock.defaultPath(dir);
		try (ApplyLock first = ApplyLock.acquire(file, Duration.ZERO)) {
			assertNotNull(first);
			long start = System.nanoTime();
			assertNull(onOtherThread(file, Duration.ofMillis(200)));
			assertTrue(System.nanoTime() - start >= Duration.ofMillis(200).toNanos());
		}
		assertNotNull(onOtherThread(file, Duration.ZERO));
		assertEquals(ApplyLock.defaultPath(dir), ApplyLock.besidePlan(PendingActions.defaultPath(dir)));
	}

	// Review M4: the journal takes the lock while stage(), preLaunch or an undo already hold it.
	@Test
	void sameThreadReentersAndKeepsTheLockUntilTheOuterHolderCloses(@TempDir Path dir) throws Exception {
		Path file = ApplyLock.defaultPath(dir);
		try (ApplyLock outer = ApplyLock.acquire(file, Duration.ZERO)) {
			assertNotNull(outer);
			try (ApplyLock inner = ApplyLock.acquire(ApplyLock.besidePlan(PendingActions.defaultPath(dir)), Duration.ZERO)) {
				assertNotNull(inner);
			}
			assertNull(onOtherThread(file, Duration.ZERO), "closing the nested holder must not release the lock");
			assertFalse(childCanLock(file), "another process must still be locked out");
		}
		assertNotNull(onOtherThread(file, Duration.ZERO));
		assertTrue(childCanLock(file));
	}

	@Test
	void differentFilesAreIndependent(@TempDir Path dir) throws Exception {
		try (ApplyLock a = ApplyLock.acquire(ApplyLock.defaultPath(dir.resolve("a")), Duration.ZERO)) {
			assertNotNull(a);
			assertNotNull(onOtherThread(ApplyLock.defaultPath(dir.resolve("b")), Duration.ZERO));
		}
	}

	@Test
	void closingTwiceIsHarmless(@TempDir Path dir) throws Exception {
		Path file = ApplyLock.defaultPath(dir);
		try (ApplyLock outer = ApplyLock.acquire(file, Duration.ZERO)) {
			assertNotNull(outer);
			ApplyLock inner = ApplyLock.acquire(file, Duration.ZERO);
			assertNotNull(inner);
			inner.close();
			inner.close();
			assertNull(onOtherThread(file, Duration.ZERO));
		}
		assertNotNull(onOtherThread(file, Duration.ZERO));
	}

	// A separate JVM that tries the OS lock once: true if it got it.
	private static boolean childCanLock(Path file) throws Exception {
		Path source = Files.writeString(file.resolveSibling("TryLock.java"), """
				import java.nio.channels.FileChannel;
				import java.nio.file.Path;
				import java.nio.file.StandardOpenOption;
				public class TryLock {
					public static void main(String[] args) throws Exception {
						try (FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
							System.exit(channel.tryLock() != null ? 0 : 1);
						}
					}
				}
				""");
		Process child = new ProcessBuilder(HelperLauncher.currentJava().toString(), source.toString(), file.toString())
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.start();
		assertTrue(child.waitFor(60, TimeUnit.SECONDS));
		return child.exitValue() == 0;
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

	// A game JVM can linger after its window closes; a relaunched game must still be able to stage meanwhile.
	@Test
	void helperLeavesTheLockFreeWhileTheGameIsStillRunning(@TempDir Path dir) throws Exception {
		Path mods = Files.createDirectories(dir.resolve("mods"));
		Path config = Files.createDirectories(dir.resolve("config"));
		Path jar = Files.writeString(mods.resolve("a.jar"), "a");
		Path pending = PendingActions.defaultPath(config);
		PendingActions.create(1, mods, config, List.of(Op.disableFile(jar))).save(pending);
		Path lockFile = ApplyLock.defaultPath(config);
		Path log = dir.resolve("helper.log");

		// The helper waits for this (live) test JVM, as it would for a game that is still closing.
		Process helper = helper(ProcessHandle.current().pid(), pending, log);
		try {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
			while (!Files.readString(log).contains("Waiting for game process") && System.nanoTime() < deadline && helper.isAlive()) {
				Thread.sleep(100);
			}
			assertTrue(Files.readString(log).contains("Waiting for game process"), Files.readString(log));
			for (int i = 0; i < 5; i++) {
				try (ApplyLock staging = ApplyLock.acquire(lockFile, Duration.ofMillis(200))) {
					assertNotNull(staging, "staging must not wait for a helper that is only waiting for the game: " + Files.readString(log));
				}
				Thread.sleep(200);
			}
			assertTrue(helper.isAlive(), Files.readString(log));
		} finally {
			helper.destroyForcibly();
			assertTrue(helper.waitFor(30, TimeUnit.SECONDS));
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
