package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.model.ModFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class FakeModrinthClient implements ModrinthClient {
	final Map<String, ModrinthVersion> current = new HashMap<>();
	final Map<String, ModrinthVersion> latest = new HashMap<>();
	final List<ModrinthProject> projects = new ArrayList<>();
	final Map<String, ModrinthVersion> latestByProject = new HashMap<>();
	final List<String> calls = new ArrayList<>();
	IOException failWith;

	static ModrinthVersion version(String id, String projectId, String number, Instant published, Dependency... deps) {
		return version(id, projectId, number, "release", published, deps);
	}

	static ModrinthVersion version(String id, String projectId, String number, String versionType, Instant published, Dependency... deps) {
		ModrinthFile file = new ModrinthFile("https://cdn/" + id + ".jar", id + ".jar", "sha1-" + id, "sha512-" + id, 10, true);
		return new ModrinthVersion(id, projectId, number, versionType, List.of("26.2"), List.of("fabric"), published,
				List.of(file), Arrays.asList(deps));
	}

	static Dependency required(String projectId) {
		return new Dependency(projectId, null, "required");
	}

	private void call(String name) throws IOException {
		calls.add(name);
		if (failWith != null) {
			throw failWith;
		}
	}

	@Override
	public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) throws IOException {
		call("versionsByHashes");
		return pick(current, sha1s);
	}

	@Override
	public Map<String, ModrinthVersion> latestVersionsByHashes(Collection<String> sha1s, String loader, String gameVersion) throws IOException {
		call("latestVersionsByHashes");
		return pick(latest, sha1s);
	}

	@Override
	public List<ModrinthProject> projects(Collection<String> idsOrSlugs) throws IOException {
		call("projects");
		return projects.stream().filter(p -> idsOrSlugs.contains(p.slug()) || idsOrSlugs.contains(p.id())).toList();
	}

	@Override
	public Optional<ModrinthVersion> latestVersion(String idOrSlug, String loader, String gameVersion) throws IOException {
		call("latestVersion:" + idOrSlug);
		return Optional.ofNullable(latestByProject.get(idOrSlug));
	}

	@Override
	public void download(ModFile file, Path target) throws IOException {
		call("download");
	}

	private static Map<String, ModrinthVersion> pick(Map<String, ModrinthVersion> source, Collection<String> keys) {
		Map<String, ModrinthVersion> out = new LinkedHashMap<>();
		for (String key : keys) {
			if (source.containsKey(key)) {
				out.put(key, source.get(key));
			}
		}
		return out;
	}
}
