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
		return out;
	}
}
