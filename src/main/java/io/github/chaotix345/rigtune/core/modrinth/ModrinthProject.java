package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonObject;

import java.util.List;

public record ModrinthProject(
		String id,
		String slug,
		String title,
		String status,
		List<String> gameVersions,
		List<String> loaders,
		String clientSide) {
	public static ModrinthProject fromJson(JsonObject obj) {
		return new ModrinthProject(
				Json.string(obj, "id"),
				Json.string(obj, "slug"),
				Json.string(obj, "title"),
				Json.string(obj, "status"),
				Json.strings(obj, "game_versions"),
				Json.strings(obj, "loaders"),
				Json.string(obj, "client_side"));
	}

	public boolean supports(String loader, String gameVersion) {
		return loaders.contains(loader) && gameVersions.contains(gameVersion);
	}
}
