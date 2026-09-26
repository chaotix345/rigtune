package io.github.chaotix345.rigtune.client.probe;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Probes {
	public static final ExecutorService EXECUTOR = daemonPool(2, "RigTune worker");
	// Modrinth lookups and downloads (Phase 5 P5A-F4): a request can wait up to 10 s to connect and 20 s for an answer, so
	// they run here and never hold up EXECUTOR's History loads, Undo plans, report rebuilds and saves. Rules fetches have
	// their own thread (RealController), and so do previews (PreviewScreen) and settings saves (SettingsSaver).
	public static final ExecutorService NETWORK = daemonPool(2, "RigTune network");

	private Probes() {
	}

	private static ExecutorService daemonPool(int threads, String name) {
		return Executors.newFixedThreadPool(threads, runnable -> {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		});
	}
}
