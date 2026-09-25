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

	public DependencyResolver(ModrinthClient client, String loader, String gameVersion) {
		this(client, loader, gameVersion, DEFAULT_MAX_DEPTH);
	}

	public DependencyResolver(ModrinthClient client, String loader, String gameVersion, int maxDepth) {
		this.client = client;
		this.loader = loader;
		this.gameVersion = gameVersion;
		this.maxDepth = maxDepth;
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

	private static void refuseIncompatible(List<ModrinthVersion> found, Set<String> installed, List<ModrinthVersion> batch) throws IOException {
		List<ModrinthVersion> together = new ArrayList<>(batch);
		together.addAll(found);
		for (ModrinthVersion version : found) {
			for (Dependency dep : version.dependencies()) {
				if (!dep.incompatible() || dep.projectId() == null) {
					continue;
				}
				if (installed.contains(dep.projectId())) {
					throw new IOException("Modrinth marks " + version.projectId() + " as incompatible with " + dep.projectId() + ", which is installed");
				}
				if (together.stream().anyMatch(other -> dep.projectId().equals(other.projectId()))) {
					throw bothInstalled(version.projectId(), dep.projectId());
				}
			}
			for (ModrinthVersion other : batch) {
				if (other.dependencies().stream().anyMatch(dep -> dep.incompatible() && version.projectId().equals(dep.projectId()))) {
					throw bothInstalled(other.projectId(), version.projectId());
				}
			}
		}
	}

	private static IOException bothInstalled(String a, String b) {
		return new IOException("Modrinth marks " + a + " and " + b + " as incompatible, and both would be installed");
	}
}
