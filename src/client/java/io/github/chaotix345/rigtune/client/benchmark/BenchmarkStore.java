package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

// The client's copy of config/rigtune/benchmarks.json: read once, saved after every finished run.
public final class BenchmarkStore {
	private static @Nullable BenchmarkHistory history;

	private BenchmarkStore() {
	}

	public static Path file() {
		return BenchmarkHistory.defaultPath(FabricLoader.getInstance().getConfigDir());
	}

	public static synchronized BenchmarkHistory history() {
		if (history == null) {
			history = BenchmarkHistory.load(file());
		}
		return history;
	}

	static synchronized void add(BenchmarkRecord run) {
		history = history().with(run);
		try {
			history.save(file());
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not save {}", file(), e);
		}
	}
}
