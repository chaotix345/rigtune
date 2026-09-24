package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record PendingActions(String createdAt, long gamePid, String modsDir, String configDir, List<Op> ops) {
	public static final String PENDING_SUFFIX = ".rigtune-pending";
	static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public enum Type {
		ENABLE_FILE, DISABLE_FILE, PATCH_JSON
	}

	public record Op(Type type, String from, String to, String path, Map<String, String> patches) {
		public Op {
			patches = patches == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(patches));
		}

		public static Op enableFile(Path from, Path to) {
			return new Op(Type.ENABLE_FILE, from.toString(), to.toString(), null, null);
		}

		public static Op disableFile(Path path) {
			return new Op(Type.DISABLE_FILE, null, null, path.toString(), null);
		}

		public static Op patchJson(Path path, Map<String, String> patches) {
			return new Op(Type.PATCH_JSON, null, null, path.toString(), patches);
		}
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
