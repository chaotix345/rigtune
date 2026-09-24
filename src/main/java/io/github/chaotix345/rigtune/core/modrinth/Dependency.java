package io.github.chaotix345.rigtune.core.modrinth;

import com.google.gson.JsonObject;

public record Dependency(String projectId, String versionId, String dependencyType) {
	public static Dependency fromJson(JsonObject obj) {
		return new Dependency(
				Json.string(obj, "project_id"),
				Json.string(obj, "version_id"),
				Json.string(obj, "dependency_type"));
	}

	public boolean required() {
		return "required".equals(dependencyType);
	}
}
