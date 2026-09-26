package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

// docs/v0.4/SPEC.md 2d (amendments A-H1, A-M1, A-L1): the Modrinth projects (and versions) of the ENABLE_FILE ops an
// earlier Apply staged in pending.json that the helper hasn't applied yet. Only the incompatibility checks read them
// (DependencyResolver.withStaged), never the resolver's installed projects: a later addition that requires a staged mod
// is still resolved, so it joins that mod's staged group. Staged DISABLE_FILE ops carry no id and don't count
// (under-blocking, the safe direction; a documented residual). projectByVersion: staged version id -> its project.
public record StagedProjects(Set<String> projects, Map<String, String> projectByVersion) {
	public static final StagedProjects NONE = new StagedProjects(Set.of(), Map.of());

	public StagedProjects {
		projects = Set.copyOf(projects);
		projectByVersion = Map.copyOf(projectByVersion);
	}

	public static StagedProjects fold(@Nullable PendingActions plan) {
		if (plan == null) {
			return NONE;
		}
		Set<String> projects = new LinkedHashSet<>();
		Map<String, String> versions = new LinkedHashMap<>();
		for (Op op : plan.ops()) {
			if (op == null || op.type() != PendingActions.Type.ENABLE_FILE || blank(op.projectId())) {
				continue;
			}
			projects.add(op.projectId());
			if (!blank(op.versionId())) {
				versions.put(op.versionId(), op.projectId());
			}
		}
		return projects.isEmpty() ? NONE : new StagedProjects(projects, versions);
	}

	// pending.json as the helper will see it (PendingActions.relocated, as Staging merges into it); nothing when it's
	// missing or unreadable.
	public static StagedProjects read(Path pendingFile) {
		if (!Files.exists(pendingFile)) {
			return NONE;
		}
		try {
			return fold(PendingActions.load(pendingFile).relocated(InstanceDirs.modsDirOf(pendingFile), InstanceDirs.configDirOf(pendingFile)));
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not read the staged mods in {}: {}", pendingFile, e.getMessage());
			return NONE;
		}
	}

	public boolean isEmpty() {
		return projects.isEmpty();
	}

	private static boolean blank(@Nullable String value) {
		return value == null || value.isBlank();
	}
}
