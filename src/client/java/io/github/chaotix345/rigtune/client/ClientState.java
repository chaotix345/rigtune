package io.github.chaotix345.rigtune.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.model.Goal;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ClientState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static @Nullable ClientState shared;

	public volatile String goal = Goal.BALANCED.name();
	public volatile String lastShownApply;

	public static synchronized ClientState shared(Path configDir) {
		if (shared == null) {
			shared = load(configDir);
		}
		return shared;
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve("rigtune.json");
	}

	public static synchronized ClientState load(Path configDir) {
		Path file = file(configDir);
		if (Files.isRegularFile(file)) {
			try {
				ClientState state = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClientState.class);
				if (state != null) {
					return state;
				}
			} catch (IOException | JsonParseException e) {
				RigTune.LOGGER.warn("Could not read {}", file, e);
			}
		}
		return new ClientState();
	}

	public synchronized void save(Path configDir) {
		Path file = file(configDir);
		try {
			Files.createDirectories(file.getParent());
			Path temp = Files.createTempFile(file.getParent(), "rigtune", ".tmp");
			try {
				Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
				try {
					Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				Files.deleteIfExists(temp);
			}
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not write {}", file, e);
		}
	}

	public Goal goalOrDefault() {
		try {
			return goal == null ? Goal.BALANCED : Goal.valueOf(goal);
		} catch (IllegalArgumentException e) {
			return Goal.BALANCED;
		}
	}
}
