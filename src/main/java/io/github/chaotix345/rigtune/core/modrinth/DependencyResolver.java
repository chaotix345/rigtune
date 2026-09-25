package io.github.chaotix345.rigtune.core.modrinth;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class DependencyResolver {
	public static final int DEFAULT_MAX_DEPTH = 5;

	private final ModrinthClient client;
	private final String loader;
	private final String gameVersion;
	private final int maxDepth;
	private final Set<String> installedVersionIds;

	public DependencyResolver(ModrinthClient client, String loader, String gameVersion) {
		this(client, loader, gameVersion, DEFAULT_MAX_DEPTH, Set.of());
	}

	// installedVersionIds: the Modrinth versions of the loaded mods, where known (a version-specific incompatibility
	// with an installed mod only counts when its version is known to be that one).
	public DependencyResolver(ModrinthClient client, String loader, String gameVersion, Set<String> installedVersionIds) {
		this(client, loader, gameVersion, DEFAULT_MAX_DEPTH, installedVersionIds);
	}

	public DependencyResolver(ModrinthClient client, String loader, String gameVersion, int maxDepth) {
		this(client, loader, gameVersion, maxDepth, Set.of());
	}

	private DependencyResolver(ModrinthClient client, String loader, String gameVersion, int maxDepth, Set<String> installedVersionIds) {
		this.client = client;
		this.loader = loader;
		this.gameVersion = gameVersion;
		this.maxDepth = maxDepth;
		this.installedVersionIds = Set.copyOf(installedVersionIds);
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
		List<ModrinthVersion> out = new ArrayList<>();
		Set<String> seen = new HashSet<>(installedProjectIds);
		Deque<Pending> queue = new ArrayDeque<>();
		queue.add(new Pending(slug, 0));
		while (!queue.isEmpty()) {
			Pending next = queue.poll();
			if (seen.contains(next.idOrSlug())) {
				continue;
			}
			Optional<ModrinthVersion> found = client.latestVersion(next.idOrSlug(), loader, gameVersion);
			if (found.isEmpty()) {
				throw new IOException("No " + loader + " version of " + next.idOrSlug() + " for Minecraft " + gameVersion);
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
		refuseIncompatible(out, installedProjectIds, batch);
		return out;
	}

	// A dependency naming a version (version_id) is incompatible with that version only, not its whole project
	// (re-check of review 4).
	private void refuseIncompatible(List<ModrinthVersion> found, Set<String> installed, List<ModrinthVersion> batch) throws IOException {
		List<ModrinthVersion> together = new ArrayList<>(batch);
		together.addAll(found);
		for (ModrinthVersion version : found) {
			for (Dependency dep : version.dependencies()) {
				if (!dep.incompatible()) {
					continue;
				}
				boolean installedHit = dep.versionId() != null ? installedVersionIds.contains(dep.versionId())
						: dep.projectId() != null && installed.contains(dep.projectId());
				if (installedHit) {
					throw new IOException("Modrinth marks " + name(version.projectId()) + " as incompatible with "
							+ name(dep.projectId() != null ? dep.projectId() : dep.versionId()) + ", which is installed");
				}
				for (ModrinthVersion other : together) {
					if (matches(dep, other)) {
						throw bothInstalled(version.projectId(), other.projectId());
					}
				}
			}
			for (ModrinthVersion other : batch) {
				if (other.dependencies().stream().anyMatch(dep -> dep.incompatible() && matches(dep, version))) {
					throw bothInstalled(other.projectId(), version.projectId());
				}
			}
		}
	}

	private static boolean matches(Dependency dep, ModrinthVersion version) {
		return dep.versionId() != null ? dep.versionId().equals(version.id()) : dep.projectId() != null && dep.projectId().equals(version.projectId());
	}

	private IOException bothInstalled(String a, String b) {
		return new IOException("Modrinth marks " + name(a) + " and " + name(b) + " as incompatible, and both would be installed");
	}

	// The project's title (or slug) for a message; its id when Modrinth can't say.
	private String name(String projectId) {
		try {
			for (ModrinthProject project : client.projects(List.of(projectId))) {
				if (projectId.equals(project.id())) {
					return project.title() != null ? project.title() : project.slug() != null ? project.slug() : projectId;
				}
			}
		} catch (IOException | RuntimeException e) {
			// The id will do.
		}
		return projectId;
	}
}
