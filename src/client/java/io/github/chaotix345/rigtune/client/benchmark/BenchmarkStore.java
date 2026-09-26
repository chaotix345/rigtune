package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkHistory;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;

// The client's copy of config/rigtune/benchmarks.json: read once, saved after every finished run, and read again when the
// file changed on disk since (v0.4: another copy of the game, a hand edit or a restored backup), so the trend and its
// notices never show an old copy and a new run never overwrites newer runs.
public final class BenchmarkStore {
	private static @Nullable BenchmarkHistory history;
	private static @Nullable Object stamp;

	private BenchmarkStore() {
	}

	public static Path file() {
		return BenchmarkHistory.defaultPath(FabricLoader.getInstance().getConfigDir());
	}

	public static synchronized BenchmarkHistory history() {
		if (history == null || !Objects.equals(stamp, stamp(file()))) {
			history = BenchmarkHistory.load(file());
			stamp = stamp(file());
		}
		return history;
	}

	// The file's modification time and size, or "missing".
	private static Object stamp(Path file) {
		try {
			BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
			return List.of(attributes.lastModifiedTime(), attributes.size());
		} catch (IOException e) {
			return "missing";
		}
	}

	static synchronized void add(BenchmarkRecord run) {
		if (history().unreadable()) {
			history = BenchmarkHistory.load(file());
		}
		history = history().with(run);
		if (history.unreadable()) {
			RigTune.LOGGER.warn("Not saving this run: {} couldn't be read, and overwriting it would lose it", file());
			return;
		}
		try {
			history = history.save(file());
			stamp = stamp(file());
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not save {}", file(), e);
		}
	}
}
