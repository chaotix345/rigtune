package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthProject;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// The preview's Modrinth: lookups only while they're allowed, never a download. Remembers each latestVersion answer, so
// an addition's own file can be told from its dependencies'.
final class LookupOnlyClient implements ModrinthClient {
	private final ModrinthClient delegate;
	private final boolean lookups;
	private final Map<String, ModrinthVersion> answers = new ConcurrentHashMap<>();

	LookupOnlyClient(ModrinthClient delegate, boolean lookups) {
		this.delegate = delegate;
		this.lookups = lookups;
	}

	ModrinthVersion answer(String idOrSlug) {
		return answers.get(idOrSlug);
	}

	private void allowed() throws IOException {
		if (!lookups) {
			throw new IOException("Modrinth lookups are off");
		}
	}

	@Override
	public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) throws IOException {
		allowed();
		return delegate.versionsByHashes(sha1s);
	}

	@Override
	public Map<String, ModrinthVersion> latestVersionsByHashes(Collection<String> sha1s, String loader, String gameVersion) throws IOException {
		allowed();
		return delegate.latestVersionsByHashes(sha1s, loader, gameVersion);
	}

	@Override
	public List<ModrinthProject> projects(Collection<String> idsOrSlugs) throws IOException {
		allowed();
		return delegate.projects(idsOrSlugs);
	}

	@Override
	public Optional<ModrinthVersion> latestVersion(String idOrSlug, String loader, String gameVersion) throws IOException {
		allowed();
		Optional<ModrinthVersion> found = delegate.latestVersion(idOrSlug, loader, gameVersion);
		found.ifPresent(version -> answers.put(idOrSlug, version));
		return found;
	}

	@Override
	public Map<String, ModrinthVersion> versions(Collection<String> ids) throws IOException {
		allowed();
		return delegate.versions(ids);
	}

	@Override
	public void download(ModFile file, Path target) throws IOException {
		throw new IOException("The preview downloads nothing");
	}
}
