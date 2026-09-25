package io.github.chaotix345.rigtune.client;

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

// The v0.2 settings (docs/v0.2/SPEC.md item 8), in their own file config/rigtune/settings.json. They're kept out of
// rigtune.json because 0.1.x rewrites that file with only the fields it knows, which after a downgrade and re-upgrade
// would silently turn the network back on. Missing fields keep these defaults.
public final class ClientSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static @Nullable ClientSettings shared;

	// Master switch: off means no network request of any kind.
	public volatile boolean networkEnabled = true;
	// Fetch the rules file from GitHub.
	public volatile boolean remoteRules = true;
	// Modrinth: installed-jar lookups, availability and update checks, and downloads.
	public volatile boolean modrinth = true;
	// The title-screen "RigTune: N suggestions" toast.
	public volatile boolean startupToast = true;
	// BenchmarkRequest.Scene name: CURRENT or BENCHMARK_WORLD.
	public volatile String benchmarkScene = "CURRENT";
	// Whether the one-time privacy toast was shown.
	public volatile boolean privacyNoticeShown = false;

	public static synchronized ClientSettings shared(Path configDir) {
		if (shared == null) {
			shared = load(configDir);
		}
		return shared;
	}

	public static Path file(Path configDir) {
		return configDir.resolve("rigtune").resolve("settings.json");
	}

	public static synchronized ClientSettings load(Path configDir) {
		Path file = file(configDir);
		if (Files.isRegularFile(file)) {
			try {
				ClientSettings settings = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ClientSettings.class);
				if (settings != null) {
					return settings;
				}
			} catch (IOException | JsonParseException e) {
				RigTune.LOGGER.warn("Could not read {}", file, e);
			}
		}
		return new ClientSettings();
	}

	public synchronized void save(Path configDir) {
		try {
			AtomicFiles.writeString(file(configDir), GSON.toJson(this));
		} catch (IOException e) {
			RigTune.LOGGER.warn("Could not write {}", file(configDir), e);
		}
	}

	public boolean remoteRulesAllowed() {
		return networkEnabled && remoteRules;
	}

	public boolean modrinthAllowed() {
		return networkEnabled && modrinth;
	}
}
