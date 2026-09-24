package io.github.chaotix345.rigtune.client.probe;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Probes {
	public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "RigTune worker");
		thread.setDaemon(true);
		return thread;
	});

	private Probes() {
	}
}
