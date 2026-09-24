package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.model.ModFile;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

public record ModrinthVersion(
		String id,
		String projectId,
		String versionNumber,
		String versionType,
		List<String> gameVersions,
		List<String> loaders,
		Instant datePublished,
		List<ModrinthFile> files,
		List<Dependency> dependencies) {
	public static ModrinthVersion fromJson(JsonObject obj) {
		return new ModrinthVersion(
				Json.string(obj, "id"),
				Json.string(obj, "project_id"),
				Json.string(obj, "version_number"),
				Json.string(obj, "version_type"),
				Json.strings(obj, "game_versions"),
				Json.strings(obj, "loaders"),
				parseInstant(Json.string(obj, "date_published")),
				Json.objects(obj, "files", ModrinthFile::fromJson),
				Json.objects(obj, "dependencies", Dependency::fromJson));
	}

	private static Instant parseInstant(String text) {
		if (text == null) {
			return Instant.EPOCH;
		}
		try {
			return Instant.parse(text);
		} catch (DateTimeParseException e) {
			return Instant.EPOCH;
		}
	}

	public ModFile primaryFile() {
		if (files.isEmpty()) {
			return null;
		}
		return files.stream().filter(ModrinthFile::primary).findFirst().orElse(files.getFirst()).toModFile();
	}

	public boolean isRelease() {
		return "release".equals(versionType);
	}
}
