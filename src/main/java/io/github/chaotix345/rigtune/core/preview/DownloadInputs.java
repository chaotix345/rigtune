package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;
import io.github.chaotix345.rigtune.core.modrinth.StagedProjects;

import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

// The inputs RealController.download gives the DownloadPlanner, for the same planner run dry. lookups: whether Modrinth
// may be asked (settings); when it may not, an addition's files are left unresolved. staged: what earlier Applies
// staged (docs/v0.4/SPEC.md 2d). lookedUp: whether the installed mods were looked up on Modrinth (docs/v0.4/SPEC.md 2o,
// M4: while lookups are on and it's false, every download is refused, as Apply refuses it).
public record DownloadInputs(ModrinthClient client, boolean lookups, String loader, String gameVersion,
		Map<String, ModrinthVersion> installedVersions, Map<String, ModrinthVersion> updateVersions, Set<String> installedProjects,
		Set<String> loadedIds, Map<String, String> stagedJars, BiPredicate<String, String> conflicts, StagedProjects staged, boolean lookedUp) {
	public DownloadInputs {
		staged = staged == null ? StagedProjects.NONE : staged;
	}

	public DownloadInputs(ModrinthClient client, boolean lookups, String loader, String gameVersion,
			Map<String, ModrinthVersion> installedVersions, Map<String, ModrinthVersion> updateVersions, Set<String> installedProjects,
			Set<String> loadedIds, Map<String, String> stagedJars, BiPredicate<String, String> conflicts, StagedProjects staged) {
		this(client, lookups, loader, gameVersion, installedVersions, updateVersions, installedProjects, loadedIds, stagedJars, conflicts, staged, true);
	}

	public DownloadInputs(ModrinthClient client, boolean lookups, String loader, String gameVersion,
			Map<String, ModrinthVersion> installedVersions, Map<String, ModrinthVersion> updateVersions, Set<String> installedProjects,
			Set<String> loadedIds, Map<String, String> stagedJars, BiPredicate<String, String> conflicts) {
		this(client, lookups, loader, gameVersion, installedVersions, updateVersions, installedProjects, loadedIds, stagedJars, conflicts,
				StagedProjects.NONE);
	}
}
