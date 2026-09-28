package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

	// docs/v0.5/SPEC.md 4f: what the next start's toasts count. applied: OK or done already; failed: FAILED (still pending,
	// retried at exit); dropped: ABANDONED.
	public record Counts(int applied, int failed, int dropped) {
		public int total() {
			return applied + failed + dropped;
		}
	}

	public ApplyResult {
		results = results == null ? List.of() : List.copyOf(results);
	}

	public Counts counts() {
		int applied = 0;
		int failed = 0;
		int dropped = 0;
		for (OpResult r : results) {
			if (r == null) {
				continue;
			}
			switch (r.status() == null ? Status.FAILED : r.status()) {
				case OK, SKIPPED_ALREADY_DONE -> applied++;
				case FAILED -> failed++;
				case ABANDONED -> dropped++;
			}
		}
		return new Counts(applied, failed, dropped);
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
