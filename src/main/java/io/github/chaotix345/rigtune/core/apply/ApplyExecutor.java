package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ApplyExecutor {
	public static final int DEFAULT_ATTEMPTS = 10;
	public static final long DEFAULT_RETRY_DELAY_MILLIS = 300;

	interface Mover {
		void move(Path from, Path to) throws IOException;
	}

	private final int attempts;
	private final long retryDelayMillis;
	private final Mover mover;

	public ApplyExecutor() {
		this(DEFAULT_ATTEMPTS, DEFAULT_RETRY_DELAY_MILLIS);
	}

	public ApplyExecutor(int attempts, long retryDelayMillis) {
		this(attempts, retryDelayMillis, (from, to) -> Files.move(from, to));
	}

	ApplyExecutor(int attempts, long retryDelayMillis, Mover mover) {
		this.attempts = Math.max(1, attempts);
		this.retryDelayMillis = retryDelayMillis;
		this.mover = mover;
	}

	// Callers hold the apply lock. pendingFile is re-read before it is rewritten, and only the ops run here
	// that succeeded are removed from it, so ops staged after `plan` was read survive.
	public ApplyResult run(PendingActions plan, Path pendingFile) throws IOException {
		ApplyResult result = new ApplyResult(Instant.now().toString(), execute(plan));
		writeRemaining(plan, pendingFile, result);
		Path resultFile = plan.configDir() != null
				? ApplyResult.defaultPath(Path.of(plan.configDir()))
				: pendingFile.resolveSibling("last-apply.json");
		result.save(resultFile);
		return result;
	}

	private static void writeRemaining(PendingActions plan, Path pendingFile, ApplyResult result) throws IOException {
		List<Op> done = result.results().stream().filter(r -> r.status() != Status.FAILED).map(OpResult::op).toList();
		PendingActions base = plan;
		if (Files.exists(pendingFile)) {
			try {
				base = PendingActions.load(pendingFile);
			} catch (IOException e) {
				base = plan;
			}
		}
		List<Op> remaining = base.ops().stream().filter(op -> done.stream().noneMatch(d -> d != null && d.sameOp(op))).toList();
		if (remaining.isEmpty()) {
			Files.deleteIfExists(pendingFile);
		} else {
			base.withOps(remaining).save(pendingFile);
		}
	}

	List<OpResult> execute(PendingActions plan) {
		Path modsDir = dir(plan.modsDir());
		Path configDir = dir(plan.configDir());
		List<Op> ops = plan.ops();
		Map<String, List<Integer>> groups = new LinkedHashMap<>();
		for (int i = 0; i < ops.size(); i++) {
			Op op = ops.get(i);
			String key = op != null && op.group() != null ? "group:" + op.group() : "op:" + i;
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
		}
		OpResult[] out = new OpResult[ops.size()];
		for (List<Integer> members : groups.values()) {
			runGroup(ops, members, modsDir, configDir, out);
		}
		return Arrays.asList(out);
	}

	private static Path dir(String value) {
		try {
			return value == null ? null : Path.of(value);
		} catch (InvalidPathException e) {
			return null;
		}
	}

	private record Undo(int index, Path moved, Path back) {
	}

	private record Applied(OpResult result, Undo undo) {
	}

	// A group is all-or-nothing: disables run first, and an enable runs only once every disable in the group is
	// OK or already done. When an op fails, the rest are skipped and the earlier renames are undone.
	private void runGroup(List<Op> ops, List<Integer> members, Path modsDir, Path configDir, OpResult[] out) {
		List<Integer> order = new ArrayList<>(members);
		order.sort(Comparator.comparingInt(i -> rank(ops.get(i))));

		String[] problems = new String[ops.size()];
		boolean refused = false;
		for (int i : order) {
			problems[i] = problem(ops.get(i), modsDir, configDir);
			refused |= problems[i] != null;
		}
		if (refused) {
			for (int i : order) {
				out[i] = new OpResult(ops.get(i), Status.FAILED,
						problems[i] != null ? "Refused: " + problems[i] : "Not applied: another change in its group was refused");
			}
			return;
		}

		List<Undo> undos = new ArrayList<>();
		for (int k = 0; k < order.size(); k++) {
			int i = order.get(k);
			Op op = ops.get(i);
			Applied applied = apply(op, i);
			out[i] = applied.result();
			if (applied.result().status() != Status.FAILED) {
				if (applied.undo() != null) {
					undos.add(applied.undo());
				}
				continue;
			}
			String reason = describe(op) + " failed";
			for (int rest : order.subList(k + 1, order.size())) {
				out[rest] = new OpResult(ops.get(rest), Status.FAILED, "Not applied because " + reason);
			}
			for (int u = undos.size() - 1; u >= 0; u--) {
				Undo undo = undos.get(u);
				out[undo.index()] = rollback(ops.get(undo.index()), undo, reason);
			}
			return;
		}
	}

	private static int rank(Op op) {
		if (op == null || op.type() == null) {
			return 3;
		}
		return switch (op.type()) {
			case DISABLE_FILE -> 0;
			case ENABLE_FILE -> 1;
			case PATCH_JSON -> 2;
		};
	}

	private static String describe(Op op) {
		return switch (op.type()) {
			case ENABLE_FILE -> "enabling " + fileName(op.to());
			case DISABLE_FILE -> "disabling " + fileName(op.path());
			case PATCH_JSON -> "patching " + fileName(op.path());
		};
	}

	private static String fileName(String path) {
		Path name = Path.of(path).getFileName();
		return name == null ? path : name.toString();
	}

	private OpResult rollback(Op op, Undo undo, String reason) {
		IOException last = null;
		for (int attempt = 1; attempt <= attempts; attempt++) {
			try {
				if (Files.exists(undo.back()) || !Files.exists(undo.moved())) {
					break;
				}
				mover.move(undo.moved(), undo.back());
				return new OpResult(op, Status.FAILED, "Rolled back because " + reason);
			} catch (IOException e) {
				last = e;
			}
			if (attempt < attempts && !sleep()) {
				break;
			}
		}
		return new OpResult(op, Status.FAILED, "Rollback failed (" + (last == null ? "the original name is taken" : last)
				+ "); " + undo.moved().getFileName() + " was left as it is after " + reason);
	}

	static String problem(Op op, Path modsDir, Path configDir) {
		if (op == null || op.type() == null) {
			return "unknown operation";
		}
		try {
			return containmentProblem(op, modsDir, configDir);
		} catch (InvalidPathException e) {
			return "invalid path: " + e.getMessage();
		}
	}

	// Mod files must sit directly in the plan's mods folder and config patches inside its config folder.
	static String containmentProblem(Op op, Path modsDir, Path configDir) {
		return switch (op.type()) {
			case ENABLE_FILE -> {
				if (op.from() == null || op.to() == null) {
					yield "missing from/to";
				}
				Path from = Path.of(op.from());
				Path to = Path.of(op.to());
				if (!SafeFileNames.isDirectChild(modsDir, from) || !SafeFileNames.isDirectChild(modsDir, to)) {
					yield op.from() + " -> " + op.to() + " is not directly inside the mods folder " + modsDir;
				}
				yield SafeFileNames.isSafeJarName(to.getFileName().toString()) ? null : to.getFileName() + " is not a safe .jar name";
			}
			case DISABLE_FILE -> op.path() == null ? "missing path"
					: SafeFileNames.isDirectChild(modsDir, Path.of(op.path())) ? null
					: op.path() + " is not directly inside the mods folder " + modsDir;
			case PATCH_JSON -> op.path() == null ? "missing path"
					: SafeFileNames.isInside(configDir, Path.of(op.path())) ? null
					: op.path() + " is not inside the config folder " + configDir;
		};
	}

	private interface Step {
		Applied run() throws IOException;
	}

	private Applied apply(Op op, int index) {
		return switch (op.type()) {
			case ENABLE_FILE -> retrying(op, () -> enable(op, index));
			case DISABLE_FILE -> retrying(op, () -> disable(op, index));
			case PATCH_JSON -> retrying(op, () -> new Applied(patchJson(op), null));
		};
	}

	private Applied retrying(Op op, Step step) {
		IOException last = null;
		for (int attempt = 1; attempt <= attempts; attempt++) {
			try {
				return step.run();
			} catch (IOException e) {
				last = e;
			} catch (RuntimeException e) {
				return new Applied(new OpResult(op, Status.FAILED, e.toString()), null);
			}
			if (attempt < attempts && !sleep()) {
				break;
			}
		}
		return new Applied(new OpResult(op, Status.FAILED, "Gave up after " + attempts + " attempt(s): " + last), null);
	}

	private boolean sleep() {
		try {
			Thread.sleep(retryDelayMillis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private Applied enable(Op op, int index) throws IOException {
		Path from = Path.of(op.from());
		Path to = Path.of(op.to());
		if (!Files.exists(from)) {
			return new Applied(Files.exists(to)
					? new OpResult(op, Status.SKIPPED_ALREADY_DONE, to.getFileName() + " is already enabled")
					: new OpResult(op, Status.FAILED, "Missing " + from), null);
		}
		if (Files.exists(to)) {
			return new Applied(new OpResult(op, Status.FAILED, to + " already exists; not overwriting it"), null);
		}
		mover.move(from, to);
		return new Applied(new OpResult(op, Status.OK, "Enabled " + to.getFileName()), new Undo(index, to, from));
	}

	private Applied disable(Op op, int index) throws IOException {
		Path path = Path.of(op.path());
		if (!Files.exists(path)) {
			return new Applied(new OpResult(op, Status.SKIPPED_ALREADY_DONE, path.getFileName() + " is already gone"), null);
		}
		Path target = disabledTarget(path);
		mover.move(path, target);
		return new Applied(new OpResult(op, Status.OK, "Disabled " + path.getFileName() + " -> " + target.getFileName()),
				new Undo(index, target, path));
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
