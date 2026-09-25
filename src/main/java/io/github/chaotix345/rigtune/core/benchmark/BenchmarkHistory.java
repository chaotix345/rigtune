package io.github.chaotix345.rigtune.core.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// config/rigtune/benchmarks.json: the last 50 finished runs, oldest first. An unreadable file is kept aside as
// benchmarks.json.bad; a file from a newer RigTune (higher schemaVersion) is left alone and only replaced when the next
// run is saved.
public final class BenchmarkHistory {
	public static final int SCHEMA_VERSION = 1;
	public static final int MAX_RUNS = 50;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private record FileFormat(int schemaVersion, List<BenchmarkRecord> runs) {
	}

	private final List<BenchmarkRecord> runs;

	private BenchmarkHistory(List<BenchmarkRecord> runs) {
		this.runs = List.copyOf(runs);
	}

	public static Path defaultPath(Path configDir) {
		return configDir.resolve("rigtune").resolve("benchmarks.json");
	}

	public static BenchmarkHistory empty() {
		return new BenchmarkHistory(List.of());
	}

	public static BenchmarkHistory load(Path file) {
		if (!Files.isRegularFile(file)) {
			return empty();
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonElement schema = json.get("schemaVersion");
			int version = schema == null ? -1 : schema.getAsInt();
			if (version > SCHEMA_VERSION) {
				RigTune.LOGGER.info("{} is from a newer RigTune (schema {}); starting a new history", file, version);
				return empty();
			}
			if (version != SCHEMA_VERSION) {
				throw new JsonParseException("schemaVersion " + version);
			}
			FileFormat format = GSON.fromJson(json, FileFormat.class);
			List<BenchmarkRecord> runs = format.runs() == null ? List.of() : format.runs().stream().filter(Objects::nonNull).toList();
			return new BenchmarkHistory(runs.subList(Math.max(0, runs.size() - MAX_RUNS), runs.size()));
		} catch (IOException | RuntimeException e) {
			Path bad = file.resolveSibling(file.getFileName() + ".bad");
			RigTune.LOGGER.warn("Unreadable {}; keeping it as {} and starting a new history", file, bad.getFileName(), e);
			try {
				Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException moveError) {
				RigTune.LOGGER.warn("Could not move {} aside", file, moveError);
			}
			return empty();
		}
	}

	public void save(Path file) throws IOException {
		AtomicFiles.writeString(file, GSON.toJson(new FileFormat(SCHEMA_VERSION, runs)));
	}

	public List<BenchmarkRecord> runs() {
		return runs;
	}

	public BenchmarkHistory with(BenchmarkRecord run) {
		List<BenchmarkRecord> out = new ArrayList<>(runs);
		out.add(run);
		return new BenchmarkHistory(out.subList(Math.max(0, out.size() - MAX_RUNS), out.size()));
	}

	public Optional<BenchmarkRecord> latest() {
		return runs.isEmpty() ? Optional.empty() : Optional.of(runs.getLast());
	}

	public Optional<BenchmarkRecord> before(String pairId) {
		return runs.stream().filter(r -> BenchmarkRecord.BEFORE.equals(r.phase()) && pairId.equals(r.pairId())).findFirst();
	}

	/** The newest "before" of this scene and MC version that has no "after" yet. */
	public Optional<BenchmarkRecord> openBefore(String scene, String mcVersion) {
		for (BenchmarkRecord r : runs.reversed()) {
			if (BenchmarkRecord.BEFORE.equals(r.phase()) && scene.equals(r.scene()) && mcVersion.equals(r.mcVersion())
					&& r.pairId() != null && !paired(r.pairId())) {
				return Optional.of(r);
			}
		}
		return Optional.empty();
	}

	private boolean paired(String pairId) {
		return runs.stream().anyMatch(r -> BenchmarkRecord.AFTER.equals(r.phase()) && pairId.equals(r.pairId()));
	}

	/** The newest `max` runs of this scene that have a result, oldest first. */
	public List<BenchmarkRecord> chart(String scene, int max) {
		List<BenchmarkRecord> matching = runs.stream().filter(r -> scene.equals(r.scene()) && r.result() != null).toList();
		return matching.subList(Math.max(0, matching.size() - max), matching.size());
	}
}
