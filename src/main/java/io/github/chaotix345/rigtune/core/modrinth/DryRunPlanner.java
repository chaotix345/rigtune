package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.model.Recommendation;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

// The DownloadPlanner Apply uses, run without downloading (the preview, docs/v0.3/SPEC.md item 13). Its fetcher checks
// the file name as Apply's does and returns a path that is never created. A jar's mod id is the updated mod's for an
// update's file (modIdsByFile), else a stand-in no loaded or staged mod has, since only the download would tell.
public final class DryRunPlanner {
	private DryRunPlanner() {
	}

	public static DownloadPlanner.Result plan(DependencyResolver resolver, Path modsDir, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions, Map<String, String> modIdsByFile, List<Recommendation> recs, Set<String> installedProjects,
			Set<String> loadedIds, Map<String, String> stagedJars) {
		return plan(resolver, modsDir, conflicts, updateVersions, modIdsByFile, recs, installedProjects, loadedIds, stagedJars, false, true);
	}

	// lookups, online: DownloadPlanner.lookedUp (docs/v0.4/SPEC.md 2o, M4).
	public static DownloadPlanner.Result plan(DependencyResolver resolver, Path modsDir, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions, Map<String, String> modIdsByFile, List<Recommendation> recs, Set<String> installedProjects,
			Set<String> loadedIds, Map<String, String> stagedJars, boolean lookups, boolean online) {
		String never = PendingActions.PENDING_SUFFIX + ".preview-" + UUID.randomUUID();
		Map<Path, String> modIds = new HashMap<>();
		AtomicInteger next = new AtomicInteger();
		// The same file fetched again (after an item that failed) is the same jar, so it keeps its id.
		DownloadPlanner.Fetcher fetcher = file -> {
			Path path = SafeFileNames.resolveJar(modsDir, file.filename(), never);
			modIds.computeIfAbsent(path, p -> modIdsByFile.getOrDefault(file.filename(), "rigtune-preview-" + next.incrementAndGet() + never));
			return path;
		};
		return new DownloadPlanner(resolver, modsDir, fetcher, conflicts, updateVersions, modIds::get).lookedUp(lookups, online)
				.plan(recs, installedProjects, loadedIds, stagedJars);
	}
}
