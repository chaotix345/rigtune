package io.github.chaotix345.rigtune.core.footprint;

import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.Loaded;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.Saved;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// startup-times.json (docs/v0.4/SPEC.md 13 and C1): launch-to-title of the last 30 launches, on the common state-file
// rules (JsonStateFile: formatVersion 1, atomic writes, corrupt -> .bad, newer -> read-only). One writer: the client's
// StartupTimes, once per launch. Advice only: nothing else reads it.
public final class StartupTimesStore {
	public static final String FILE_NAME = "startup-times.json";
	public static final int MAX_RUNS = 30;
	public static final int MEDIAN_OF = 10;
	public static final long MAX_BYTES = 16 * 1024;

	// at: ISO-8601 UTC; ms: JVM start to the first title screen; mods: the loaded non-builtin mods; modSetHash: ModSetHash.
	public record Run(String at, long ms, @Nullable String mcVersion, @Nullable String rigtuneVersion, int mods,
			@Nullable String modSetHash) {
	}

	record Doc(@Nullable List<Run> runs) {
	}

	// lastMs/medianMs: the last launch and the median of the last MEDIAN_OF (null without runs); runs: how many are
	// stored; modSetChanged: the last two launches have different known hashes.
	public record Summary(@Nullable Long lastMs, @Nullable Long medianMs, int runs, boolean modSetChanged) {
	}

	private final JsonStateFile file;

	public StartupTimesStore(Path configDir) {
		this.file = new JsonStateFile(file(configDir), MAX_BYTES);
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve(FILE_NAME);
	}

	public synchronized List<Run> runs() {
		return valid(file.load(Doc.class).value());
	}

	// Appends a run and keeps the newest MAX_RUNS (fewer if they don't fit the byte cap). READ_ONLY for a newer or
	// unreadable file, which is left as it is.
	public synchronized Saved record(Run run) {
		Loaded<Doc> loaded = file.load(Doc.class);
		if (!loaded.writable()) {
			return Saved.READ_ONLY;
		}
		List<Run> runs = new ArrayList<>(valid(loaded.value()));
		runs.add(run);
		while (runs.size() > MAX_RUNS) {
			runs.removeFirst();
		}
		while (true) {
			Saved saved = file.save(new Doc(runs), loaded.root());
			if (saved != Saved.TOO_LARGE || runs.size() <= 1) {
				return saved;
			}
			runs.removeFirst();
		}
	}

	public static Summary summarize(List<Run> runs) {
		if (runs.isEmpty()) {
			return new Summary(null, null, 0, false);
		}
		List<Long> recent = new ArrayList<>(runs.subList(Math.max(0, runs.size() - MEDIAN_OF), runs.size()).stream().map(Run::ms).toList());
		recent.sort(null);
		int n = recent.size();
		long median = n % 2 == 1 ? recent.get(n / 2) : Math.round((recent.get(n / 2 - 1) + recent.get(n / 2)) / 2.0);
		Run last = runs.getLast();
		boolean changed = false;
		if (runs.size() >= 2) {
			String previous = runs.get(runs.size() - 2).modSetHash();
			changed = previous != null && last.modSetHash() != null && !previous.equals(last.modSetHash());
		}
		return new Summary(last.ms(), median, runs.size(), changed);
	}

	// The file is the player's to edit: runs without a time or a positive duration are left out.
	private static List<Run> valid(@Nullable Doc doc) {
		if (doc == null || doc.runs() == null) {
			return List.of();
		}
		return doc.runs().stream().filter(Objects::nonNull).filter(r -> r.at() != null && !r.at().isBlank() && r.ms() > 0).toList();
	}
}
