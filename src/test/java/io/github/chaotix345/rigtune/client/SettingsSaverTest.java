package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.probe.Probes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSaverTest {
	@Test
	void aSaveIsNotBlockedByABusyWorkerPool(@TempDir Path configDir) throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch busy = new CountDownLatch(2);
		for (int i = 0; i < 2; i++) {
			Probes.EXECUTOR.execute(() -> {
				busy.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
		}
		try {
			assertTrue(busy.await(5, TimeUnit.SECONDS), "both worker threads busy");
			ClientSettings settings = new ClientSettings();
			settings.modrinth = false;
			SettingsSaver.shared().save(settings, configDir).get(2, TimeUnit.SECONDS);
			assertFalse(ClientSettings.load(configDir).modrinth);
		} finally {
			release.countDown();
		}
	}

	@Test
	void rapidChangesCoalesceIntoOneWriteOfTheLatestValues(@TempDir Path configDir) {
		Queue<Runnable> tasks = new ArrayDeque<>();
		SettingsSaver saver = new SettingsSaver(tasks::add);
		ClientSettings settings = new ClientSettings();
		settings.networkEnabled = false;
		saver.save(settings, configDir);
		settings.modrinth = false;
		saver.save(settings, configDir);
		settings.startupToast = false;
		saver.save(settings, configDir);
		assertEquals(1, tasks.size());

		tasks.poll().run();
		ClientSettings saved = ClientSettings.load(configDir);
		assertTrue(!saved.networkEnabled && !saved.modrinth && !saved.startupToast);

		settings.startupToast = true;
		saver.save(settings, configDir);
		assertEquals(1, tasks.size());
		tasks.poll().run();
		assertTrue(ClientSettings.load(configDir).startupToast);
	}

	@Test
	void flushWaitsForTheQueuedSaveButNotForever(@TempDir Path configDir) throws Exception {
		ExecutorService single = Executors.newSingleThreadExecutor();
		CountDownLatch release = new CountDownLatch(1);
		try {
			single.execute(() -> {
				try {
					release.await(30, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			});
			SettingsSaver saver = new SettingsSaver(single);
			assertTrue(saver.flush(100), "nothing queued yet");
			saver.save(new ClientSettings(), configDir);
			assertFalse(saver.flush(100), "bounded while the save can't run");
			release.countDown();
			assertTrue(saver.flush(2_000));
			assertTrue(Files.isRegularFile(ClientSettings.file(configDir)));
		} finally {
			release.countDown();
			single.shutdownNow();
		}
	}
}
