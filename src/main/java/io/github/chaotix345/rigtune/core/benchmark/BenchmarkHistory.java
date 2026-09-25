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
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

// config/rigtune/benchmarks.json: the last 50 finished runs, oldest first. A corrupt file is kept aside as
// benchmarks.json.bad; a file from a newer RigTune (higher schemaVersion) is left alone and only replaced when the next
// run is saved. A file that couldn't be read (a lock held by a virus scanner, say) or moved aside is "unreadable": it
// must not be overwritten, so the caller loads again later.
public final class BenchmarkHistory {
	public static final int SCHEMA_VERSION = 1;
	public static final int MAX_RUNS = 50;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private record FileFormat(int schemaVersion, List<BenchmarkRecord> runs) {
	}

	private final List<BenchmarkRecord> runs;
	private final boolean unreadable;

	private BenchmarkHistory(List<BenchmarkRecord> runs) {
		this(runs, false);
	}

	private BenchmarkHistory(List<BenchmarkRecord> runs, boolean unreadable) {
		this.runs = List.copyOf(runs);
		this.unreadable = unreadable;
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
		String text;
		try {
			text = Files.readString(file, StandardCharsets.UTF_8);
		} catch (CharacterCodingException e) {
			return movedAside(file, e);
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not read {}; will try again", file, e);
			return new BenchmarkHistory(List.of(), true);
		}
		try {
			JsonObject json = JsonParser.parseString(text).getAsJsonObject();
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
		} catch (RuntimeException e) {
			return movedAside(file, e);
		}
	}

	private static BenchmarkHistory movedAside(Path file, Exception why) {
		Path bad = file.resolveSibling(file.getFileName() + ".bad");
		RigTune.LOGGER.warn("Corrupt {}; keeping it as {} and starting a new history", file, bad.getFileName(), why);
		try {
			Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
			return empty();
		} catch (IOException moveError) {
			RigTune.LOGGER.warn("Could not move {} aside", file, moveError);
			return new BenchmarkHistory(List.of(), true);
		}
	}

	/** The file exists but couldn't be read or moved aside, so it must not be overwritten yet. */
	public boolean unreadable() {
		return unreadable;
	}

	public void save(Path file) throws IOException {
		if (unreadable) {
			throw new IOException(file + " couldn't be read, so it is not overwritten");
		}
		AtomicFiles.writeString(file, GSON.toJson(new FileFormat(SCHEMA_VERSION, runs)));
	}

	public List<BenchmarkRecord> runs() {
		return runs;
	}

	public BenchmarkHistory with(BenchmarkRecord run) {
		List<BenchmarkRecord> out = new ArrayList<>(runs);
		out.add(run);
		return new BenchmarkHistory(out.subList(Math.max(0, out.size() - MAX_RUNS), out.size()), unreadable);
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

	/** The newest `max` runs of this scene and MC version (the benchmark world differs per version) with a result, oldest first. */
	public List<BenchmarkRecord> chart(String scene, String mcVersion, int max) {
		List<BenchmarkRecord> matching = runs.stream()
				.filter(r -> scene.equals(r.scene()) && mcVersion.equals(r.mcVersion()) && r.result() != null).toList();
		return matching.subList(Math.max(0, matching.size() - max), matching.size());
	}
}
