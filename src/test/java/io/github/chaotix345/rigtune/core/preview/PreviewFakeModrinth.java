package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.apply.TestJars;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.modrinth.Dependency;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthFile;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthProject;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// A Modrinth whose latest versions are set by the test and whose download() writes a real mod jar (with the mod id
// given for that file name), recording every call.
public final class PreviewFakeModrinth implements ModrinthClient {
	public static final Instant T = Instant.parse("2026-09-01T00:00:00Z");

	public final Map<String, ModrinthVersion> latest = new HashMap<>();
	public final Map<String, String> modIdByFile = new HashMap<>();
	public final List<String> calls = Collections.synchronizedList(new ArrayList<>());

	public static ModrinthVersion version(String id, String projectId, String fileName, Dependency... deps) {
		ModrinthFile file = new ModrinthFile("https://cdn/" + fileName, fileName, "sha1-" + id, "sha512-" + id, 10, true);
		return new ModrinthVersion(id, projectId, "1.0", "release", List.of("26.2"), List.of("fabric"), T, List.of(file), Arrays.asList(deps));
	}

	public static Dependency required(String projectId) {
		return new Dependency(projectId, null, "required");
	}

	// Answers for both the slug and the project id; the file holds a mod with this id.
	public ModrinthVersion put(String slug, ModrinthVersion version, String modId) {
		latest.put(slug, version);
		latest.put(version.projectId(), version);
		modIdByFile.put(version.files().getFirst().filename(), modId);
		return version;
	}

	public boolean downloaded() {
		return calls.contains("download");
	}

	@Override
	public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) {
		calls.add("versionsByHashes");
		return Map.of();
	}

	@Override
	public Map<String, ModrinthVersion> latestVersionsByHashes(Collection<String> sha1s, String loader, String gameVersion) {
		calls.add("latestVersionsByHashes");
		return Map.of();
	}

	@Override
	public List<ModrinthProject> projects(Collection<String> idsOrSlugs) {
		calls.add("projects");
		return List.of();
	}

	@Override
	public Optional<ModrinthVersion> latestVersion(String idOrSlug, String loader, String gameVersion) {
		calls.add("latestVersion:" + idOrSlug);
		return Optional.ofNullable(latest.get(idOrSlug));
	}

	@Override
	public void download(ModFile file, Path target) throws IOException {
		calls.add("download");
		String modId = modIdByFile.get(file.filename());
		if (modId == null) {
			throw new IOException("no such file " + file.filename());
		}
		TestJars.modJar(target, modId);
	}
}
