package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// config/rigtune/stutter.json (docs/v0.4/SPEC.md 5, "Shared contracts" C1): the last 5 capture summaries, each with at
// most 10 worst spikes, at most 64 KiB (the oldest sessions are dropped first). Local only: it's in no report unless
// the player presses Copy summary. The common rules (formatVersion, atomic writes, corrupt -> .bad, newer -> read-only,
// unknown top-level fields kept) are JsonStateFile's. A newer file's sessions are ignored and the file is never written.
public final class StutterStore {
	public static final long MAX_BYTES = 64 * 1024;
	public static final int MAX_SESSIONS = 5;

	private final JsonStateFile file;

	record StutterFile(List<StutterReport> sessions) {
	}

	public StutterStore(Path configDir) {
		this.file = new JsonStateFile(file(configDir), MAX_BYTES);
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve("stutter.json");
	}

	public Path path() {
		return file.file();
	}

	// Oldest first; empty when missing, corrupt (moved aside), unreadable or from a newer RigTune.
	public synchronized List<StutterReport> sessions() {
		JsonStateFile.Loaded<StutterFile> loaded = file.load(StutterFile.class);
		return loaded.state() == JsonStateFile.State.OK ? sessions(loaded.value()) : List.of();
	}

	public synchronized @Nullable StutterReport latest() {
		List<StutterReport> all = sessions();
		return all.isEmpty() ? null : all.getLast();
	}

	public synchronized JsonStateFile.Saved add(StutterReport report) {
		JsonStateFile.Loaded<StutterFile> loaded = file.load(StutterFile.class);
		if (!loaded.writable()) {
			return JsonStateFile.Saved.READ_ONLY;
		}
		List<StutterReport> all = new ArrayList<>(loaded.state() == JsonStateFile.State.OK ? sessions(loaded.value()) : List.of());
		all.add(trimmed(report));
		while (all.size() > MAX_SESSIONS) {
			all.removeFirst();
		}
		JsonStateFile.Saved saved = JsonStateFile.Saved.TOO_LARGE;
		while (!all.isEmpty()) {
			saved = file.save(new StutterFile(List.copyOf(all)), loaded.root());
			if (saved != JsonStateFile.Saved.TOO_LARGE) {
				return saved;
			}
			all.removeFirst();
		}
		return saved;
	}

	// Keeps the file (and its unknown fields) but drops every session.
	public synchronized JsonStateFile.Saved clear() {
		JsonStateFile.Loaded<StutterFile> loaded = file.load(StutterFile.class);
		if (loaded.state() == JsonStateFile.State.MISSING) {
			return JsonStateFile.Saved.OK;
		}
		return loaded.writable() ? file.save(new StutterFile(List.of()), loaded.root()) : JsonStateFile.Saved.READ_ONLY;
	}

	private static List<StutterReport> sessions(@Nullable StutterFile value) {
		return value == null || value.sessions() == null ? List.of() : value.sessions().stream().filter(Objects::nonNull).toList();
	}

	private static StutterReport trimmed(StutterReport r) {
		if (r.worst().size() <= StutterReport.MAX_WORST) {
			return r;
		}
		return new StutterReport(r.startedAt(), r.source(), r.mc(), r.collector(), r.heapMaxMb(), r.sessionSeconds(), r.gameplaySeconds(), r.frames(),
				r.avgFps(), r.onePercentLowFps(), r.histogramCounts(), r.histogramTimeMs(), r.spikes(), r.lostMs(), r.causes(), r.tags(),
				List.copyOf(r.worst().subList(0, StutterReport.MAX_WORST)), r.facts(), r.advice(), r.enoughData(), r.phaseTiming());
	}
}
