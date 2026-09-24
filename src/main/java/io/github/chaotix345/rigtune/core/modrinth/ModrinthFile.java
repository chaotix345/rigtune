package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonObject;
import io.github.chaotix345.rigtune.core.model.ModFile;

public record ModrinthFile(String url, String filename, String sha1, String sha512, long size, boolean primary) {
	public static ModrinthFile fromJson(JsonObject obj) {
		JsonObject hashes = Json.object(obj, "hashes");
		return new ModrinthFile(
				Json.string(obj, "url"),
				Json.string(obj, "filename"),
				Json.string(hashes, "sha1"),
				Json.string(hashes, "sha512"),
				Json.number(obj, "size"),
				Json.bool(obj, "primary"));
	}

	public ModFile toModFile() {
		return new ModFile(url, filename, sha512, size);
	}
}
