package io.github.chaotix345.rigtune.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.model.Goal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientState {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String goal = Goal.BALANCED.name();
	public String lastShownApply;

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
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
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
