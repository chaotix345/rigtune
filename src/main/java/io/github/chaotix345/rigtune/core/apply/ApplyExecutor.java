package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.ApplyResult.OpResult;
import io.github.chaotix345.rigtune.core.apply.ApplyResult.Status;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.UnfinishedGroups.Rename;
import io.github.chaotix345.rigtune.core.history.HistoryUpdates;
import io.github.chaotix345.rigtune.core.history.Journal;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

public final class ApplyExecutor {
	public static final int DEFAULT_ATTEMPTS = 10;
	public static final long DEFAULT_RETRY_DELAY_MILLIS = 300;
	public static final int MAX_FAILED_RUNS = 3;

	// A sharing violation (Windows denying a rename because an AV scanner, indexer or the Modrinth App briefly has
	// the jar open) gets its own, longer-lived retry policy instead of the fast fixed-delay one: real-world evidence
	// (a 27 MB jar disable that lost the race after 3 s) showed the fast policy gives up well before typical
	// contention like this clears.
	static final long SHARING_RETRY_INITIAL_MILLIS = 300;
	static final long SHARING_RETRY_MAX_MILLIS = 5000;
	static final long SHARING_RETRY_BUDGET_MILLIS = 30_000;

	interface Mover {
		void move(Path from, Path to) throws IOException;
	}

	// Injectable so tests can drive the retry loops without sleeping for real.
	interface Sleeper {
		boolean sleep(long millis);
	}

	// Injectable so tests can make reading a jar's mod id throw.
	interface ModIdReader {
		String read(Path jar) throws IOException;
	}

	private final int attempts;
	private final long retryDelayMillis;
	private final Mover mover;
	private final Sleeper sleeper;
	private final ModIdReader modIds;

	public ApplyExecutor() {
		this(DEFAULT_ATTEMPTS, DEFAULT_RETRY_DELAY_MILLIS);
	}

	public ApplyExecutor(int attempts, long retryDelayMillis) {
		this(attempts, retryDelayMillis, (from, to) -> Files.move(from, to));
	}

	ApplyExecutor(int attempts, long retryDelayMillis, Mover mover) {
		this(attempts, retryDelayMillis, mover, ApplyExecutor::realSleep);
	}

	ApplyExecutor(int attempts, long retryDelayMillis, Mover mover, Sleeper sleeper) {
		this(attempts, retryDelayMillis, mover, sleeper, ModJars::readModId);
	}

	ApplyExecutor(int attempts, long retryDelayMillis, Mover mover, Sleeper sleeper, ModIdReader modIds) {
		this.attempts = Math.max(1, attempts);
		this.retryDelayMillis = retryDelayMillis;
		this.mover = mover;
		this.sleeper = sleeper;
		this.modIds = modIds;
	}

	private static boolean realSleep(long millis) {
		try {
			Thread.sleep(millis);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	// A FileSystemException other than these three is treated as a sharing violation: on Windows, a plain
	// FileSystemException (or an AccessDeniedException, its subclass) is what Files.move throws when something else
	// has the file open, whatever the exact, locale-specific reason text says. NoSuchFile/FileAlreadyExists/
	// DirectoryNotEmpty describe a state a retry can't fix, so they keep the fast fixed-delay policy instead.
	private static boolean isSharingViolation(IOException e) {
		if (!(e instanceof FileSystemException)) {
			return false;
		}
		return !(e instanceof NoSuchFileException || e instanceof FileAlreadyExistsException || e instanceof DirectoryNotEmptyException);
	}

	// Tracks one group's (or rollback's) progress through a retry loop: fixed-delay up to `attempts` tries for an
	// ordinary IOException, or exponential backoff up to a ~30 s total budget for a sharing violation. Never mixes
	// the two budgets for one group: whichever kind the first failure was decides the policy for the rest of it.
	private final class RetryState {
		private int attempt;
		private long elapsedBackoffMillis;
		private long backoffMillis = SHARING_RETRY_INITIAL_MILLIS;
		private Boolean sharing;

		// Called after a failed attempt. Returns whether the caller should try again (having slept if so).
		boolean onFailure(IOException e) {
			attempt++;
			if (sharing == null) {
				sharing = isSharingViolation(e);
			}
			if (sharing) {
				if (elapsedBackoffMillis + backoffMillis > SHARING_RETRY_BUDGET_MILLIS) {
					return false;
				}
				if (!sleeper.sleep(backoffMillis)) {
					return false;
				}
				elapsedBackoffMillis += backoffMillis;
				backoffMillis = Math.min(backoffMillis * 2, SHARING_RETRY_MAX_MILLIS);
				return true;
			}
			return attempt < attempts && sleeper.sleep(retryDelayMillis);
		}

		int attempt() {
			return attempt;
		}
	}

	// Callers hold the apply lock. pendingFile is re-read before it is rewritten, and only the ops run here
	// that succeeded are removed from it, so ops staged after `plan` was read survive. The mods and config folders
	// come from where pendingFile is; the ones the plan records are informational only.
	// The files are written in a crash-safe order (audit L2), so a death or a failed write between two steps never makes
	// History call an applied change "Not applied": (1) the abandoned ops leave pending.json and the failed ones count a
	// run, (2) last-apply.json, (3) the done ops leave pending.json, (4) the journal. Before (2), preLaunch's reconcile
	// finds the done ops still pending (staged; the next run redoes them as SKIPPED_ALREADY_DONE), and from (2) on it
	// replays last-apply.json. An abandoned op is never still in pending.json once last-apply.json says so, where a later
	// run could apply it after History has called it abandoned.
	public ApplyResult run(PendingActions plan, Path pendingFile) throws IOException {
		Path configDir = InstanceDirs.configDirOf(pendingFile);
		Path modsDir = InstanceDirs.modsDirOf(pendingFile);
		ApplyResult result = new ApplyResult(Instant.now().toString(), giveUpOnRepeatFailures(execute(plan, modsDir, configDir)));
		writeRemaining(plan, pendingFile, result, modsDir, EnumSet.of(Status.ABANDONED), true);
		result.save(ApplyResult.defaultPath(configDir));
		writeRemaining(plan, pendingFile, result, modsDir, EnumSet.of(Status.OK, Status.SKIPPED_ALREADY_DONE, Status.ABANDONED), false);
		updateJournal(configDir, result);
		return result;
	}

	// Best effort and last (review M5): the renames and pending.json/last-apply.json are already done, and a journal
	// problem (even a missing class on the helper's classpath) must never fail them. preLaunch reconciles from
	// last-apply.json if this didn't happen.
	private static void updateJournal(Path configDir, ApplyResult result) {
		try {
			new Journal(configDir, null, null, ApplyExecutor::journalWarning)
					.updateExisting(entries -> HistoryUpdates.applyResults(entries, result.results()));
		} catch (Throwable t) {
			journalWarning("Could not update history.json", t);
		}
	}

	private static void journalWarning(String message, Throwable error) {
		ApplyHelper.log(message + (error == null ? "" : ": " + error));
	}

	// A group with an op that has now failed in MAX_FAILED_RUNS helper runs is abandoned as a whole, so a change that
	// can never apply doesn't come back at every exit. Never a group left half-applied: dropping it would retire its
	// download and leave the mod missing for good, so it stays until a run finishes it or rolls it back.
	static List<OpResult> giveUpOnRepeatFailures(List<OpResult> results) {
		Set<String> halfApplied = new HashSet<>();
		for (int i = 0; i < results.size(); i++) {
			if (leftHalfApplied(results.get(i))) {
				halfApplied.add(groupKey(results.get(i).op(), i));
			}
		}
		Set<String> givenUp = new HashSet<>();
		for (int i = 0; i < results.size(); i++) {
			OpResult r = results.get(i);
			if (r.status() == Status.FAILED && r.op() != null && r.op().attempts() + 1 >= MAX_FAILED_RUNS && !halfApplied.contains(groupKey(r.op(), i))) {
				givenUp.add(groupKey(r.op(), i));
			}
		}
		List<OpResult> out = new ArrayList<>(results);
		for (int i = 0; i < out.size(); i++) {
			OpResult r = out.get(i);
			if (r.status() == Status.FAILED && r.op() != null && givenUp.contains(groupKey(r.op(), i))) {
				out.set(i, new OpResult(r.op(), Status.ABANDONED, "Gave up after " + MAX_FAILED_RUNS + " restarts: " + r.message()));
			}
		}
		return out;
	}

	// A rollback that failed leaves its file under the name its group gave it, and its result says where (resultPath);
	// no other failed result has one.
	static boolean leftHalfApplied(OpResult r) {
		return r != null && r.status() == Status.FAILED && r.op() != null && r.resultPath() != null;
	}

	private static String groupKey(Op op, int index) {
		return op.group() != null ? "group:" + op.group() : "op:" + index;
	}

	// The ops whose status is in `leaving` leave the plan (the plan passed in when pending.json is gone or unreadable; so
	// step (3) lists ABANDONED again, and an op step (1) dropped never comes back). With countFailures, failed ones count
	// another run (at most MAX_FAILED_RUNS - 1: only a half-applied group fails that often without being abandoned), and
	// an abandoned enable's download is renamed to .rigtune-superseded.
	private static void writeRemaining(PendingActions plan, Path pendingFile, ApplyResult result, Path modsDir, Set<Status> leaving,
			boolean countFailures) throws IOException {
		List<Op> gone = result.results().stream().filter(r -> leaving.contains(r.status())).map(OpResult::op).filter(Objects::nonNull).toList();
		List<Op> failed = !countFailures ? List.of()
				: result.results().stream().filter(r -> r.status() == Status.FAILED).map(OpResult::op).filter(Objects::nonNull).toList();
		if (countFailures && gone.isEmpty() && failed.isEmpty()) {
			return;
		}
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
			if (gone.stream().anyMatch(d -> d.sameOp(op))) {
				continue;
			}
			remaining.add(op != null && failed.stream().anyMatch(f -> f.sameOp(op))
					? op.withAttempts(Math.min(op.attempts() + 1, MAX_FAILED_RUNS - 1)) : op);
		}
		if (remaining.isEmpty()) {
			Files.deleteIfExists(pendingFile);
		} else if (!remaining.equals(base.ops()) || !Files.exists(pendingFile)) {
			base.withOps(remaining).save(pendingFile);
		}
		if (!countFailures) {
			return;
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
		InstalledJars installed = new InstalledJars(modsDir, this::jarModId);
		UnfinishedGroups unfinished = UnfinishedGroups.load(configDir);
		for (List<Integer> members : groups.values()) {
			runGroup(ops, members, modsDir, configDir, installed, unfinished, out);
			members.forEach(i -> installed.forget(ops.get(i)));
		}
		unfinished.retainOnly(ops.stream().filter(Objects::nonNull).map(Op::group).filter(Objects::nonNull).toList());
		return Arrays.asList(out);
	}

	// The mod ids of the jars in the mods folder, each read once; forget() drops the names a group may have renamed.
	private static final class InstalledJars {
		private final Path modsDir;
		private final Function<Path, String> idOf;
		private final Map<String, String> idsByName = new HashMap<>();

		InstalledJars(Path modsDir, Function<Path, String> idOf) {
			this.modsDir = modsDir;
			this.idOf = idOf;
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
				if (!ignored.contains(name) && modId.equals(idsByName.computeIfAbsent(name, n -> Objects.requireNonNullElse(idOf.apply(jar), "")))) {
					return name;
				}
			}
			return null;
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
	// modIds: the mod id of each enable whose file is still there.
	private static String duplicateProblem(List<Op> ops, List<Integer> order, String[] modIds, InstalledJars installed) {
		Set<String> disabled = new HashSet<>();
		for (int i : order) {
			if (ops.get(i).type() == PendingActions.Type.DISABLE_FILE) {
				disabled.add(fileName(ops.get(i).path()));
			}
		}
		for (int i : order) {
			if (modIds[i] == null) {
				continue;
			}
			String existing = installed.withModId(modIds[i], disabled);
			if (existing != null) {
				return "mod " + modIds[i] + " is already installed as " + existing + ", so enabling " + fileName(ops.get(i).to())
						+ " would load it twice; its download is renamed to " + PendingActions.SUPERSEDED_SUFFIX;
			}
		}
		return null;
	}

	// Every enable is checked with the id its jar declares: one staged without a mod id (by 0.1.0, or an Undo of a jar it
	// couldn't read; review 3, apply-safety-1) and one staged with an id alike (review 4, apply-safety-1). Null when
	// there is none, also after an Error (review 4, security-1): one bad jar must fail only its own check, never the helper.
	private String jarModId(Path jar) {
		try {
			return modIds.read(jar);
		} catch (Throwable t) {
			return null;
		}
	}

	private record Undo(int index, Path moved, Path back) {
	}

	private record Applied(OpResult result, Undo undo) {
	}

	// A group is all-or-nothing: disables run first, and an enable runs only once every disable in the group is OK or
	// already done. Each op is tried once per pass. When one fails, the rest are skipped and every rename of the group is
	// undone at once; only then is the whole group retried (audit H4: retrying in place kept, say, the old jar disabled
	// and the new one not yet enabled for up to 30 s). The pass's renames are recorded in UnfinishedGroups first, so if
	// the helper is killed mid-group or a rollback fails, the next run rolls the group forward: renames an earlier run did
	// that are still in effect count as done by this one, and if the group fails again they're rolled back with the rest.
	// Either way no group ends half-applied unless a rollback itself fails, and then it stays recorded and pending.
	private void runGroup(List<Op> ops, List<Integer> members, Path modsDir, Path configDir, InstalledJars installed,
			UnfinishedGroups unfinished, OpResult[] out) {
		List<Integer> order = new ArrayList<>(members);
		order.sort(Comparator.comparingInt(i -> rank(ops.get(i))));
		Op first = ops.get(order.getFirst());
		String group = first == null ? null : first.group();
		Map<Integer, Undo> earlier = earlierRenames(ops, order, modsDir, unfinished.of(group));

		String[] problems = new String[ops.size()];
		String[] modIds = new String[ops.size()];
		boolean refused = false;
		for (int i : order) {
			Op op = ops.get(i);
			problems[i] = problem(op, modsDir, configDir);
			// A jar with no readable mod id can't be checked against what's installed, so it's never enabled; nor is one
			// that isn't the mod it was staged as. (An enable an earlier run did was checked by that run.)
			if (problems[i] == null && op.type() == PendingActions.Type.ENABLE_FILE && Files.exists(Path.of(op.from()))) {
				modIds[i] = jarModId(Path.of(op.from()));
				if (modIds[i] == null) {
					problems[i] = fileName(op.from()) + " is not a Fabric mod jar (no readable fabric.mod.json id)";
				} else if (op.modId() != null && !op.modId().equals(modIds[i])) {
					problems[i] = fileName(op.from()) + " declares mod id " + modIds[i] + ", not " + op.modId() + " as staged";
				}
			}
			refused |= problems[i] != null;
		}
		if (refused) {
			for (int i : order) {
				out[i] = new OpResult(ops.get(i), Status.FAILED,
						problems[i] != null ? "Refused: " + problems[i] : "Not applied: another change in its group was refused");
			}
			if (rollBack(ops, new ArrayList<>(earlier.values()), "another change in its group was refused", out)) {
				unfinished.remove(group);
			}
			return;
		}
		String duplicate = duplicateProblem(ops, order, modIds, installed);
		if (duplicate != null) {
			// What an earlier run did stays: the mod is installed another way, so putting the old jar back would load it twice.
			for (int i : order) {
				Undo done = earlier.get(i);
				out[i] = done != null ? doneEarlier(ops.get(i), done) : new OpResult(ops.get(i), Status.ABANDONED, "Dropped: " + duplicate);
			}
			unfinished.remove(group);
			return;
		}

		RetryState state = new RetryState();
		while (true) {
			Map<Integer, Path> targets = new HashMap<>();
			List<Rename> renames = renames(ops, order, earlier, targets);
			if (order.size() > 1 && !renames.isEmpty()) {
				unfinished.put(group, renames);
			}
			List<Undo> undos = new ArrayList<>();
			int failed = -1;
			IOException error = null;
			for (int k = 0; k < order.size() && failed < 0; k++) {
				int i = order.get(k);
				Op op = ops.get(i);
				Undo done = earlier.get(i);
				Applied applied;
				if (done != null) {
					applied = new Applied(doneEarlier(op, done), done);
				} else {
					try {
						applied = tryOnce(op, i, targets.get(i));
					} catch (IOException e) {
						error = e;
						failed = k;
						break;
					}
				}
				out[i] = applied.result();
				if (applied.result().status() == Status.FAILED) {
					failed = k;
				} else if (applied.undo() != null) {
					undos.add(applied.undo());
				}
			}
			if (failed < 0) {
				unfinished.remove(group);
				return;
			}
			int i = order.get(failed);
			String reason = describe(ops.get(i)) + " failed";
			for (int rest : order.subList(failed + 1, order.size())) {
				out[rest] = new OpResult(ops.get(rest), Status.FAILED, "Not applied because " + reason);
				// An earlier run's rename of an op this pass didn't reach is put back too.
				if (earlier.containsKey(rest)) {
					undos.add(earlier.get(rest));
				}
			}
			boolean putBack = rollBack(ops, undos, reason, out);
			if (putBack) {
				earlier = Map.of();
				if (error != null && state.onFailure(error)) {
					continue;
				}
			}
			if (error != null) {
				out[i] = new OpResult(ops.get(i), Status.FAILED, "Gave up after " + (state.attempt() + (putBack ? 0 : 1)) + " tries: " + error);
			}
			if (putBack) {
				unfinished.remove(group);
			}
			return;
		}
	}

	// The renames an earlier run recorded for this group and didn't finish or undo: each still in effect (its new name
	// exists, its old one doesn't), matched to the group's op by id and paths, both names directly in the mods folder, so
	// a record can never make the helper rename anything the op itself wouldn't.
	private static Map<Integer, Undo> earlierRenames(List<Op> ops, List<Integer> order, Path modsDir, List<Rename> recorded) {
		Map<Integer, Undo> out = new LinkedHashMap<>();
		for (int i : order) {
			for (Rename r : recorded) {
				Undo undo = inEffect(ops.get(i), i, r, modsDir);
				if (undo != null) {
					out.put(i, undo);
					break;
				}
			}
		}
		return out;
	}

	private static Undo inEffect(Op op, int index, Rename r, Path modsDir) {
		if (op == null || op.type() == null || r.from() == null || r.to() == null || !Objects.equals(op.id(), r.op())) {
			return null;
		}
		try {
			Path from = Path.of(r.from());
			Path to = Path.of(r.to());
			boolean same = switch (op.type()) {
				case ENABLE_FILE -> op.from() != null && op.to() != null && from.equals(Path.of(op.from())) && to.equals(Path.of(op.to()));
				case DISABLE_FILE -> op.path() != null && from.equals(Path.of(op.path())) && fileName(r.to()).startsWith(fileName(r.from()) + ".disabled");
				case PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES -> false;
			};
			return same && SafeFileNames.isDirectChild(modsDir, from) && SafeFileNames.isDirectChild(modsDir, to) && Files.exists(to)
					&& !Files.exists(from) ? new Undo(index, to, from) : null;
		} catch (InvalidPathException e) {
			return null;
		}
	}

	// This pass's renames in order: the earlier run's still in effect, then each one this pass will do (an enable whose
	// download is there and whose name is free, a disable whose jar is there, with the .disabled name it gets in `targets`).
	private static List<Rename> renames(List<Op> ops, List<Integer> order, Map<Integer, Undo> earlier, Map<Integer, Path> targets) {
		List<Rename> out = new ArrayList<>();
		for (int i : order) {
			Op op = ops.get(i);
			Undo done = earlier.get(i);
			if (done != null) {
				out.add(new Rename(op.id(), done.back().toString(), done.moved().toString()));
			} else if (op.type() == PendingActions.Type.ENABLE_FILE && Files.exists(Path.of(op.from())) && !Files.exists(Path.of(op.to()))) {
				out.add(new Rename(op.id(), op.from(), op.to()));
			} else if (op.type() == PendingActions.Type.DISABLE_FILE && Files.exists(Path.of(op.path()))) {
				Path target = disabledTarget(Path.of(op.path()));
				targets.put(i, target);
				out.add(new Rename(op.id(), op.path(), target.toString()));
			}
		}
		return out;
	}

	private static OpResult doneEarlier(Op op, Undo done) {
		return new OpResult(op, Status.SKIPPED_ALREADY_DONE, "Already done by an earlier run: " + done.moved().getFileName(), done.moved().toString());
	}

	// Undoes the renames, newest first, each with the full retry budget. False when one stays where the group put it.
	private boolean rollBack(List<Op> ops, List<Undo> undos, String reason, OpResult[] out) {
		boolean all = true;
		for (int u = undos.size() - 1; u >= 0; u--) {
			Undo undo = undos.get(u);
			out[undo.index()] = rollback(ops.get(undo.index()), undo, reason);
			all &= !leftHalfApplied(out[undo.index()]);
		}
		return all;
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
		RetryState state = new RetryState();
		IOException last = null;
		while (true) {
			if (Files.exists(undo.back()) || !Files.exists(undo.moved())) {
				break;
			}
			try {
				mover.move(undo.moved(), undo.back());
				return new OpResult(op, Status.FAILED, "Rolled back because " + reason);
			} catch (IOException e) {
				last = e;
				if (!state.onFailure(e)) {
					break;
				}
			}
		}
		boolean stuck = Files.exists(undo.moved());
		return new OpResult(op, Status.FAILED, "Rollback failed (" + (last == null ? "the original name is taken" : last)
				+ "); " + undo.moved().getFileName() + " was left as it is after " + reason
				+ (stuck ? "; the next exit finishes or rolls back this change" : ""), stuck ? undo.moved().toString() : null);
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

	// One try: the op's result, or an IOException a retry might fix. A RuntimeException (a malformed config file, a
	// patcher's refusal) fails it at once.
	private Applied tryOnce(Op op, int index, Path disableTarget) throws IOException {
		try {
			return switch (op.type()) {
				case ENABLE_FILE -> enable(op, index);
				case DISABLE_FILE -> disable(op, index, disableTarget);
				case PATCH_JSON -> new Applied(patchJson(op), null);
				case PATCH_TOML -> new Applied(patchConfig(op, TomlConfigPatcher.patchFile(Path.of(op.path()), patchesOf(op))), null);
				case PATCH_PROPERTIES -> new Applied(patchConfig(op, PropertiesConfigPatcher.patchFile(Path.of(op.path()), patchesOf(op))), null);
			};
		} catch (RuntimeException e) {
			return new Applied(new OpResult(op, Status.FAILED, e.toString()), null);
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
		return new Applied(new OpResult(op, Status.OK, "Enabled " + to.getFileName(), to.toString()), new Undo(index, to, from));
	}

	// planned: the .disabled name recorded for this rename, if any.
	private Applied disable(Op op, int index, Path planned) throws IOException {
		Path path = Path.of(op.path());
		if (!Files.exists(path)) {
			return new Applied(new OpResult(op, Status.SKIPPED_ALREADY_DONE, path.getFileName() + " is already gone"), null);
		}
		Path target = planned != null ? planned : disabledTarget(path);
		mover.move(path, target);
		return new Applied(new OpResult(op, Status.OK, "Disabled " + path.getFileName() + " -> " + target.getFileName(), target.toString()),
				new Undo(index, target, path));
	}

	public static Path disabledTarget(Path path) {
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
