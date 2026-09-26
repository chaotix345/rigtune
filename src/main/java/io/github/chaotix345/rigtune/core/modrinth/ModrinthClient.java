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

	// The versions with these ids, by id; ids Modrinth doesn't know are left out (docs/v0.4/SPEC.md 2d, amendment A-M1:
	// the versions earlier Applies staged). A client that can't look them up throws, and the caller does without.
	default Map<String, ModrinthVersion> versions(Collection<String> ids) throws IOException {
		throw new IOException("This Modrinth client can't look versions up by id");
	}

	void download(ModFile file, Path target) throws IOException;
}
