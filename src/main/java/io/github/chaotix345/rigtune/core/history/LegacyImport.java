package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The one-time `legacy-import` entry for an upgrade from 0.1.x (docs/v0.2/SPEC.md item 3, review L2): the file ops
// 0.1.x's last helper run did (OK or already done) become APPLIED changes, and ops 0.1.x left staged become STAGED
// changes. RigTune's own jars are left out (by mod id, by the group of its update, or by reading the jar), and so are
// config patches that already ran (their before-values were never recorded).
public final class LegacyImport {
	private static final Pattern DISABLED_AS = Pattern.compile("^Disabled .+ -> (.+)$");

	private LegacyImport() {
	}

	// Null when there's nothing to import.
	public static JournalEntry entry(ApplyResult lastApply, PendingActions leftover, Function<Path, String> modIdOf,
			StagedChanges.ConfigKeys config, String mcVersion) {
		return entry(lastApply, leftover, modIdOf, jar -> null, config, mcVersion);
	}

	// modNameOf (docs/v0.4/SPEC.md 2c, best effort): a jar's display name where it is now; 0.1.x's jars are often gone.
	public static JournalEntry entry(ApplyResult lastApply, PendingActions leftover, Function<Path, String> modIdOf, Function<Path, String> modNameOf,
			StagedChanges.ConfigKeys config, String mcVersion) {
		List<JournalChange> changes = new ArrayList<>();
		if (lastApply != null) {
			changes.addAll(applied(lastApply, modIdOf, modNameOf));
		}
		if (leftover != null) {
			changes.addAll(staged(leftover, modIdOf, modNameOf, config));
		}
		if (changes.isEmpty()) {
			return null;
		}
		String at = lastApply != null && lastApply.finishedAt() != null ? lastApply.finishedAt() : Instant.now().toString();
		return new JournalEntry(ChangeRecorder.newEntryId(), at, JournalEntry.LEGACY_IMPORT, null, mcVersion, null, changes);
	}

	private static List<JournalChange> applied(ApplyResult lastApply, Function<Path, String> modIdOf, Function<Path, String> modNameOf) {
		Set<String> selfGroups = new HashSet<>();
		for (ApplyResult.OpResult r : lastApply.results()) {
			if (r != null && r.op() != null && r.op().type() == PendingActions.Type.ENABLE_FILE
					&& UndoPlanner.RIGTUNE.equals(r.op().modId()) && r.op().group() != null) {
				selfGroups.add(r.op().group());
			}
		}
		List<JournalChange> out = new ArrayList<>();
		for (ApplyResult.OpResult r : lastApply.results()) {
			if (r == null || r.op() == null || r.op().type() == null
					|| r.status() != ApplyResult.Status.OK && r.status() != ApplyResult.Status.SKIPPED_ALREADY_DONE) {
				continue;
			}
			Op op = r.op();
			try {
				if (op.type() == PendingActions.Type.ENABLE_FILE && op.to() != null) {
					String modId = op.modId() != null ? op.modId() : modIdOf.apply(Path.of(op.to()));
					if (!UndoPlanner.RIGTUNE.equals(modId)) {
						out.add(JournalChange.file(JournalChange.ENABLE, modId, HistoryUpdates.fileName(op.to()), JournalChange.APPLIED, op.id(), op.group())
								.withModName(modNameOf.apply(Path.of(op.to()))));
					}
				} else if (op.type() == PendingActions.Type.DISABLE_FILE && op.path() != null && (op.group() == null || !selfGroups.contains(op.group()))) {
					String disabledAs = disabledAs(r);
					String modId = disabledAs == null ? null : modIdOf.apply(Path.of(op.path()).resolveSibling(disabledAs));
					if (!UndoPlanner.RIGTUNE.equals(modId)) {
						out.add(JournalChange.file(JournalChange.DISABLE, modId, HistoryUpdates.fileName(op.path()), JournalChange.APPLIED, op.id(), op.group())
								.withResultFile(disabledAs).withModName(disabledAs == null ? null : modNameOf.apply(Path.of(op.path()).resolveSibling(disabledAs))));
					}
				}
			} catch (InvalidPathException ignored) {
			}
		}
		return out;
	}

	// Where a disabled jar went: resultPath (0.2), else 0.1.0's "Disabled x -> y" message, else x.disabled; unknown
	// for a disable that was already done.
	private static String disabledAs(ApplyResult.OpResult r) {
		if (r.resultPath() != null) {
			return HistoryUpdates.fileName(r.resultPath());
		}
		Matcher m = r.message() == null ? null : DISABLED_AS.matcher(r.message());
		if (m != null && m.matches()) {
			return m.group(1);
		}
		return r.status() == ApplyResult.Status.OK ? HistoryUpdates.fileName(r.op().path()) + ".disabled" : null;
	}

	// Ops 0.1.x left in pending.json. Ops without an id (written before ids existed) can't be tracked and are left out.
	private static List<JournalChange> staged(PendingActions leftover, Function<Path, String> modIdOf, Function<Path, String> modNameOf,
			StagedChanges.ConfigKeys config) {
		List<Op> tracked = leftover.ops().stream().filter(op -> op != null && op.id() != null && op.type() != null).toList();
		Set<String> selfGroups = new HashSet<>();
		tracked.forEach(op -> {
			if (op.type() == PendingActions.Type.ENABLE_FILE && UndoPlanner.RIGTUNE.equals(op.modId()) && op.group() != null) {
				selfGroups.add(op.group());
			}
		});
		PendingActions base = leftover.withOps(List.of());
		List<JournalChange> out = new ArrayList<>();
		for (JournalChange c : StagedChanges.of(base, tracked, base.merge(tracked), config, modIdOf, modNameOf, Set.of()).changes()) {
			if (!UndoPlanner.RIGTUNE.equals(c.modId()) && (c.group() == null || !selfGroups.contains(c.group()))) {
				out.add(c);
			}
		}
		return out;
	}
}
