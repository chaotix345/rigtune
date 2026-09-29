package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ApplyResult(String finishedAt, List<OpResult> results) {
	public enum Status {
		OK, SKIPPED_ALREADY_DONE, FAILED,
		// Not applied, and dropped from pending.json because it can never apply (see ApplyExecutor).
		ABANDONED
	}

	// resultPath (0.2): where the file ended up (the actual .disabled name of a disable). 0.1.x readers ignore it.
	public record OpResult(PendingActions.Op op, Status status, String message, String resultPath) {
		public OpResult(PendingActions.Op op, Status status, String message) {
			this(op, status, message, null);
		}
	}

	// docs/v0.5/SPEC.md 4f: what the next start's toasts count, in changes as History and Apply count them (#21): an update's
	// disable and enable (one group, applied all-or-nothing) are one. A group is failed when an op of it FAILED (still
	// pending, retried at exit), else dropped when one was ABANDONED, else applied (OK or done already).
	public record Counts(int applied, int failed, int dropped) {
		public int total() {
			return applied + failed + dropped;
		}
	}

	public ApplyResult {
		results = results == null ? List.of() : List.copyOf(results);
	}

	public Counts counts() {
		Map<String, List<OpResult>> groups = new LinkedHashMap<>();
		for (int i = 0; i < results.size(); i++) {
			OpResult r = results.get(i);
			if (r != null) {
				String group = r.op() == null ? null : r.op().group();
				groups.computeIfAbsent(group != null ? "group:" + group : "op:" + i, k -> new ArrayList<>()).add(r);
			}
		}
		int applied = 0;
		int failed = 0;
		int dropped = 0;
		for (List<OpResult> group : groups.values()) {
			int changes = changes(group);
			if (group.stream().anyMatch(r -> r.status() == null || r.status() == Status.FAILED)) {
				failed += changes;
			} else if (group.stream().anyMatch(r -> r.status() == Status.ABANDONED)) {
				dropped += changes;
			} else {
				applied += changes;
			}
		}
		return new Counts(applied, failed, dropped);
	}

	// As LauncherRepair.modChanges: a group's disables and enables pair up; a config patch is a change of its own.
	private static int changes(List<OpResult> group) {
		int enables = 0;
		int disables = 0;
		int other = 0;
		for (OpResult r : group) {
			PendingActions.Type type = r.op() == null ? null : r.op().type();
			if (type == PendingActions.Type.ENABLE_FILE) {
				enables++;
			} else if (type == PendingActions.Type.DISABLE_FILE) {
				disables++;
			} else {
				other++;
			}
		}
		return Math.max(enables, disables) + other;
	}

	public boolean allSucceeded() {
		return results.stream().allMatch(r -> r.status() == Status.OK || r.status() == Status.SKIPPED_ALREADY_DONE);
	}

	// Every op that wasn't applied, abandoned ones included.
	public List<PendingActions.Op> failedOps() {
		return results.stream().filter(r -> r.status() == Status.FAILED || r.status() == Status.ABANDONED).map(OpResult::op).toList();
	}

	public List<PendingActions.Op> abandonedOps() {
		return results.stream().filter(r -> r.status() == Status.ABANDONED).map(OpResult::op).toList();
	}

	public static Path defaultPath(Path configDir) {
		return configDir.resolve("rigtune").resolve("last-apply.json");
	}

	public static ApplyResult load(Path file) throws IOException {
		try {
			ApplyResult result = PendingActions.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ApplyResult.class);
			if (result == null) {
				throw new IOException("Empty result: " + file);
			}
			return result;
		} catch (JsonParseException e) {
			throw new IOException("Malformed result " + file + ": " + e.getMessage(), e);
		}
	}

	public void save(Path file) throws IOException {
		AtomicFiles.writeString(file, PendingActions.GSON.toJson(this));
	}
}
