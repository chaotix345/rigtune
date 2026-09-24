package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public enum Type {
		ENABLE_FILE, DISABLE_FILE, PATCH_JSON
	}

	// id: unique per staged op. group: ops sharing one are applied all-or-nothing (an update is {disable old, enable new}).
	// modId: the fabric.mod.json id of the jar an ENABLE_FILE op brings in.
	public record Op(Type type, String from, String to, String path, Map<String, String> patches, String id, String group, String modId) {
		public Op {
			patches = patches == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(patches));
		}

		public Op(Type type, String from, String to, String path, Map<String, String> patches) {
			this(type, from, to, path, patches, null, null, null);
		}

		public static Op enableFile(Path from, Path to) {
			return new Op(Type.ENABLE_FILE, from.toString(), to.toString(), null, null, newId(), null, null);
		}

		public static Op disableFile(Path path) {
			return new Op(Type.DISABLE_FILE, null, null, path.toString(), null, newId(), null, null);
		}

		public static Op patchJson(Path path, Map<String, String> patches) {
			return new Op(Type.PATCH_JSON, null, null, path.toString(), patches, newId(), null, null);
		}

		public Op inGroup(String newGroup) {
			return new Op(type, from, to, path, patches, id, newGroup, modId);
		}

		public Op withModId(String newModId) {
			return new Op(type, from, to, path, patches, id, group, newModId);
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

	// Puts the ops in one new all-or-nothing group, in the given order.
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
