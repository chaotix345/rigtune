package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Function;

// The DownloadPlanner Apply uses, run without downloading (the preview, docs/v0.3/SPEC.md item 13). Its fetcher checks
// the file name as Apply's does and returns a path that is never created. A jar's mod id is the updated mod's for an
// update's file (modIdsByFile), else a stand-in no loaded or staged mod has, since only the download would tell.
// docs/v0.5/SPEC.md 2H L5: with Checks, each fetched file's fabric.mod.json is read in memory (RangeReader), and the jar
// gets Apply's mod id and its VersionPins.Jar, so the planner runs Apply's fabric.mod.json checks; a file whose read
// fails stays as above, judged on nothing.
public final class DryRunPlanner {
	private DryRunPlanner() {
	}

	// pins: the loaded mods' ranges (the client's FabricPins, as Apply's planner gets them). nestedOrProvided: the ids that
	// are loaded, but not as a top-level jar (nested in a mod, or provided by one): a jar whose nesting a read can't know
	// counts as carrying them, so the preview never judges a range Apply's full read of the jar might not. read: one file's
	// fabric.mod.json (RangeReader.read); close: once the plan is done.
	public record Checks(VersionPins pins, Set<String> nestedOrProvided, Function<ModFile, RangeReader.Read> read, Runnable close) {
	}

	// checkedFiles: the file names whose fabric.mod.json was read, or found missing (Apply refuses those as well).
	public record Planned(DownloadPlanner.Result result, Set<String> checkedFiles) {
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
		return plan(resolver, modsDir, conflicts, updateVersions, modIdsByFile, recs, installedProjects, loadedIds, stagedJars, lookups, online, null)
				.result();
	}

	public static Planned plan(DependencyResolver resolver, Path modsDir, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions, Map<String, String> modIdsByFile, List<Recommendation> recs, Set<String> installedProjects,
			Set<String> loadedIds, Map<String, String> stagedJars, boolean lookups, boolean online, @Nullable Checks checks) {
		String never = PendingActions.PENDING_SUFFIX + ".preview-" + UUID.randomUUID();
		Map<Path, String> modIds = new HashMap<>();
		// What was read of each fetched file (absent: nothing could be).
		Map<Path, FabricModJson> read = new HashMap<>();
		Set<String> checked = new HashSet<>();
		AtomicInteger next = new AtomicInteger();
		// The same file fetched again (after an item that failed) is the same jar, so it keeps its id.
		DownloadPlanner.Fetcher fetcher = file -> {
			Path path = SafeFileNames.resolveJar(modsDir, file.filename(), never);
			if (!modIds.containsKey(path)) {
				modIds.put(path, modIdOf(file, path, checks, modIdsByFile, read, checked, "rigtune-preview-" + next.incrementAndGet() + never));
			}
			return path;
		};
		DownloadPlanner.DryJars jars = checks == null ? null : (path, modId, replaces) -> jar(read.get(path), modId, replaces, checks.nestedOrProvided());
		DownloadPlanner planner = new DownloadPlanner(resolver, modsDir, fetcher, conflicts, updateVersions, modIds::get, jars,
				checks == null ? VersionPins.NONE : checks.pins());
		return new Planned(planner.lookedUp(lookups, online).plan(recs, installedProjects, loadedIds, stagedJars), Set.copyOf(checked));
	}

	// Apply's mod id for the file: its fabric.mod.json's (null, "not a Fabric mod jar", where Apply reads none). An update's
	// file keeps the updated mod's id unless the jar says the same: Apply judges another id with the jar's nested mods,
	// which a read doesn't have. A file that couldn't be read gets the stand-in.
	private static @Nullable String modIdOf(ModFile file, Path path, @Nullable Checks checks, Map<String, String> modIdsByFile,
			Map<Path, FabricModJson> read, Set<String> checked, String standIn) {
		String updated = modIdsByFile.get(file.filename());
		RangeReader.Read answer = checks == null ? null : checks.read().apply(file);
		if (answer == null || !answer.ok() && !answer.missing()) {
			return updated != null ? updated : standIn;
		}
		FabricModJson json = answer.ok() ? FabricModJson.parse(answer.fabricModJson()) : null;
		if (json == null) {
			checked.add(file.filename());
			return null;
		}
		if (updated != null && !updated.equals(json.id())) {
			return updated;
		}
		read.put(path, json);
		checked.add(file.filename());
		return json.id();
	}

	private static VersionPins.Jar jar(@Nullable FabricModJson json, String modId, String replaces, Set<String> nestedOrProvided) {
		if (json == null) {
			return new VersionPins.Jar(modId, null, null, replaces, nestedOrProvided, Map.of(), Map.of());
		}
		Set<String> provides = json.provides();
		if (json.nestsJars()) {
			provides = new HashSet<>(provides);
			provides.addAll(nestedOrProvided);
		}
		return new VersionPins.Jar(modId, json.name(), json.version(), replaces, provides, json.depends(), json.breaks());
	}
}
