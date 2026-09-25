package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.UpdateInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

// Turns a batch of Add/Update recommendations into staged ops: one all-or-nothing group per recommendation.
// Each recommendation works on its own copies of the installed projects and mod ids, committed only when it succeeds,
// so a failed one can't make a later one skip a dependency it never delivered. A recommendation that needs a
// dependency an earlier one in the batch staged joins that one's group, so the dependency can't be applied or rolled
// back without it. Updates are planned before additions and join the batch: an addition is judged against the installed
// mods with those updates applied, and one that relies on an update joins its group (plan review A-H1).
public final class DownloadPlanner {
	public interface Fetcher {
		// Downloads the file (hash-checked) to <mods>/<name>.jar.rigtune-pending and returns that path.
		Path fetch(ModFile file) throws IOException;
	}

	// opIds: each staged recommendation id -> its ops' ids (its own ops, or the ops of the group it joined when it brought
	// none), so the client can tell which recommendations are still staged (plan review A-M1).
	public record Result(List<Op> ops, List<String> ids, List<String> errors, Map<String, List<String>> opIds) {
		public Result(List<Op> ops, List<String> ids, List<String> errors) {
			this(ops, ids, errors, Map.of());
		}
	}

	private final DependencyResolver resolver;
	private final Path modsDir;
	private final Fetcher fetcher;
	private final BiPredicate<String, String> conflicts;
	private final Map<String, ModrinthVersion> updateVersions;
	private final Function<Path, String> modIdOf;

	public DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher) {
		this(resolver, modsDir, fetcher, (a, b) -> false);
	}

	// conflicts: whether the mods of two AddMod slugs can't be installed together (the rules' ModConflicts).
	public DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, BiPredicate<String, String> conflicts) {
		this(resolver, modsDir, fetcher, conflicts, Map.of());
	}

	// updateVersions: the updates' Modrinth versions by version id, dependencies included
	// (OnlineDataFetcher.Result.updateVersions()); an update missing from it counts with no dependencies.
	public DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions) {
		this(resolver, modsDir, fetcher, conflicts, updateVersions, ModJars::modIdOf);
	}

	DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions, Function<Path, String> modIdOf) {
		this.resolver = resolver;
		this.modsDir = modsDir;
		this.fetcher = fetcher;
		this.conflicts = conflicts;
		this.updateVersions = Map.copyOf(updateVersions);
		this.modIdOf = modIdOf;
	}

	// installedProjects / loadedIds: the Modrinth projects and mod ids of the loaded mods. stagedJars: mod id -> the
	// pending jar of an enable already in pending.json (a newer download replaces it when merged).
	public Result plan(List<Recommendation> recs, Set<String> installedProjects, Set<String> loadedIds, Map<String, String> stagedJars) {
		Batch batch = new Batch(installedProjects, loadedIds, stagedJars);
		List<String> ids = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		Map<String, List<String>> opIds = new LinkedHashMap<>();
		// Every update before any addition, so the outcome doesn't depend on the order they were ticked in (A-H1).
		List<Recommendation> ordered = new ArrayList<>();
		recs.stream().filter(r -> r.action() instanceof Action.UpdateMod).forEach(ordered::add);
		recs.stream().filter(r -> !(r.action() instanceof Action.UpdateMod)).forEach(ordered::add);
		for (Recommendation rec : ordered) {
			Attempt attempt = new Attempt(batch);
			try {
				switch (rec.action()) {
					case Action.AddMod add -> addMod(add, attempt);
					case Action.UpdateMod update -> updateMod(update, attempt);
					default -> {
					}
				}
			} catch (IOException | RuntimeException e) {
				RigTune.LOGGER.warn("Could not prepare {}", rec.id(), e);
				errors.add(rec.title() + ": " + e.getMessage());
				continue;
			}
			String group = batch.commit(attempt);
			if (rec.action() instanceof Action.AddMod add) {
				batch.added.add(add);
			}
			ids.add(rec.id());
			List<Op> own = attempt.ops.isEmpty() && !attempt.joins.isEmpty()
					? batch.ops.stream().filter(op -> group.equals(op.group())).toList() : attempt.ops;
			opIds.put(rec.id(), own.stream().map(Op::id).toList());
		}
		return new Result(List.copyOf(batch.ops), ids, errors, Map.copyOf(opIds));
	}

	private void addMod(Action.AddMod add, Attempt attempt) throws IOException {
		// The batch never stages both sides of a conflict: the later one fails (review 4, rules-accuracy-2).
		for (Action.AddMod earlier : attempt.batch.added) {
			if (conflicts.test(earlier.slug(), add.slug())) {
				throw new IOException("it conflicts with " + earlier.title() + ", which is being installed too");
			}
		}
		String ref = add.projectId() != null ? add.projectId() : add.slug();
		DependencyResolver.Resolution resolution = resolver.resolve(ref, attempt.projects, attempt.batch.versions, attempt.batch.groupOfUpdate.keySet());
		// Allowed only because an update replaces the installed version: it goes in with that update or not at all.
		resolution.updatesNeeded().forEach(project -> attempt.joins.add(attempt.batch.groupOfUpdate.get(project)));
		// Projects staged earlier in this batch aren't in attempt.projects, so they come back from the resolver and are joined.
		for (ModrinthVersion version : resolution.versions()) {
			String stagedBy = attempt.batch.groupOfProject.get(version.projectId());
			if (stagedBy != null) {
				attempt.joins.add(stagedBy);
				continue;
			}
			ModFile file = version.primaryFile();
			if (file == null) {
				throw new IOException("No file for " + version.versionNumber());
			}
			Path target = SafeFileNames.resolveJar(modsDir, file.filename());
			if (Files.exists(target)) {
				attempt.projects.add(version.projectId());
				continue;
			}
			Path pending = fetcher.fetch(file);
			String jarModId = modIdOf.apply(pending);
			// Without a mod id nothing can check it isn't a second copy of an installed mod (review 3, apply-safety-1).
			if (jarModId == null) {
				attempt.batch.dropDuplicate(pending);
				throw new IOException(file.filename() + " is not a Fabric mod jar (no readable fabric.mod.json id)");
			}
			String sameMod = attempt.batch.groupOfMod.get(jarModId);
			if (sameMod != null) {
				attempt.joins.add(sameMod);
				attempt.batch.dropDuplicate(pending);
				continue;
			}
			// A second jar with an already-loaded mod id would stop Fabric from starting, so drop it.
			if (!attempt.modIds.add(jarModId)) {
				RigTune.LOGGER.info("Skipping {}: mod {} is already present", file.filename(), jarModId);
				attempt.batch.dropDuplicate(pending);
				continue;
			}
			attempt.batch.noteReplaced(jarModId, pending);
			attempt.newProjects.add(version.projectId());
			attempt.versions.add(version);
			attempt.ops.add(Op.enableFile(pending, target).withModId(jarModId));
		}
	}

	private void updateMod(Action.UpdateMod update, Attempt attempt) throws IOException {
		ModFile file = update.update().file();
		if (file == null) {
			throw new IOException("No file for " + update.update().newVersionNumber());
		}
		if (!SafeFileNames.isDirectChild(modsDir, update.currentFile())) {
			throw new IOException("it isn't in this instance's mods folder; update it in your launcher");
		}
		Path target = SafeFileNames.resolveJar(modsDir, file.filename());
		// As for an added mod: the enable never overwrites, so a target taken by another file would fail at every exit.
		if (Files.exists(target) && !(Files.exists(update.currentFile()) && Files.isSameFile(target, update.currentFile()))) {
			throw new IOException(target.getFileName() + " is already in the mods folder");
		}
		// The update's own version is judged like an addition's, against the installed mods and the batch (A-H1).
		UpdateInfo info = update.update();
		ModrinthVersion next = updateVersions.get(info.newVersionId());
		if (next == null) {
			next = DependencyResolver.known(info.newVersionId(), info.projectId());
		}
		resolver.checkUpdate(next, attempt.projects, attempt.batch.versions);
		Path pending = fetcher.fetch(file);
		String jarModId = modIdOf.apply(pending);
		// As for an added mod (review 4, apply-safety-1): the installed jar is only replaced by a jar with a readable id.
		if (jarModId == null) {
			attempt.batch.dropDuplicate(pending);
			throw new IOException(file.filename() + " is not a Fabric mod jar (no readable fabric.mod.json id)");
		}
		attempt.batch.noteReplaced(jarModId, pending);
		attempt.ops.add(Op.disableFile(update.currentFile()));
		attempt.ops.add(Op.enableFile(pending, target).withModId(jarModId));
		attempt.versions.add(next);
		attempt.updatedProject = info.projectId();
	}

	// What the batch has committed so far. projects and modIds are what is installed (loaded, or already in mods/);
	// what the batch staged is in groupOfProject and groupOfMod, with the group it went into, and in versions and added.
	// groupOfUpdate: installed project -> the group of its staged update.
	private static final class Batch {
		final Set<String> projects;
		final Set<String> modIds;
		final Map<String, String> stagedJars;
		final Map<String, String> groupOfProject = new HashMap<>();
		final Map<String, String> groupOfMod = new HashMap<>();
		final Map<String, String> groupOfUpdate = new LinkedHashMap<>();
		final List<Op> ops = new ArrayList<>();
		final List<ModrinthVersion> versions = new ArrayList<>();
		final List<Action.AddMod> added = new ArrayList<>();

		Batch(Set<String> installedProjects, Set<String> loadedIds, Map<String, String> stagedJars) {
			this.projects = new HashSet<>(installedProjects);
			this.modIds = new HashSet<>(loadedIds);
			this.stagedJars = stagedJars;
		}

		String commit(Attempt attempt) {
			String group = attempt.joins.isEmpty() ? PendingActions.newId() : attempt.joins.iterator().next();
			for (int i = 0; i < ops.size(); i++) {
				if (attempt.joins.contains(ops.get(i).group())) {
					ops.set(i, ops.get(i).inGroup(group));
				}
			}
			groupOfProject.replaceAll((project, g) -> attempt.joins.contains(g) ? group : g);
			groupOfMod.replaceAll((mod, g) -> attempt.joins.contains(g) ? group : g);
			groupOfUpdate.replaceAll((project, g) -> attempt.joins.contains(g) ? group : g);
			for (Op op : attempt.ops) {
				ops.add(op.inGroup(group));
				if (op.type() == PendingActions.Type.ENABLE_FILE && op.modId() != null) {
					groupOfMod.put(op.modId(), group);
				}
			}
			attempt.newProjects.forEach(project -> groupOfProject.put(project, group));
			if (attempt.updatedProject != null) {
				groupOfUpdate.put(attempt.updatedProject, group);
			}
			versions.addAll(attempt.versions);
			projects.addAll(attempt.projects);
			modIds.addAll(attempt.modIds);
			return group;
		}

		// Deletes a just-downloaded jar that no op uses (the one exception to never deleting).
		void dropDuplicate(Path pending) throws IOException {
			String path = pending.toString();
			if (!stagedJars.containsValue(path) && ops.stream().noneMatch(op -> path.equals(op.from()))) {
				Files.deleteIfExists(pending);
			}
		}

		void noteReplaced(String modId, Path pending) {
			String old = modId == null ? null : stagedJars.get(modId);
			if (old != null && !old.equals(pending.toString())) {
				RigTune.LOGGER.info("{} replaces the staged {} for mod {}", pending.getFileName(), Path.of(old).getFileName(), modId);
			}
		}
	}

	// One recommendation's work, on copies of the batch's sets.
	private static final class Attempt {
		final Batch batch;
		final Set<String> projects;
		final Set<String> modIds;
		final Set<String> joins = new LinkedHashSet<>();
		final List<String> newProjects = new ArrayList<>();
		final List<ModrinthVersion> versions = new ArrayList<>();
		final List<Op> ops = new ArrayList<>();
		String updatedProject;

		Attempt(Batch batch) {
			this.batch = batch;
			this.projects = new HashSet<>(batch.projects);
			this.modIds = new HashSet<>(batch.modIds);
		}
	}
}
