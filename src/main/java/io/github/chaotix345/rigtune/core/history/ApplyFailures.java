package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyExecutor;
import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// The ops the last helper run didn't apply, from last-apply.json (docs/v0.3/SPEC.md 3e, review B-M1). FAILED ones stay
// staged and are retried at the next exit; ABANDONED ones were dropped. An op in last-apply.json carries the attempt count
// from before that run, so the run was attempt attempts + 1 of MAX_ATTEMPTS. Reasons are the helper's own (English)
// messages, with the instance's folders cut from the paths in them.
public final class ApplyFailures {
	public static final int MAX_ATTEMPTS = ApplyExecutor.MAX_FAILED_RUNS;

	// file: the jar (enable: the new one) or the patched file; modId: an enable's mod id, else null.
	public record Failure(String opId, ApplyResult.Status status, PendingActions.Type type, String modId, String file, String reason, int attempt) {
		public boolean abandoned() {
			return status == ApplyResult.Status.ABANDONED;
		}
	}

	private ApplyFailures() {
	}

	// dirs: folders whose paths are shown as bare names (the mods and config folders).
	public static List<Failure> of(ApplyResult result, List<Path> dirs) {
		if (result == null) {
			return List.of();
		}
		List<String> prefixes = prefixes(dirs);
		List<Failure> out = new ArrayList<>();
		for (ApplyResult.OpResult r : result.results()) {
			if (r == null || r.op() == null || r.status() != ApplyResult.Status.FAILED && r.status() != ApplyResult.Status.ABANDONED) {
				continue;
			}
			Op op = r.op();
			String file = op.type() == PendingActions.Type.ENABLE_FILE ? name(op.to()) : name(op.path());
			String modId = op.type() == PendingActions.Type.ENABLE_FILE ? op.modId() : null;
			out.add(new Failure(op.id(), r.status(), op.type(), modId, file, shorten(r.message(), prefixes), op.attempts() + 1));
		}
		return out;
	}

	public static Map<String, Failure> byOpId(ApplyResult result, List<Path> dirs) {
		Map<String, Failure> out = new LinkedHashMap<>();
		for (Failure failure : of(result, dirs)) {
			if (failure.opId() != null) {
				out.put(failure.opId(), failure);
			}
		}
		return out;
	}

	// One line for latest.log (English, like the rest of the log). finishedAt: when that helper run finished (the first
	// 0.3 start may log a run 0.1.x or 0.2.x left long ago).
	public static String warnLine(Failure f, String finishedAt) {
		String what = f.type() + " " + (f.modId() != null ? f.modId() + " (" + f.file() + ")" : f.file());
		return f.abandoned()
				? "RigTune's helper dropped a change (run finished " + finishedAt + "): " + what + ": " + f.reason()
				: "RigTune's helper couldn't apply a change (run finished " + finishedAt + ", attempt " + f.attempt() + " of " + MAX_ATTEMPTS
						+ "; it's retried at the next exit): " + what + ": " + f.reason();
	}

	private static List<String> prefixes(List<Path> dirs) {
		List<String> out = new ArrayList<>();
		for (Path dir : dirs == null ? List.<Path>of() : dirs) {
			if (dir == null) {
				continue;
			}
			String text = dir.toAbsolutePath().normalize().toString();
			out.add(text + dir.getFileSystem().getSeparator());
			out.add(text.replace('\\', '/') + "/");
		}
		out.sort(Comparator.comparingInt(String::length).reversed());
		return out.stream().distinct().toList();
	}

	private static String shorten(String message, List<String> prefixes) {
		if (message == null || message.isBlank()) {
			return "?";
		}
		String out = message.replaceAll("\\s*\\R\\s*", " ").strip();
		for (String prefix : prefixes) {
			out = out.replace(prefix, "");
		}
		return out;
	}

	private static String name(String path) {
		return path == null ? "?" : Objects.requireNonNullElse(HistoryUpdates.fileName(path), "?");
	}
}
