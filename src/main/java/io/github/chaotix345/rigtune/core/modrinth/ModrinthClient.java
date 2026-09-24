package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.model.ModFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ModrinthClient {
	Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) throws IOException;

	Map<String, ModrinthVersion> latestVersionsByHashes(Collection<String> sha1s, String loader, String gameVersion) throws IOException;

	List<ModrinthProject> projects(Collection<String> idsOrSlugs) throws IOException;

	Optional<ModrinthVersion> latestVersion(String idOrSlug, String loader, String gameVersion) throws IOException;

	void download(ModFile file, Path target) throws IOException;
}
