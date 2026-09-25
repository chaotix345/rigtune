package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.model.ModFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

// Every Modrinth request goes through here, so switching Modrinth (or the network) off in RigTune's settings stops all
// of them: lookups, update checks, dependency resolution and downloads. The switch is read on every call.
public final class GatedModrinthClient implements ModrinthClient {
	private final ModrinthClient delegate;
	private final BooleanSupplier allowed;

	public GatedModrinthClient(ModrinthClient delegate, BooleanSupplier allowed) {
		this.delegate = delegate;
		this.allowed = allowed;
	}

	private void check() throws ModrinthException {
		if (!allowed.getAsBoolean()) {
			throw new ModrinthException(0, "Modrinth is off in RigTune's settings");
		}
	}

	@Override
	public Map<String, ModrinthVersion> versionsByHashes(Collection<String> sha1s) throws IOException {
		check();
		return delegate.versionsByHashes(sha1s);
	}

	@Override
	public Map<String, ModrinthVersion> latestVersionsByHashes(Collection<String> sha1s, String loader, String gameVersion) throws IOException {
		check();
		return delegate.latestVersionsByHashes(sha1s, loader, gameVersion);
	}

	@Override
	public List<ModrinthProject> projects(Collection<String> idsOrSlugs) throws IOException {
		check();
		return delegate.projects(idsOrSlugs);
	}

	@Override
	public Optional<ModrinthVersion> latestVersion(String idOrSlug, String loader, String gameVersion) throws IOException {
		check();
		return delegate.latestVersion(idOrSlug, loader, gameVersion);
	}

	@Override
	public void download(ModFile file, Path target) throws IOException {
		check();
		delegate.download(file, target);
	}
}
