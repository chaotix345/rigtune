package io.github.chaotix345.rigtune.v010.core.apply;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record PendingActions(String createdAt, long gamePid, String modsDir, String configDir, List<Op> ops) {
	public static final String PENDING_SUFFIX = ".rigtune-pending";
	public static final String SUPERSEDED_SUFFIX = ".rigtune-superseded";
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public enum Type {
		ENABLE_FILE, DISABLE_FILE, PATCH_JSON
	}

	// id: unique per staged op. group: ops sharing one are applied all-or-nothing (an update is {disable old, enable new}).
	// modId: the fabric.mod.json id of the jar an ENABLE_FILE op brings in. attempts: helper runs this op has failed in.
	public record Op(Type type, String from, String to, String path, Map<String, String> patches, String id, String group, String modId,
			int attempts) {
		public Op {
			patches = patches == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(patches));
		}

		public Op(Type type, String from, String to, String path, Map<String, String> patches) {
			this(type, from, to, path, patches, null, null, null, 0);
		}

		public static Op enableFile(Path from, Path to) {
			return new Op(Type.ENABLE_FILE, from.toString(), to.toString(), null, null, newId(), null, null, 0);
		}

		public static Op disableFile(Path path) {
			return new Op(Type.DISABLE_FILE, null, null, path.toString(), null, newId(), null, null, 0);
		}

		public static Op patchJson(Path path, Map<String, String> patches) {
			return new Op(Type.PATCH_JSON, null, null, path.toString(), patches, newId(), null, null, 0);
		}

		public Op inGroup(String newGroup) {
			return new Op(type, from, to, path, patches, id, newGroup, modId, attempts);
		}

		public Op withModId(String newModId) {
			return new Op(type, from, to, path, patches, id, group, newModId, attempts);
		}

		public Op withAttempts(int newAttempts) {
			return new Op(type, from, to, path, patches, id, group, modId, newAttempts);
		}

		// Same file change, whatever its id or group.
		public boolean sameChange(Op other) {
			return other != null && type == other.type && Objects.equals(from, other.from) && Objects.equals(to, other.to)
					&& Objects.equals(path, other.path) && Objects.equals(patches, other.patches);
		}

		// The same staged op: by id when both have one (plans written before ids existed have none).
		public boolean sameOp(Op other) {
			if (other == null) {
				return false;
			}
			return id != null && other.id != null ? id.equals(other.id) : equals(other);
		}
	}

	public static String newId() {
		return UUID.randomUUID().toString();
	}

	public static List<Op> group(Op... ops) {
		return group(List.of(ops));
	}

	public static List<Op> group(List<Op> ops) {
		String group = newId();
		List<Op> out = new ArrayList<>(ops.size());
		for (Op op : ops) {
			out.add(op.inGroup(group));
		}
		return List.copyOf(out);
	}

	public PendingActions {
		ops = ops == null ? List.of() : List.copyOf(ops);
	}

	public static PendingActions create(long gamePid, Path modsDir, Path configDir, List<Op> ops) {
		return new PendingActions(Instant.now().toString(), gamePid, modsDir.toString(), configDir.toString(), ops);
	}

	public static Path defaultPath(Path configDir) {
		return configDir.resolve("rigtune").resolve("pending.json");
	}

	public PendingActions withOps(List<Op> newOps) {
		return new PendingActions(createdAt, gamePid, modsDir, configDir, newOps);
	}

	// The plan as seen from this instance's folders (derived from where pending.json is): they are recorded afresh, for
	// information only, and ops outside them are dropped, since they belong to the instance this one was copied from.
	public PendingActions relocated(Path newModsDir, Path newConfigDir) {
		List<Op> kept = ops.stream().filter(op -> ApplyExecutor.problem(op, newModsDir, newConfigDir) == null).toList();
		return new PendingActions(createdAt, gamePid, newModsDir.toString(), newConfigDir.toString(), kept);
	}

	public record Merged(PendingActions plan, List<Path> superseded) {
	}

	// Adds staged ops. An op that repeats a staged change is dropped and the two groups are joined. An ENABLE_FILE
	// whose mod id already has a staged ENABLE_FILE replaces it, taking over the rest of its group (such as the
	// update's disable), and the replaced op's pending jar is returned for the caller to retire.
	public Merged merge(List<Op> incoming) {
		List<Op> merged = new ArrayList<>(ops);
		List<Path> superseded = new ArrayList<>();
		for (Op op : incoming) {
			Op same = merged.stream().filter(existing -> existing.sameChange(op)).findFirst().orElse(null);
			if (same != null) {
				if (op.group() != null && !op.group().equals(same.group())) {
					regroup(merged, same, op.group());
				}
				continue;
			}
			Op next = op;
			if (op.type() == Type.ENABLE_FILE && op.modId() != null) {
				for (Op old : List.copyOf(merged)) {
					if (old.type() != Type.ENABLE_FILE || !op.modId().equals(old.modId())) {
						continue;
					}
					merged.remove(old);
					if (old.group() != null) {
						if (next.group() == null) {
							next = next.inGroup(old.group());
						} else {
							regroup(merged, old, next.group());
						}
					}
					if (old.from() != null && !old.from().equals(op.from())) {
						superseded.add(Path.of(old.from()));
					}
				}
			}
			merged.add(next);
		}
		List<Path> retire = superseded.stream()
				.filter(p -> merged.stream().noneMatch(o -> p.toString().equals(o.from())))
				.distinct()
				.toList();
		return new Merged(withOps(merged), retire);
	}

	private static void regroup(List<Op> ops, Op member, String group) {
		for (int i = 0; i < ops.size(); i++) {
			Op op = ops.get(i);
			if (op == member || (member.group() != null && member.group().equals(op.group()))) {
				ops.set(i, op.inGroup(group));
			}
		}
	}

	// Leaves a replaced download inert: x.jar.rigtune-pending becomes x.jar.rigtune-superseded (never deleted).
	public static Path retire(Path pendingJar) throws IOException {
		if (!Files.exists(pendingJar)) {
			return null;
		}
		String name = pendingJar.getFileName().toString();
		String base = name.endsWith(PENDING_SUFFIX) ? name.substring(0, name.length() - PENDING_SUFFIX.length()) : name;
		Path target = pendingJar.resolveSibling(base + SUPERSEDED_SUFFIX);
		for (int i = 1; Files.exists(target); i++) {
			target = pendingJar.resolveSibling(base + SUPERSEDED_SUFFIX + "." + i);
		}
		Files.move(pendingJar, target);
		return target;
	}

	// Retires the download of a dropped ENABLE_FILE op: only a .rigtune-pending file directly in modsDir.
	static void retireDownload(Op op, Path modsDir) {
		if (op == null || op.type() != Type.ENABLE_FILE || op.from() == null || !op.from().endsWith(PENDING_SUFFIX)) {
			return;
		}
		try {
			Path from = Path.of(op.from());
			if (SafeFileNames.isDirectChild(modsDir, from)) {
				retire(from);
			}
		} catch (IOException | InvalidPathException ignored) {
			// It stays a .rigtune-pending file, which Fabric ignores.
		}
	}

	// Cancels every staged change under the apply lock: the downloads of staged enables become .rigtune-superseded
	// (never deleted) and pending.json is deleted. Returns how many ops were dropped, or -1 if the lock is busy.
	public static int discard(Path pendingFile, Duration lockWait) throws IOException {
		try (ApplyLock lock = ApplyLock.acquire(ApplyLock.besidePlan(pendingFile), lockWait)) {
			if (lock == null) {
				return -1;
			}
			if (!Files.exists(pendingFile)) {
				return 0;
			}
			List<Op> ops;
			try {
				ops = load(pendingFile).ops();
			} catch (IOException e) {
				ops = List.of();
			}
			Path modsDir = InstanceDirs.modsDirOf(pendingFile);
			ops.forEach(op -> retireDownload(op, modsDir));
			Files.deleteIfExists(pendingFile);
			return ops.size();
		}
	}

	public static PendingActions load(Path file) throws IOException {
		try {
			PendingActions plan = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PendingActions.class);
			if (plan == null) {
				throw new IOException("Empty plan: " + file);
			}
			return plan;
		} catch (JsonParseException e) {
			throw new IOException("Malformed plan " + file + ": " + e.getMessage(), e);
		}
	}

	public void save(Path file) throws IOException {
		AtomicFiles.writeString(file, GSON.toJson(this));
	}
}
