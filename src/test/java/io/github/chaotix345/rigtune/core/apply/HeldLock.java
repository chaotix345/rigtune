package io.github.chaotix345.rigtune.core.apply;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// Holds the apply lock on another thread, like a helper process would: ApplyLock is reentrant on the thread that holds
// it, so a test that fakes a busy helper must not take the lock on its own thread.
public final class HeldLock implements AutoCloseable {
	private final CountDownLatch release = new CountDownLatch(1);
	private final Thread thread;

	private HeldLock(Path lockFile) throws InterruptedException {
		CountDownLatch held = new CountDownLatch(1);
		Throwable[] error = new Throwable[1];
		thread = new Thread(() -> {
			try (ApplyLock lock = ApplyLock.acquire(lockFile, Duration.ZERO)) {
				if (lock == null) {
					throw new IllegalStateException("lock already held: " + lockFile);
				}
				held.countDown();
				release.await();
			} catch (Throwable t) {
				error[0] = t;
				held.countDown();
			}
		}, "held-apply-lock");
		thread.start();
		if (!held.await(30, TimeUnit.SECONDS) || error[0] != null) {
			throw new IllegalStateException("could not hold " + lockFile, error[0]);
		}
	}

	public static HeldLock hold(Path lockFile) throws InterruptedException {
		return new HeldLock(lockFile);
	}

	@Override
	public void close() throws InterruptedException {
		release.countDown();
		thread.join(30_000);
	}
}
