package io.github.chaotix345.rigtune.core.benchmark;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.AtomicFiles;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

// config/rigtune/benchmark-restore.json: the Distant Horizons / Iris values from before a benchmark changed them,
// written before the first change and deleted after the restore. If the game dies in between, the next start
// restores them once the mods are ready (Iris saves its shader toggle at once, so it would otherwise stay off).
// A null field has nothing to restore.
public record RestoreMarker(@Nullable Boolean dhRenderingEnabled, @Nullable Boolean irisShadersEnabled, String createdAt) {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public interface Target {
		boolean loaded();

		boolean ready();

		void set(boolean value) throws Exception;
	}

	public static Path defaultPath(Path configDir) {
		return configDir.resolve("rigtune").resolve("benchmark-restore.json");
	}

	public static Optional<RestoreMarker> load(Path file) {
		if (!Files.isRegularFile(file)) {
			return Optional.empty();
		}
		try {
			RestoreMarker marker = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RestoreMarker.class);
			if (marker != null) {
				return Optional.of(marker);
			}
		} catch (IOException | JsonParseException e) {
			RigTune.LOGGER.warn("Unreadable {}; dropping it", file, e);
		}
		delete(file);
		return Optional.empty();
	}

	public void save(Path file) throws IOException {
		AtomicFiles.writeString(file, GSON.toJson(this));
	}

	public static void delete(Path file) {
		try {
			Files.deleteIfExists(file);
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not delete {}", file, e);
		}
	}

	/**
	 * Restores what the marker holds. A mod that is no longer loaded drops its value; one that isn't ready yet keeps it
	 * for a later call. True when nothing is left (the marker is gone).
	 */
	public static boolean restorePending(Path file, Target dh, Target iris) {
		Optional<RestoreMarker> loaded = load(file);
		if (loaded.isEmpty()) {
			return true;
		}
		RestoreMarker marker = loaded.get();
		Boolean dhLeft = restore("Distant Horizons rendering", marker.dhRenderingEnabled(), dh);
		Boolean irisLeft = restore("Iris shaders", marker.irisShadersEnabled(), iris);
		if (dhLeft == null && irisLeft == null) {
			delete(file);
			return true;
		}
		RestoreMarker rest = new RestoreMarker(dhLeft, irisLeft, marker.createdAt());
		if (!rest.equals(marker)) {
			try {
				rest.save(file);
			} catch (IOException e) {
				RigTune.LOGGER.warn("Could not update {}", file, e);
			}
		}
		return false;
	}

	private static @Nullable Boolean restore(String what, @Nullable Boolean value, Target target) {
		if (value == null || !target.loaded()) {
			return null;
		}
		if (!target.ready()) {
			return value;
		}
		try {
			target.set(value);
			RigTune.LOGGER.info("Restored {} to {} after an interrupted benchmark", what, value);
			return null;
		} catch (Exception | LinkageError e) {
			RigTune.LOGGER.warn("Could not restore {} to {}", what, value, e);
			return value;
		}
	}
}
