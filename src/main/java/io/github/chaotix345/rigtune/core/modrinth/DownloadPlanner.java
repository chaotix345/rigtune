package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

// Turns a batch of Add/Update recommendations into staged ops: one all-or-nothing group per recommendation.
// Each recommendation works on its own copies of the installed projects and mod ids, committed only when it succeeds,
// so a failed one can't make a later one skip a dependency it never delivered. A recommendation that needs a
// dependency an earlier one in the batch staged joins that one's group, so the dependency can't be applied or rolled
// back without it.
public final class DownloadPlanner {
	public interface Fetcher {
		// Downloads the file (hash-checked) to <mods>/<name>.jar.rigtune-pending and returns that path.
		Path fetch(ModFile file) throws IOException;
	}

	public record Result(List<Op> ops, List<String> ids, List<String> errors) {
	}

	private final DependencyResolver resolver;
	private final Path modsDir;
	private final Fetcher fetcher;
	private final Function<Path, String> modIdOf;

	public DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher) {
		this(resolver, modsDir, fetcher, ModJars::modIdOf);
	}

	DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, Function<Path, String> modIdOf) {
		this.resolver = resolver;
		this.modsDir = modsDir;
		this.fetcher = fetcher;
		this.modIdOf = modIdOf;
	}

	// installedProjects / loadedIds: the Modrinth projects and mod ids of the loaded mods. stagedJars: mod id -> the
	// pending jar of an enable already in pending.json (a newer download replaces it when merged).
	public Result plan(List<Recommendation> recs, Set<String> installedProjects, Set<String> loadedIds, Map<String, String> stagedJars) {
		Batch batch = new Batch(installedProjects, loadedIds, stagedJars);
		List<String> ids = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		for (Recommendation rec : recs) {
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
			batch.commit(attempt);
			ids.add(rec.id());
		}
		return new Result(List.copyOf(batch.ops), ids, errors);
	}

	private void addMod(Action.AddMod add, Attempt attempt) throws IOException {
		String ref = add.projectId() != null ? add.projectId() : add.slug();
		// Projects staged earlier in this batch aren't in attempt.projects, so they come back from the resolver and are joined.
		for (ModrinthVersion version : resolver.resolve(ref, attempt.projects)) {
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
			String sameMod = jarModId == null ? null : attempt.batch.groupOfMod.get(jarModId);
			if (sameMod != null) {
				attempt.joins.add(sameMod);
				attempt.batch.dropDuplicate(pending);
				continue;
			}
			// A second jar with an already-loaded mod id would stop Fabric from starting, so drop it.
			if (jarModId != null && !attempt.modIds.add(jarModId)) {
				RigTune.LOGGER.info("Skipping {}: mod {} is already present", file.filename(), jarModId);
				attempt.batch.dropDuplicate(pending);
				continue;
			}
			attempt.batch.noteReplaced(jarModId, pending);
			attempt.newProjects.add(version.projectId());
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
		Path pending = fetcher.fetch(file);
		String jarModId = Objects.requireNonNullElse(modIdOf.apply(pending), update.modId());
		attempt.batch.noteReplaced(jarModId, pending);
		attempt.ops.add(Op.disableFile(update.currentFile()));
		attempt.ops.add(Op.enableFile(pending, target).withModId(jarModId));
	}

	// What the batch has committed so far. projects and modIds are what is installed (loaded, or already in mods/);
	// what the batch staged is in groupOfProject and groupOfMod, with the group it went into.
	private static final class Batch {
		final Set<String> projects;
		final Set<String> modIds;
		final Map<String, String> stagedJars;
		final Map<String, String> groupOfProject = new HashMap<>();
		final Map<String, String> groupOfMod = new HashMap<>();
		final List<Op> ops = new ArrayList<>();

		Batch(Set<String> installedProjects, Set<String> loadedIds, Map<String, String> stagedJars) {
			this.projects = new HashSet<>(installedProjects);
			this.modIds = new HashSet<>(loadedIds);
			this.stagedJars = stagedJars;
		}

		void commit(Attempt attempt) {
			String group = attempt.joins.isEmpty() ? PendingActions.newId() : attempt.joins.iterator().next();
			for (int i = 0; i < ops.size(); i++) {
				if (attempt.joins.contains(ops.get(i).group())) {
					ops.set(i, ops.get(i).inGroup(group));
				}
			}
			groupOfProject.replaceAll((project, g) -> attempt.joins.contains(g) ? group : g);
			groupOfMod.replaceAll((mod, g) -> attempt.joins.contains(g) ? group : g);
			for (Op op : attempt.ops) {
				ops.add(op.inGroup(group));
				if (op.type() == PendingActions.Type.ENABLE_FILE && op.modId() != null) {
					groupOfMod.put(op.modId(), group);
				}
			}
			attempt.newProjects.forEach(project -> groupOfProject.put(project, group));
			projects.addAll(attempt.projects);
			modIds.addAll(attempt.modIds);
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
		final List<Op> ops = new ArrayList<>();

		Attempt(Batch batch) {
			this.batch = batch;
			this.projects = new HashSet<>(batch.projects);
			this.modIds = new HashSet<>(batch.modIds);
		}
	}
}
