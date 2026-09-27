package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.InstanceDirs;
import io.github.chaotix345.rigtune.core.apply.LogSafe;
import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// docs/v0.4/SPEC.md 2d (amendments A-H1, A-M1, A-L1): the Modrinth projects (and versions) of the ENABLE_FILE ops an
// earlier Apply staged in pending.json that the helper hasn't applied yet. Only the incompatibility checks read them
// (DependencyResolver.withStaged), never the resolver's installed projects: a later addition that requires a staged mod
// is still resolved, so it joins that mod's staged group. projectByVersion: staged version id -> its project.
// docs/v0.5/SPEC.md 2H L9: disabledSha1s, the jars staged DISABLE_FILE ops turn off at the next restart (by SHA-1, which
// the resolver matches against the installed versions' files), unless a staged enable brings the same mod back (an
// update's or an undo's other half). Only the dependency checks read them; the incompatibility checks still count those
// mods as present (L10: over-blocking, the safe side). enabledMods: the mod ids staged enables bring in.
public record StagedProjects(Set<String> projects, Map<String, String> projectByVersion, Set<String> disabledSha1s, Set<String> enabledMods) {
	public static final StagedProjects NONE = new StagedProjects(Set.of(), Map.of());

	public StagedProjects {
		projects = Set.copyOf(projects);
		projectByVersion = Map.copyOf(projectByVersion);
		disabledSha1s = Set.copyOf(disabledSha1s);
		enabledMods = Set.copyOf(enabledMods);
	}

	public StagedProjects(Set<String> projects, Map<String, String> projectByVersion) {
		this(projects, projectByVersion, Set.of(), Set.of());
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
			PendingActions plan = PendingActions.load(pendingFile).relocated(InstanceDirs.modsDirOf(pendingFile), InstanceDirs.configDirOf(pendingFile));
			List<Path> disabled = new ArrayList<>();
			for (Op op : plan.ops()) {
				if (op != null && op.type() == PendingActions.Type.DISABLE_FILE && op.path() != null) {
					disabled.add(Path.of(op.path()));
				}
			}
			return fold(plan).withEnables(plan.ops()).withDisabled(disabled);
		} catch (IOException | RuntimeException e) {
			RigTune.LOGGER.warn("Could not read the staged mods in {}: {}", LogSafe.name(pendingFile), LogSafe.error(e, pendingFile));
			return NONE;
		}
	}

	// docs/v0.5/SPEC.md 2H L9: this view with the mods these staged enables bring in (their staged mod id, else their jar's).
	public StagedProjects withEnables(Collection<Op> ops) {
		Set<String> mods = new HashSet<>(enabledMods);
		for (Op op : ops) {
			if (op != null && op.type() == PendingActions.Type.ENABLE_FILE) {
				String modId = op.modId() != null ? op.modId() : modIdAt(op.from());
				if (modId != null) {
					mods.add(modId);
				}
			}
		}
		return new StagedProjects(projects, projectByVersion, disabledSha1s, mods);
	}

	// docs/v0.5/SPEC.md 2H L9: this view with the jars these disables turn off at the next restart: those still there
	// whose mod no staged enable brings back.
	public StagedProjects withDisabled(Collection<Path> jars) {
		Set<String> sha1s = new HashSet<>(disabledSha1s);
		for (Path jar : jars) {
			if (jar == null || !Files.isRegularFile(jar)) {
				continue;
			}
			String modId = ModJars.modIdOf(jar);
			if (modId != null && enabledMods.contains(modId)) {
				continue;
			}
			String sha1 = sha1(jar);
			if (sha1 != null) {
				sha1s.add(sha1);
			}
		}
		return new StagedProjects(projects, projectByVersion, sha1s, enabledMods);
	}

	public boolean isEmpty() {
		return projects.isEmpty();
	}

	private static @Nullable String modIdAt(@Nullable String path) {
		try {
			return path == null || !Files.isRegularFile(Path.of(path)) ? null : ModJars.modIdOf(Path.of(path));
		} catch (InvalidPathException e) {
			return null;
		}
	}

	private static @Nullable String sha1(Path jar) {
		try (InputStream in = Files.newInputStream(jar)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			byte[] buffer = new byte[64 << 10];
			for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
				digest.update(buffer, 0, n);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException e) {
			return null;
		}
	}

	private static boolean blank(@Nullable String value) {
		return value == null || value.isBlank();
	}
}
