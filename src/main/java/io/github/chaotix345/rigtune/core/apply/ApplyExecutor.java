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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class ApplyExecutor {
	public static final int DEFAULT_ATTEMPTS = 10;
	public static final long DEFAULT_RETRY_DELAY_MILLIS = 300;
	public static final int MAX_FAILED_RUNS = 3;

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
	// that succeeded are removed from it, so ops staged after `plan` was read survive. The mods and config folders
	// come from where pendingFile is; the ones the plan records are informational only.
	public ApplyResult run(PendingActions plan, Path pendingFile) throws IOException {
		Path configDir = InstanceDirs.configDirOf(pendingFile);
		Path modsDir = InstanceDirs.modsDirOf(pendingFile);
		ApplyResult result = new ApplyResult(Instant.now().toString(), giveUpOnRepeatFailures(execute(plan, modsDir, configDir)));
		writeRemaining(plan, pendingFile, result, modsDir);
		result.save(ApplyResult.defaultPath(configDir));
		return result;
	}

	// A group with an op that has now failed in MAX_FAILED_RUNS helper runs is abandoned as a whole, so a change that
	// can never apply doesn't come back at every exit.
	static List<OpResult> giveUpOnRepeatFailures(List<OpResult> results) {
		Set<String> givenUp = new HashSet<>();
		for (int i = 0; i < results.size(); i++) {
			OpResult r = results.get(i);
			if (r.status() == Status.FAILED && r.op() != null && r.op().attempts() + 1 >= MAX_FAILED_RUNS) {
				givenUp.add(groupKey(r.op(), i));
			}
		}
		List<OpResult> out = new ArrayList<>(results);
		for (int i = 0; i < out.size(); i++) {
			OpResult r = out.get(i);
			if (r.status() == Status.FAILED && r.op() != null && givenUp.contains(groupKey(r.op(), i))) {
				out.set(i, new OpResult(r.op(), Status.ABANDONED, "Gave up after " + MAX_FAILED_RUNS + " failed attempts: " + r.message()));
			}
		}
		return out;
	}

	private static String groupKey(Op op, int index) {
		return op.group() != null ? "group:" + op.group() : "op:" + index;
	}

	// Ops that are done or abandoned leave the plan, and failed ones count another attempt. An abandoned enable's
	// download is renamed to .rigtune-superseded.
	private static void writeRemaining(PendingActions plan, Path pendingFile, ApplyResult result, Path modsDir) throws IOException {
		List<Op> leaving = result.results().stream().filter(r -> r.status() != Status.FAILED).map(OpResult::op).filter(Objects::nonNull).toList();
		List<Op> failed = result.results().stream().filter(r -> r.status() == Status.FAILED).map(OpResult::op).filter(Objects::nonNull).toList();
		PendingActions base = plan;
		if (Files.exists(pendingFile)) {
			try {
				base = PendingActions.load(pendingFile);
			} catch (IOException e) {
				base = plan;
			}
		}
		List<Op> remaining = new ArrayList<>();
		for (Op op : base.ops()) {
			if (leaving.stream().anyMatch(d -> d.sameOp(op))) {
				continue;
			}
			remaining.add(op != null && failed.stream().anyMatch(f -> f.sameOp(op)) ? op.withAttempts(op.attempts() + 1) : op);
		}
		if (remaining.isEmpty()) {
			Files.deleteIfExists(pendingFile);
		} else {
			base.withOps(remaining).save(pendingFile);
		}
		for (Op op : result.abandonedOps()) {
			if (op != null && remaining.stream().noneMatch(o -> o != null && op.from() != null && op.from().equals(o.from()))) {
				PendingActions.retireDownload(op, modsDir);
			}
		}
	}

	List<OpResult> execute(PendingActions plan, Path modsDir, Path configDir) {
		List<Op> ops = plan.ops();
		Map<String, List<Integer>> groups = new LinkedHashMap<>();
		for (int i = 0; i < ops.size(); i++) {
			Op op = ops.get(i);
			String key = op != null && op.group() != null ? "group:" + op.group() : "op:" + i;
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
		}
		OpResult[] out = new OpResult[ops.size()];
		InstalledJars installed = new InstalledJars(modsDir);
		for (List<Integer> members : groups.values()) {
			runGroup(ops, members, modsDir, configDir, installed, out);
			members.forEach(i -> installed.forget(ops.get(i)));
		}
		return Arrays.asList(out);
	}

	// The mod ids of the jars in the mods folder, each read once; forget() drops the names a group may have renamed.
	private static final class InstalledJars {
		private final Path modsDir;
		private final Map<String, String> idsByName = new HashMap<>();

		InstalledJars(Path modsDir) {
			this.modsDir = modsDir;
		}

		// The first *.jar (other than `ignored`) whose fabric.mod.json id is modId, or null.
		String withModId(String modId, Set<String> ignored) {
			List<Path> jars;
			try (Stream<Path> files = Files.list(modsDir)) {
				jars = files.filter(f -> f.getFileName().toString().endsWith(".jar") && Files.isRegularFile(f)).sorted().toList();
			} catch (IOException e) {
				return null;
			}
			for (Path jar : jars) {
				String name = jar.getFileName().toString();
				if (!ignored.contains(name) && modId.equals(idsByName.computeIfAbsent(name, n -> idOf(jar)))) {
					return name;
				}
			}
			return null;
		}

		private static String idOf(Path jar) {
			try {
				return Objects.requireNonNullElse(ModJars.readModId(jar), "");
			} catch (IOException e) {
				return "";
			}
		}

		void forget(Op op) {
			if (op == null) {
				return;
			}
			for (String path : Arrays.asList(op.from(), op.to(), op.path())) {
				try {
					if (path != null) {
						idsByName.remove(fileName(path));
					}
				} catch (InvalidPathException ignored) {
				}
			}
		}
	}

	// An enable whose mod is already installed another way (the launcher updated it meanwhile, or it was added by
	// hand) would load the mod twice, and Fabric then refuses to start. Jars this group disables don't count.
	private static String duplicateProblem(List<Op> ops, List<Integer> order, InstalledJars installed) {
		Set<String> disabled = new HashSet<>();
		for (int i : order) {
			if (ops.get(i).type() == PendingActions.Type.DISABLE_FILE) {
				disabled.add(fileName(ops.get(i).path()));
			}
		}
		for (int i : order) {
			Op op = ops.get(i);
			if (op.type() != PendingActions.Type.ENABLE_FILE || op.modId() == null || !Files.exists(Path.of(op.from()))) {
				continue;
			}
			String existing = installed.withModId(op.modId(), disabled);
			if (existing != null) {
				return "mod " + op.modId() + " is already installed as " + existing + ", so enabling " + fileName(op.to())
						+ " would load it twice; its download is renamed to " + PendingActions.SUPERSEDED_SUFFIX;
			}
		}
		return null;
	}

	private record Undo(int index, Path moved, Path back) {
	}

	private record Applied(OpResult result, Undo undo) {
	}

	// A group is all-or-nothing: disables run first, and an enable runs only once every disable in the group is
	// OK or already done. When an op fails, the rest are skipped and the earlier renames are undone.
	private void runGroup(List<Op> ops, List<Integer> members, Path modsDir, Path configDir, InstalledJars installed, OpResult[] out) {
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
		String duplicate = duplicateProblem(ops, order, installed);
		if (duplicate != null) {
			for (int i : order) {
				out[i] = new OpResult(ops.get(i), Status.ABANDONED, "Dropped: " + duplicate);
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
			case PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES -> 2;
		};
	}

	private static String describe(Op op) {
		return switch (op.type()) {
			case ENABLE_FILE -> "enabling " + fileName(op.to());
			case DISABLE_FILE -> "disabling " + fileName(op.path());
			case PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES -> "patching " + fileName(op.path());
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

	// Mod files must sit directly in the instance's mods folder and config patches inside its config folder.
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
			case PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES -> op.path() == null ? "missing path"
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
			case PATCH_TOML -> retrying(op, () -> new Applied(patchConfig(op, TomlConfigPatcher.patchFile(Path.of(op.path()), patchesOf(op))), null));
			case PATCH_PROPERTIES -> retrying(op, () -> new Applied(patchConfig(op, PropertiesConfigPatcher.patchFile(Path.of(op.path()), patchesOf(op))), null));
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

	private static Map<String, String> patchesOf(Op op) {
		return op.patches() == null ? Map.of() : op.patches();
	}

	private static OpResult patchConfig(Op op, boolean changed) {
		Path path = Path.of(op.path());
		return changed
				? new OpResult(op, Status.OK, "Patched " + patchesOf(op).size() + " value(s) in " + path.getFileName())
				: new OpResult(op, Status.SKIPPED_ALREADY_DONE, path.getFileName() + " already has these values");
	}
}
