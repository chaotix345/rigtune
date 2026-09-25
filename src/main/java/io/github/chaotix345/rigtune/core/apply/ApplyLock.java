package io.github.chaotix345.rigtune.core.apply;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

// An OS file lock on config/rigtune/apply.lock, held by the apply helper for its whole run and briefly by staging.
// The OS drops it when the holder exits, so a killed helper never leaves a stale lock; the file itself means nothing.
// Within one JVM it is reentrant per thread: a nested acquire (the journal inside stage(), preLaunch or an undo) gets
// it at once, and the OS lock is released only when the outermost holder closes. Other threads wait as other
// processes do.
public final class ApplyLock implements AutoCloseable {
	public static final String FILE_NAME = "apply.lock";
	private static final long POLL_MILLIS = 50;
	private static final Map<Path, Shared> SHARED = new ConcurrentHashMap<>();

	private static final class Shared {
		final ReentrantLock jvm = new ReentrantLock();
		FileChannel channel;
		FileLock lock;
	}

	private final Shared shared;
	private boolean closed;

	private ApplyLock(Shared shared) {
		this.shared = shared;
	}

	public static Path defaultPath(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public static Path besidePlan(Path pendingJson) {
		return pendingJson.toAbsolutePath().resolveSibling(FILE_NAME);
	}

	// Null when someone else (another process, or another thread in this JVM) still has it after `wait`.
	public static ApplyLock acquire(Path file, Duration wait) throws IOException {
		Path absolute = file.toAbsolutePath().normalize();
		Files.createDirectories(absolute.getParent());
		// By real path, so the same folder reached through a symlink (or another spelling) is the same lock.
		Path key = absolute.getParent().toRealPath().resolve(absolute.getFileName());
		Shared shared = SHARED.computeIfAbsent(key, k -> new Shared());
		if (shared.jvm.isHeldByCurrentThread()) {
			// Re-entering never waits, so an interrupted holder gets it too.
			shared.jvm.lock();
			return new ApplyLock(shared);
		}
		long waitNanos = Math.max(0, wait.toNanos());
		long deadline = System.nanoTime() + waitNanos;
		try {
			if (!shared.jvm.tryLock(waitNanos, TimeUnit.NANOSECONDS)) {
				return null;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
		boolean locked = false;
		try {
			locked = lockFile(shared, key, deadline);
		} finally {
			if (!locked) {
				shared.jvm.unlock();
			}
		}
		return locked ? new ApplyLock(shared) : null;
	}

	private static boolean lockFile(Shared shared, Path file, long deadline) throws IOException {
		while (true) {
			FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			try {
				FileLock lock = channel.tryLock();
				if (lock != null) {
					shared.channel = channel;
					shared.lock = lock;
					return true;
				}
			} catch (OverlappingFileLockException ignored) {
			} catch (IOException | RuntimeException e) {
				channel.close();
				throw e;
			}
			channel.close();
			if (System.nanoTime() - deadline >= 0) {
				return false;
			}
			try {
				Thread.sleep(POLL_MILLIS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
	}

	// Must be called on the thread that acquired it (try-with-resources). Another thread is refused before anything
	// changes, so the owner can still release it.
	@Override
	public void close() {
		if (closed) {
			return;
		}
		if (!shared.jvm.isHeldByCurrentThread()) {
			throw new IllegalStateException("The apply lock can only be released by the thread that took it");
		}
		closed = true;
		try {
			if (shared.jvm.getHoldCount() == 1) {
				try {
					shared.lock.release();
				} catch (IOException ignored) {
				} finally {
					try {
						shared.channel.close();
					} catch (IOException ignored) {
					}
					shared.lock = null;
					shared.channel = null;
				}
			}
		} finally {
			shared.jvm.unlock();
		}
	}
}
