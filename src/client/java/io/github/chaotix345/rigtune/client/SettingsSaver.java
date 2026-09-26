package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SettingsSaver {
	private static final SettingsSaver SHARED = new SettingsSaver(executor());

	private final Executor executor;
	private @Nullable Target queued;
	private CompletableFuture<Void> last = CompletableFuture.completedFuture(null);

	private record Target(ClientSettings settings, Path configDir) {
	}

	SettingsSaver(Executor executor) {
		this.executor = executor;
	}

	public static SettingsSaver shared() {
		return SHARED;
	}

	public synchronized CompletableFuture<Void> save(ClientSettings settings, Path configDir) {
		Target target = new Target(settings, configDir);
		if (!target.equals(queued)) {
			queued = target;
			last = CompletableFuture.runAsync(() -> write(target), executor);
		}
		return last;
	}

	public boolean flush(long timeoutMillis) {
		CompletableFuture<Void> pending;
		synchronized (this) {
			pending = last;
		}
		try {
			pending.get(timeoutMillis, TimeUnit.MILLISECONDS);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException e) {
			RigTune.LOGGER.warn("A queued settings save didn't finish in {} ms", timeoutMillis, e);
		}
		return false;
	}

	private void write(Target target) {
		synchronized (this) {
			if (target.equals(queued)) {
				queued = null;
			}
		}
		target.settings().save(target.configDir());
	}

	private static Executor executor() {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
			Thread thread = new Thread(runnable, "RigTune settings");
			thread.setDaemon(true);
			return thread;
		});
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}
}
