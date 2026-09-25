package io.github.chaotix345.rigtune.core.preview;

import io.github.chaotix345.rigtune.core.modrinth.ModrinthClient;
import io.github.chaotix345.rigtune.core.modrinth.ModrinthVersion;

import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

// The inputs RealController.download gives the DownloadPlanner, for the same planner run dry. lookups: whether Modrinth
// may be asked (settings); when it may not, an addition's files are left unresolved.
public record DownloadInputs(ModrinthClient client, boolean lookups, String loader, String gameVersion,
		Map<String, ModrinthVersion> installedVersions, Map<String, ModrinthVersion> updateVersions, Set<String> installedProjects,
		Set<String> loadedIds, Map<String, String> stagedJars, BiPredicate<String, String> conflicts) {
}
