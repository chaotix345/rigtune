package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

// An OS file lock on config/rigtune/apply.lock, held by the apply helper for its whole run and briefly by staging.
// The OS drops it when the holder exits, so a killed helper never leaves a stale lock; the file itself means nothing.
public final class ApplyLock implements AutoCloseable {
	public static final String FILE_NAME = "apply.lock";
	private static final long POLL_MILLIS = 50;

	private final FileChannel channel;
	private final FileLock lock;

	private ApplyLock(FileChannel channel, FileLock lock) {
		this.channel = channel;
		this.lock = lock;
	}

	public static Path defaultPath(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public static Path besidePlan(Path pendingJson) {
		return pendingJson.toAbsolutePath().resolveSibling(FILE_NAME);
	}

	// Null when someone else (another process, or another holder in this JVM) still has it after `wait`.
	public static ApplyLock acquire(Path file, Duration wait) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		long deadline = System.nanoTime() + Math.max(0, wait.toNanos());
		while (true) {
			FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			try {
				FileLock lock = channel.tryLock();
				if (lock != null) {
					return new ApplyLock(channel, lock);
				}
			} catch (OverlappingFileLockException ignored) {
			} catch (IOException | RuntimeException e) {
				channel.close();
				throw e;
			}
			channel.close();
			if (System.nanoTime() - deadline >= 0) {
				return null;
			}
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return null;
			}
		}
	}

	@Override
	public void close() {
		try {
			lock.release();
		} catch (IOException ignored) {
		} finally {
			try {
				channel.close();
			} catch (IOException ignored) {
			}
		}
	}
}
