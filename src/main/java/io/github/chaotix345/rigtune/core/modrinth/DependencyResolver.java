package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.model.TextException;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DependencyResolver {
	public static final int DEFAULT_MAX_DEPTH = 5;
	static final String ANOTHER_MOD = "another mod";
	// The refusals are shown in the UI (docs/v0.3/SPEC.md item 9): rigtune.download.* keys, project names as arguments.
	private static final Text ANOTHER = Text.of("rigtune.download.another_mod", ANOTHER_MOD);

	// updatesNeeded: the installed projects whose update in this batch the resolution relies on, because something in it
	// is incompatible with the version installed now (plan review A-H1: the planner joins those updates' groups).
	public record Resolution(List<ModrinthVersion> versions, Set<String> updatesNeeded) {
	}

	private final ModrinthClient client;
	private final String loader;
	private final String gameVersion;
	private final int maxDepth;
	// The loaded mods' Modrinth versions by id, with their projects and dependencies where known.
	private final Map<String, ModrinthVersion> installed;
	// docs/v0.4/SPEC.md 2d (A-H1, A-M1): what earlier Applies staged. Read only by the incompatibility checks, never as
	// installed: a dependency that is only staged is still resolved (and so joins its staged group).
	private final StagedProjects staged;
	// The staged versions with their dependencies, asked of Modrinth once per resolver (one plan); empty when it can't say.
	private List<ModrinthVersion> stagedVersions;
	// Modrinth's latest version per project or slug, remembered for the resolver's run (one plan), so the pairwise
	// pre-check of additions (docs/v0.4/SPEC.md 2e) asks nothing twice.
	private final Map<String, ModrinthVersion> answers = new HashMap<>();

	public DependencyResolver(ModrinthClient client, String loader, String gameVersion) {
		this(client, loader, gameVersion, DEFAULT_MAX_DEPTH, Map.of());
	}

	// installedVersionIds: the Modrinth versions of the loaded mods, where known (a version-specific incompatibility
	// with an installed mod only counts when its version is known to be that one).
	public DependencyResolver(ModrinthClient client, String loader, String gameVersion, Set<String> installedVersionIds) {
		this(client, loader, gameVersion, DEFAULT_MAX_DEPTH, bare(installedVersionIds));
	}

	// installedVersions: the loaded mods' Modrinth versions by version id (OnlineDataFetcher.Result.installedVersions()).
	public DependencyResolver(ModrinthClient client, String loader, String gameVersion, Map<String, ModrinthVersion> installedVersions) {
		this(client, loader, gameVersion, DEFAULT_MAX_DEPTH, installedVersions);
	}

	public DependencyResolver(ModrinthClient client, String loader, String gameVersion, int maxDepth) {
		this(client, loader, gameVersion, maxDepth, Map.of());
	}

	private DependencyResolver(ModrinthClient client, String loader, String gameVersion, int maxDepth, Map<String, ModrinthVersion> installed) {
		this(client, loader, gameVersion, maxDepth, installed, StagedProjects.NONE);
	}

	private DependencyResolver(ModrinthClient client, String loader, String gameVersion, int maxDepth, Map<String, ModrinthVersion> installed,
			StagedProjects staged) {
		this.client = client;
		this.loader = loader;
		this.gameVersion = gameVersion;
		this.maxDepth = maxDepth;
		this.installed = Map.copyOf(installed);
		this.staged = staged;
	}

	// This resolver, also checking against what earlier Applies staged (StagedProjects.read of pending.json).
	public DependencyResolver withStaged(StagedProjects stagedProjects) {
		return new DependencyResolver(client, loader, gameVersion, maxDepth, installed, stagedProjects == null ? StagedProjects.NONE : stagedProjects);
	}

	// A version known only by its id and project (no dependencies).
	static ModrinthVersion known(String id, String projectId) {
		return new ModrinthVersion(id, projectId, null, null, List.of(), List.of(), Instant.EPOCH, List.of(), List.of());
	}

	private static Map<String, ModrinthVersion> bare(Set<String> versionIds) {
		Map<String, ModrinthVersion> out = new LinkedHashMap<>();
		versionIds.forEach(id -> out.put(id, known(id, null)));
		return out;
	}

	private record Pending(String idOrSlug, int depth) {
	}

	public List<ModrinthVersion> resolve(String slug, Set<String> installedProjectIds) throws IOException {
		return resolve(slug, installedProjectIds, List.of());
	}

	// batch: the versions going in with these (earlier recommendations of the same download batch). A version Modrinth
	// marks incompatible with an installed project, or with anything going in with it, refuses the whole resolution
	// (review 4, rules-accuracy-2).
	public List<ModrinthVersion> resolve(String slug, Set<String> installedProjectIds, List<ModrinthVersion> batch) throws IOException {
		return resolve(slug, installedProjectIds, batch, Set.of()).versions();
	}

	// updatedProjects: installed projects whose update this batch has staged (their new versions are in batch). The
	// resolution is judged against the installed mods with those updates applied (SPEC 3b, plan review A-H1); what only
	// passes because an update replaces the installed version comes back in updatesNeeded.
	public Resolution resolve(String slug, Set<String> installedProjectIds, List<ModrinthVersion> batch, Set<String> updatedProjects) throws IOException {
		List<ModrinthVersion> out = new ArrayList<>();
		Set<String> seen = new HashSet<>(installedProjectIds);
		Deque<Pending> queue = new ArrayDeque<>();
		queue.add(new Pending(slug, 0));
		while (!queue.isEmpty()) {
			Pending next = queue.poll();
			if (seen.contains(next.idOrSlug())) {
				continue;
			}
			Optional<ModrinthVersion> found = latest(next.idOrSlug());
			if (found.isEmpty()) {
				throw new TextException(Text.of("rigtune.download.no_version", "No %s version of %s for Minecraft %s", loader, next.idOrSlug(), gameVersion));
			}
			ModrinthVersion version = found.get();
			boolean fresh = !seen.contains(version.projectId());
			seen.add(next.idOrSlug());
			seen.add(version.projectId());
			if (!fresh) {
				continue;
			}
			out.add(version);
			if (next.depth() >= maxDepth) {
				continue;
			}
			for (Dependency dep : version.dependencies()) {
				if (dep.required() && dep.projectId() != null && !seen.contains(dep.projectId())) {
					queue.add(new Pending(dep.projectId(), next.depth() + 1));
				}
			}
		}
		List<ModrinthVersion> together = new ArrayList<>(batch);
		together.addAll(out);
		Set<String> needed = new LinkedHashSet<>();
		for (ModrinthVersion version : out) {
			refuseIncompatible(version, null, installedProjectIds, batch, together, updatedProjects, needed);
		}
		return new Resolution(List.copyOf(out), Collections.unmodifiableSet(needed));
	}

	Optional<ModrinthVersion> latest(String idOrSlug) throws IOException {
		ModrinthVersion known = answers.get(idOrSlug);
		if (known != null) {
			return Optional.of(known);
		}
		Optional<ModrinthVersion> found = client.latestVersion(idOrSlug, loader, gameVersion);
		found.ifPresent(version -> answers.put(idOrSlug, version));
		return found;
	}

	// An update's own version (SPEC 3b, plan review A-H1): refused when Modrinth marks it incompatible with an installed
	// project or version, or with anything in the batch, either side declaring it. Its own project's installed version
	// is the one it replaces; every other installed version counts, even one this batch also updates (the update is
	// offered again once that one is installed). An installed mod's declaration that the version being replaced matches
	// too (a whole-project entry) is a conflict that already exists, which refusing the update wouldn't remove.
	public void checkUpdate(ModrinthVersion update, Set<String> installedProjectIds, List<ModrinthVersion> batch) throws IOException {
		List<ModrinthVersion> together = new ArrayList<>(batch);
		together.add(update);
		refuseIncompatible(update, update.projectId(), installedProjectIds, batch, together, Set.of(), new HashSet<>());
	}

	// A dependency naming a version (version_id) is incompatible with that version only, not its whole project
	// (re-check of review 4). replacing: the project whose installed version this one replaces (an update's), or null.
	private void refuseIncompatible(ModrinthVersion version, String replacing, Set<String> installedProjects, List<ModrinthVersion> batch,
			List<ModrinthVersion> together, Set<String> updated, Set<String> needed) throws IOException {
		for (Dependency dep : version.dependencies()) {
			if (!dep.incompatible()) {
				continue;
			}
			if (dep.versionId() != null) {
				ModrinthVersion hit = installed.get(dep.versionId());
				if (hit != null && !same(hit.projectId(), replacing)) {
					if (hit.projectId() != null && updated.contains(hit.projectId())) {
						needed.add(hit.projectId());
					} else {
						throw installedIncompatible(version, dep.projectId() != null ? name(dep.projectId()) : versionName(dep.versionId(), together));
					}
				}
				// A staged version, known by id from pending.json itself (no Modrinth call).
				String stagedProject = staged.projectByVersion().get(dep.versionId());
				if (stagedProject != null && !same(stagedProject, replacing) && !stagedProject.equals(version.projectId())) {
					if (updated.contains(stagedProject)) {
						needed.add(stagedProject);
					} else {
						throw stagedIncompatible(version, name(stagedProject));
					}
				}
			} else if (dep.projectId() != null && installedProjects.contains(dep.projectId()) && !dep.projectId().equals(replacing)) {
				throw installedIncompatible(version, name(dep.projectId()));
			} else if (dep.projectId() != null && staged.projects().contains(dep.projectId()) && !dep.projectId().equals(replacing)
					&& !dep.projectId().equals(version.projectId())) {
				throw stagedIncompatible(version, name(dep.projectId()));
			}
			for (ModrinthVersion other : together) {
				if (other != version && matches(dep, other)) {
					throw bothInstalled(version.projectId(), other.projectId());
				}
			}
		}
		for (ModrinthVersion other : batch) {
			if (declaresIncompatible(other, version)) {
				throw bothInstalled(other.projectId(), version.projectId());
			}
		}
		// The installed mods' own declarations (the review's known gap): one this batch updates counts through its new
		// version, which is in the batch.
		for (ModrinthVersion mine : installed.values()) {
			if (same(mine.projectId(), replacing) || !declaresIncompatible(mine, version) || declaresIncompatibleWithReplaced(mine, replacing)) {
				continue;
			}
			if (mine.projectId() != null && updated.contains(mine.projectId())) {
				needed.add(mine.projectId());
			} else {
				throw new TextException(Text.of("rigtune.download.incompatible_installed", "Modrinth marks %s, which is installed, as incompatible with %s",
						name(mine.projectId()), name(version.projectId())));
			}
		}
		// The staged versions' own declarations (A-M1): only when Modrinth could say what they declare.
		for (ModrinthVersion mine : stagedVersions()) {
			if (same(mine.projectId(), replacing) || same(mine.projectId(), version.projectId()) || !declaresIncompatible(mine, version)
					|| declaresIncompatibleWithReplaced(mine, replacing)) {
				continue;
			}
			if (mine.projectId() != null && updated.contains(mine.projectId())) {
				needed.add(mine.projectId());
			} else {
				throw new TextException(Text.of("rigtune.download.staged_incompatible",
						"Modrinth marks %s, which is waiting for a restart, as incompatible with %s", name(mine.projectId()), name(version.projectId())));
			}
		}
	}

	private List<ModrinthVersion> stagedVersions() {
		if (stagedVersions == null) {
			stagedVersions = List.of();
			if (!staged.projectByVersion().isEmpty()) {
				try {
					Map<String, ModrinthVersion> found = client.versions(staged.projectByVersion().keySet());
					stagedVersions = found.values().stream().filter(v -> v != null && staged.projectByVersion().containsKey(v.id())).toList();
				} catch (IOException | RuntimeException e) {
					RigTune.LOGGER.info("Checking only the staged mods' projects, not their own declarations: {}", e.getMessage());
				}
			}
		}
		return stagedVersions;
	}

	private boolean declaresIncompatibleWithReplaced(ModrinthVersion declaring, String replacing) {
		return replacing != null && installed.values().stream().anyMatch(old -> same(old.projectId(), replacing) && declaresIncompatible(declaring, old));
	}

	private static boolean same(String projectId, String other) {
		return projectId != null && projectId.equals(other);
	}

	// docs/v0.4/SPEC.md 2e: two versions that can't go in together, whichever of them declares it.
	static boolean incompatible(ModrinthVersion a, ModrinthVersion b) {
		return declaresIncompatible(a, b) || declaresIncompatible(b, a);
	}

	private static boolean declaresIncompatible(ModrinthVersion declaring, ModrinthVersion target) {
		return declaring != target && declaring.dependencies().stream().anyMatch(dep -> dep.incompatible() && matches(dep, target));
	}

	private static boolean matches(Dependency dep, ModrinthVersion version) {
		return dep.versionId() != null ? dep.versionId().equals(version.id()) : dep.projectId() != null && dep.projectId().equals(version.projectId());
	}

	private IOException installedIncompatible(ModrinthVersion version, Object installedName) {
		return new TextException(Text.of("rigtune.download.incompatible", "Modrinth marks %s as incompatible with %s, which is installed",
				name(version.projectId()), installedName));
	}

	private IOException stagedIncompatible(ModrinthVersion version, Object stagedName) {
		return new TextException(Text.of("rigtune.download.incompatible_staged", "Modrinth marks %s as incompatible with %s, which is waiting for a restart",
				name(version.projectId()), stagedName));
	}

	TextException bothInstalled(String a, String b) {
		return new TextException(Text.of("rigtune.download.incompatible_both", "Modrinth marks %s and %s as incompatible, and both would be installed",
				name(a), name(b)));
	}

	// The project a version-only dependency points at, from local data only (SPEC 3c, plan review A-M2: no new Modrinth
	// call): the installed versions, then what goes in together. Never the version id.
	private Object versionName(String versionId, List<ModrinthVersion> together) {
		String project = null;
		ModrinthVersion mine = installed.get(versionId);
		if (mine != null) {
			project = mine.projectId();
		}
		for (ModrinthVersion other : together) {
			if (project == null && versionId.equals(other.id())) {
				project = other.projectId();
			}
		}
		String title = project == null ? null : title(project);
		return title != null ? title : ANOTHER;
	}

	// The project's title (or slug) for a message; its id when Modrinth can't say.
	private Object name(String projectId) {
		if (projectId == null) {
			return ANOTHER;
		}
		String title = title(projectId);
		return title != null ? title : projectId;
	}

	// Null when Modrinth can't say.
	private String title(String projectId) {
		try {
			for (ModrinthProject project : client.projects(List.of(projectId))) {
				if (projectId.equals(project.id())) {
					return project.title() != null ? project.title() : project.slug();
				}
			}
		} catch (IOException | RuntimeException e) {
			// The fallback will do.
		}
		return null;
	}
}
