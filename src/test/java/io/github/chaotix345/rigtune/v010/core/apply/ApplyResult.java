package io.github.chaotix345.rigtune.v010.core.apply;

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

	public record OpResult(PendingActions.Op op, Status status, String message) {
	}

	public ApplyResult {
		results = results == null ? List.of() : List.copyOf(results);
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
