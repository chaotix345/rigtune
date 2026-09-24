package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ApplyExecutor {
	public static final int DEFAULT_ATTEMPTS = 10;
	public static final long DEFAULT_RETRY_DELAY_MILLIS = 300;

	private final int attempts;
	private final long retryDelayMillis;

	public ApplyExecutor() {
		this(DEFAULT_ATTEMPTS, DEFAULT_RETRY_DELAY_MILLIS);
	}

	public ApplyExecutor(int attempts, long retryDelayMillis) {
		this.attempts = Math.max(1, attempts);
		this.retryDelayMillis = retryDelayMillis;
	}

	public ApplyResult run(PendingActions plan, Path pendingFile) throws IOException {
		List<OpResult> results = new ArrayList<>();
		for (Op op : plan.ops()) {
			results.add(execute(op));
		}
		ApplyResult result = new ApplyResult(Instant.now().toString(), results);
		if (result.allSucceeded()) {
			Files.deleteIfExists(pendingFile);
		} else {
			plan.withOps(result.failedOps()).save(pendingFile);
		}
		Path resultFile = plan.configDir() != null
				? ApplyResult.defaultPath(Path.of(plan.configDir()))
				: pendingFile.resolveSibling("last-apply.json");
		result.save(resultFile);
		return result;
	}

	OpResult execute(Op op) {
		if (op == null || op.type() == null) {
			return new OpResult(op, Status.FAILED, "Unknown operation");
		}
		return switch (op.type()) {
			case ENABLE_FILE -> retrying(op, () -> enable(op));
			case DISABLE_FILE -> retrying(op, () -> disable(op));
			case PATCH_JSON -> retrying(op, () -> patchJson(op));
		};
	}

	private interface Step {
		OpResult run() throws IOException;
	}

	private OpResult retrying(Op op, Step step) {
		IOException last = null;
		for (int attempt = 1; attempt <= attempts; attempt++) {
			try {
				return step.run();
			} catch (IOException e) {
				last = e;
			} catch (RuntimeException e) {
				return new OpResult(op, Status.FAILED, e.toString());
			}
			if (attempt < attempts) {
				try {
					Thread.sleep(retryDelayMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return new OpResult(op, Status.FAILED, "Gave up after " + attempts + " attempt(s): " + last);
	}

	private static OpResult enable(Op op) throws IOException {
		Path from = Path.of(op.from());
		Path to = Path.of(op.to());
		if (!Files.exists(from)) {
			return Files.exists(to)
					? new OpResult(op, Status.SKIPPED_ALREADY_DONE, to.getFileName() + " is already enabled")
					: new OpResult(op, Status.FAILED, "Missing " + from);
		}
		if (Files.exists(to)) {
			return new OpResult(op, Status.FAILED, to + " already exists; not overwriting it");
		}
		Files.move(from, to);
		return new OpResult(op, Status.OK, "Enabled " + to.getFileName());
	}

	private static OpResult disable(Op op) throws IOException {
		Path path = Path.of(op.path());
		if (!Files.exists(path)) {
			return new OpResult(op, Status.SKIPPED_ALREADY_DONE, path.getFileName() + " is already gone");
		}
		Path target = disabledTarget(path);
		Files.move(path, target);
		return new OpResult(op, Status.OK, "Disabled " + path.getFileName() + " -> " + target.getFileName());
	}

	static Path disabledTarget(Path path) {
		String base = path.getFileName() + ".disabled";
		Path candidate = path.resolveSibling(base);
		for (int i = 1; Files.exists(candidate); i++) {
			candidate = path.resolveSibling(base + "." + i);
		}
		return candidate;
	}

	private static OpResult patchJson(Op op) throws IOException {
		Path path = Path.of(op.path());
		Map<String, String> patches = op.patches() == null ? Map.of() : op.patches();
		return SodiumConfigPatcher.patchFile(path, patches)
				? new OpResult(op, Status.OK, "Patched " + patches.size() + " value(s) in " + path.getFileName())
				: new OpResult(op, Status.SKIPPED_ALREADY_DONE, path.getFileName() + " already has these values");
	}
}
