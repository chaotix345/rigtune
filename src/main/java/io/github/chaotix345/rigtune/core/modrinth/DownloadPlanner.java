package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.SafeFileNames;
import io.github.chaotix345.rigtune.core.history.JarInfo;
import io.github.chaotix345.rigtune.core.model.Action;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.ModFile;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.TextException;
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
import java.util.function.BiFunction;
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
	// none), so the client can tell which recommendations are still staged (plan review A-M1). errorTexts: the errors as
	// the UI shows them (docs/v0.3/SPEC.md item 9); errors stay their English.
	public record Result(List<Op> ops, List<String> ids, List<String> errors, Map<String, List<String>> opIds, List<Text> errorTexts) {
		public Result {
			errorTexts = errorTexts != null ? List.copyOf(errorTexts) : errors == null ? List.of() : errors.stream().map(Text::literal).toList();
		}

		public Result(List<Op> ops, List<String> ids, List<String> errors, Map<String, List<String>> opIds) {
			this(ops, ids, errors, opIds, null);
		}

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
	private final Function<Path, String> versionOf;
	private final VersionPins pins;
	private boolean lookedUp = true;

	public DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher) {
		this(resolver, modsDir, fetcher, (a, b) -> false);
	}

	// conflicts: whether the mods of two AddMod slugs can't be installed together (the rules' ModConflicts).
	public DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, BiPredicate<String, String> conflicts) {
		this(resolver, modsDir, fetcher, conflicts, Map.of());
	}

	// updateVersions: the updates' Modrinth versions by version id, dependencies included
	// (OnlineDataFetcher.Result.updateVersions()); an update missing from it is refused, since its own incompatibilities
	// can't be checked.
	public DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions) {
		this(resolver, modsDir, fetcher, conflicts, updateVersions, VersionPins.NONE);
	}

	// pins: the loaded mods' fabric.mod.json version ranges on other mods (docs/v0.4/SPEC.md 2o, H2), matched against each
	// downloaded jar's own version.
	public DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions, VersionPins pins) {
		this(resolver, modsDir, fetcher, conflicts, updateVersions, ModJars::modIdOf, ModJars::versionOf, pins);
	}

	// The dry run (DryRunPlanner): nothing is downloaded, so no jar's version is known and no pin is checked.
	DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions, Function<Path, String> modIdOf) {
		this(resolver, modsDir, fetcher, conflicts, updateVersions, modIdOf, jar -> null, VersionPins.NONE);
	}

	private DownloadPlanner(DependencyResolver resolver, Path modsDir, Fetcher fetcher, BiPredicate<String, String> conflicts,
			Map<String, ModrinthVersion> updateVersions, Function<Path, String> modIdOf, Function<Path, String> versionOf, VersionPins pins) {
		this.resolver = resolver;
		this.modsDir = modsDir;
		this.fetcher = fetcher;
		this.conflicts = conflicts;
		this.updateVersions = Map.copyOf(updateVersions);
		this.modIdOf = modIdOf;
		this.versionOf = versionOf;
		this.pins = pins == null ? VersionPins.NONE : pins;
	}

	// docs/v0.4/SPEC.md 2o, H3: the loadedIds for plan(): the scanned mods that are top-level jars. A mod nested inside
	// another (jar-in-jar) is no second copy: Fabric loads a top-level jar next to it (the newer one wins), and the helper's
	// duplicate check counts top-level jars only. InstalledMod: sha1 is null only for built-in and nested mods; a file is
	// a jar directly in mods/ (its hash may have failed).
	public static Set<String> topLevelIds(List<InstalledMod> scanned) {
		Set<String> out = new HashSet<>();
		if (scanned != null) {
			scanned.stream().filter(m -> m.sha1() != null || m.file() != null).forEach(m -> out.add(m.modId()));
		}
		return out;
	}

	// docs/v0.4/SPEC.md 2o, M4: false while the installed mods haven't been looked up on Modrinth (the lookup is still
	// running, or it failed and waits for a Rescan). The checks against installed mods need that data, so plan() then
	// refuses every recommendation, fetching and asking nothing. With Modrinth lookups off there is nothing to wait for.
	public DownloadPlanner lookedUp(boolean installedLookedUp) {
		this.lookedUp = installedLookedUp;
		return this;
	}

	// installedProjects / loadedIds: the Modrinth projects and mod ids of the loaded top-level mods (topLevelIds).
	// stagedJars: mod id -> the pending jar of an enable already in pending.json (a newer download replaces it when merged).
	public Result plan(List<Recommendation> recs, Set<String> installedProjects, Set<String> loadedIds, Map<String, String> stagedJars) {
		Batch batch = new Batch(installedProjects, loadedIds, stagedJars);
		Map<String, List<String>> opIds = new LinkedHashMap<>();
		// Every update before any addition, so the outcome doesn't depend on the order they were ticked in (A-H1).
		List<Recommendation> ordered = new ArrayList<>();
		recs.stream().filter(r -> r.action() instanceof Action.UpdateMod).forEach(ordered::add);
		recs.stream().filter(r -> !(r.action() instanceof Action.UpdateMod)).forEach(ordered::add);
		if (!lookedUp) {
			return notLookedUp(ordered);
		}
		Map<String, Text> refusedTogether = refusedTogether(ordered);
		// Positions in ordered. An update needing a project that isn't installed waits once until the additions are planned
		// (docs/v0.4/SPEC.md 2o, H1-A). The ids and errors keep ordered's order (PreviewDownloads matches errors by position).
		List<Integer> queue = new ArrayList<>();
		for (int i = 0; i < ordered.size(); i++) {
			queue.add(i);
		}
		Set<Integer> waited = new HashSet<>();
		boolean[] staged = new boolean[ordered.size()];
		String[] errorAt = new String[ordered.size()];
		Text[] errorTextAt = new Text[ordered.size()];
		for (int q = 0; q < queue.size(); q++) {
			int at = queue.get(q);
			Recommendation rec = ordered.get(at);
			boolean additionsLater = queue.subList(q + 1, queue.size()).stream().anyMatch(i -> ordered.get(i).action() instanceof Action.AddMod);
			Attempt attempt = new Attempt(batch, additionsLater && !waited.contains(at));
			try {
				Text together = refusedTogether.get(rec.id());
				if (together != null) {
					throw new TextException(together);
				}
				switch (rec.action()) {
					case Action.AddMod add -> addMod(add, attempt);
					case Action.UpdateMod update -> updateMod(update, attempt);
					default -> {
					}
				}
			} catch (WaitForAdditions w) {
				waited.add(at);
				queue.add(at);
				continue;
			} catch (IOException | RuntimeException e) {
				RigTune.LOGGER.warn("Could not prepare {}", rec.id(), e);
				errorAt[at] = rec.title() + ": " + e.getMessage();
				// A refusal of the planner or the resolver is translated; a network or file error's detail stays as it is.
				errorTextAt[at] = Text.of("rigtune.download.error", "%s: %s", rec.titleText(),
						e instanceof TextException text ? text.text() : Text.literal(String.valueOf(e.getMessage())));
				continue;
			}
			String group = batch.commit(attempt);
			staged[at] = true;
			List<Op> own = attempt.ops.isEmpty() && !attempt.joins.isEmpty()
					? batch.ops.stream().filter(op -> group.equals(op.group())).toList() : attempt.ops;
			opIds.put(rec.id(), own.stream().map(Op::id).toList());
		}
		List<String> ids = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		List<Text> errorTexts = new ArrayList<>();
		for (int i = 0; i < ordered.size(); i++) {
			if (staged[i]) {
				ids.add(ordered.get(i).id());
			} else if (errorAt[i] != null) {
				errors.add(errorAt[i]);
				errorTexts.add(errorTextAt[i]);
			}
		}
		return new Result(List.copyOf(batch.ops), ids, errors, Map.copyOf(opIds), errorTexts);
	}

	private static Result notLookedUp(List<Recommendation> ordered) {
		Text wait = Text.of("rigtune.download.not_loaded",
				"Modrinth's data for your mods isn't loaded yet, or the lookup failed; wait a moment or press Rescan, then try again");
		List<String> errors = new ArrayList<>();
		List<Text> errorTexts = new ArrayList<>();
		for (Recommendation rec : ordered) {
			errors.add(rec.title() + ": " + wait.english());
			errorTexts.add(Text.of("rigtune.download.error", "%s: %s", rec.titleText(), wait));
		}
		return new Result(List.of(), List.of(), errors, Map.of(), errorTexts);
	}

	// docs/v0.4/SPEC.md 2e: pairs of ticked recommendations that can't go in together are refused together, before
	// anything is downloaded, each naming the other: RigTune can't know which one the player wanted, and the tick order
	// mustn't decide. Updates: their new versions, either declaring the other incompatible on Modrinth. Additions: the
	// rules' conflicts (review 4, rules-accuracy-2), and Modrinth's incompatibility between their own versions (a
	// dependency's is still found when it's resolved, and fails the later one). Recommendation id -> the refusal.
	private Map<String, Text> refusedTogether(List<Recommendation> ordered) {
		Map<String, Text> out = new HashMap<>();
		List<Recommendation> updates = new ArrayList<>();
		List<Recommendation> additions = new ArrayList<>();
		Map<String, ModrinthVersion> versions = new HashMap<>();
		for (Recommendation rec : ordered) {
			switch (rec.action()) {
				case Action.UpdateMod update -> {
					ModrinthVersion next = updateVersions.get(update.update().newVersionId());
					if (next != null) {
						updates.add(rec);
						versions.put(rec.id(), next);
					}
				}
				case Action.AddMod add -> additions.add(rec);
				default -> {
				}
			}
		}
		if (additions.size() > 1) {
			for (Recommendation rec : additions) {
				Action.AddMod add = (Action.AddMod) rec.action();
				try {
					resolver.latest(add.projectId() != null ? add.projectId() : add.slug()).ifPresent(version -> versions.put(rec.id(), version));
				} catch (IOException | RuntimeException e) {
					// Resolving it again fails the recommendation with the reason.
				}
			}
		}
		pairs(updates, versions, out, null);
		// Either direction of the rules' conflicts counts (ModConflicts is symmetric; a caller's predicate may not be).
		pairs(additions, versions, out, (a, b) -> conflicts.test(((Action.AddMod) a.action()).slug(), ((Action.AddMod) b.action()).slug())
				|| conflicts.test(((Action.AddMod) b.action()).slug(), ((Action.AddMod) a.action()).slug())
				? Text.of("rigtune.download.conflicts", "it conflicts with %s, which is ticked too; tick only one of them", ((Action.AddMod) b.action()).title())
				: null);
		return out;
	}

	private void pairs(List<Recommendation> recs, Map<String, ModrinthVersion> versions, Map<String, Text> out,
			BiFunction<Recommendation, Recommendation, Text> ruleConflict) {
		for (int i = 0; i < recs.size(); i++) {
			for (int j = i + 1; j < recs.size(); j++) {
				Recommendation a = recs.get(i);
				Recommendation b = recs.get(j);
				Text ab = ruleConflict == null ? null : ruleConflict.apply(a, b);
				Text ba = ruleConflict == null ? null : ruleConflict.apply(b, a);
				ModrinthVersion va = versions.get(a.id());
				ModrinthVersion vb = versions.get(b.id());
				if (ab == null && va != null && vb != null && DependencyResolver.incompatible(va, vb)) {
					ab = resolver.bothInstalled(va.projectId(), vb.projectId()).text();
					ba = resolver.bothInstalled(vb.projectId(), va.projectId()).text();
				}
				if (ab != null) {
					out.putIfAbsent(a.id(), ab);
					out.putIfAbsent(b.id(), ba);
				}
			}
		}
	}

	private void addMod(Action.AddMod add, Attempt attempt) throws IOException {
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
				throw new TextException(Text.of("rigtune.download.no_file", "No file for %s", version.versionNumber()));
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
				throw notAMod(file);
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
			refusePinned(jarModId, pending, attempt);
			attempt.batch.noteReplaced(jarModId, pending);
			attempt.newProjects.add(version.projectId());
			attempt.versions.add(version);
			// The Modrinth identity lets the next Apply's checks see this staged addition (docs/v0.4/SPEC.md 2d).
			attempt.ops.add(Op.enableFile(pending, target).withModId(jarModId).withProjectId(version.projectId()).withVersionId(version.id()));
		}
	}

	private void updateMod(Action.UpdateMod update, Attempt attempt) throws IOException {
		ModFile file = update.update().file();
		if (file == null) {
			throw new TextException(Text.of("rigtune.download.no_file", "No file for %s", update.update().newVersionNumber()));
		}
		if (!SafeFileNames.isDirectChild(modsDir, update.currentFile())) {
			throw new TextException(Text.of("rigtune.download.outside_mods_folder", "it isn't in this instance's mods folder; update it in your launcher"));
		}
		Path target = SafeFileNames.resolveJar(modsDir, file.filename());
		// As for an added mod: the enable never overwrites, so a target taken by another file would fail at every exit.
		if (Files.exists(target) && !(Files.exists(update.currentFile()) && Files.isSameFile(target, update.currentFile()))) {
			throw new TextException(Text.of("rigtune.download.target_exists", "%s is already in the mods folder", target.getFileName().toString()));
		}
		// The update's own version is judged like an addition's, against the installed mods and the batch (A-H1).
		UpdateInfo info = update.update();
		ModrinthVersion next = updateVersions.get(info.newVersionId());
		if (next == null) {
			throw new TextException(Text.of("rigtune.download.stale", "its Modrinth data changed since the list was made; try again"));
		}
		resolver.checkUpdate(next, attempt.projects, attempt.batch.versions);
		// docs/v0.4/SPEC.md 2o, H1-A: a project the new version requires that isn't installed would stop the game from
		// starting. One this batch's additions stage is joined (the update waits for them once); otherwise the update is
		// refused: an update's own dependencies aren't resolved.
		for (String project : resolver.missingRequirements(next, attempt.projects)) {
			String stagedBy = attempt.batch.groupOfProject.get(project);
			if (stagedBy != null) {
				attempt.joins.add(stagedBy);
			} else if (attempt.mayWait) {
				throw new WaitForAdditions();
			} else {
				throw resolver.missingRequirement(project);
			}
		}
		Path pending = fetcher.fetch(file);
		String jarModId = modIdOf.apply(pending);
		// As for an added mod (review 4, apply-safety-1): the installed jar is only replaced by a jar with a readable id.
		if (jarModId == null) {
			attempt.batch.dropDuplicate(pending);
			throw notAMod(file);
		}
		// docs/v0.4/SPEC.md 2o, M7: another mod in its place (a secondary file installed, a renamed mod) would leave everything
		// that depends on the old id without it, unless the new jar still provides that id (or nests a mod with it).
		if (update.modId() != null && !update.modId().equals(jarModId) && !provides(pending, update.modId())) {
			attempt.batch.dropDuplicate(pending);
			throw new TextException(Text.of("rigtune.download.not_same_mod", "%s is a different mod (%s, not %s)", file.filename(), jarModId, update.modId()));
		}
		refusePinned(jarModId, pending, attempt);
		attempt.batch.noteReplaced(jarModId, pending);
		attempt.ops.add(Op.disableFile(update.currentFile()));
		attempt.ops.add(Op.enableFile(pending, target).withModId(jarModId).withProjectId(next.projectId()).withVersionId(next.id()));
		attempt.versions.add(next);
		attempt.updatedProject = info.projectId();
	}

	// docs/v0.4/SPEC.md 2o, H2: an installed mod whose fabric.mod.json `depends` range excludes this jar's version (or whose
	// `breaks` range includes it) would stop Fabric from starting, so the jar isn't staged.
	private void refusePinned(String jarModId, Path pending, Attempt attempt) throws IOException {
		Text problem = pins.problem(jarModId, versionOf.apply(pending));
		if (problem != null) {
			attempt.batch.dropDuplicate(pending);
			throw new TextException(problem);
		}
	}

	private static boolean provides(Path jar, String modId) {
		JarInfo info = JarInfo.read(jar);
		return info != null && info.provides().contains(modId);
	}

	private static TextException notAMod(ModFile file) {
		return new TextException(Text.of("rigtune.download.not_a_mod", "%s is not a Fabric mod jar (no readable fabric.mod.json id)", file.filename()));
	}

	// What the batch has committed so far. projects and modIds are what is installed (loaded, or already in mods/);
	// what the batch staged is in groupOfProject and groupOfMod, with the group it went into, and in versions.
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

	// One recommendation's work, on copies of the batch's sets. mayWait: an update may still wait for the batch's additions.
	private static final class Attempt {
		final Batch batch;
		final boolean mayWait;
		final Set<String> projects;
		final Set<String> modIds;
		final Set<String> joins = new LinkedHashSet<>();
		final List<String> newProjects = new ArrayList<>();
		final List<ModrinthVersion> versions = new ArrayList<>();
		final List<Op> ops = new ArrayList<>();
		String updatedProject;

		Attempt(Batch batch, boolean mayWait) {
			this.batch = batch;
			this.mayWait = mayWait;
			this.projects = new HashSet<>(batch.projects);
			this.modIds = new HashSet<>(batch.modIds);
		}
	}

	// An update that needs a project an addition later in the batch may stage: planned again after the additions.
	private static final class WaitForAdditions extends RuntimeException {
		WaitForAdditions() {
			super(null, null, false, false);
		}
	}
}
