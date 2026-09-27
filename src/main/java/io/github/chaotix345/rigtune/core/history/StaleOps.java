package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import org.jspecify.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

// docs/v0.5/SPEC.md 2H RW-3: staged groups that can never run, so RigTune drops them at launch instead of saying they'll be
// retried (the real 0.1.0 DH group of real-world-2026-09-27.md §3-4). An enable's download is gone: when the mod is there
// anyway (at the op's target, unless a staged disable turns that jar off, or loaded from another jar) it was installed
// another way, else the download is simply gone. Or its download is there but its mod is loaded from a jar that is
// neither the op's target nor one a staged disable turns off: installed another way too. A group the helper left half
// done (PartlyApplied) never counts: the next exit finishes it or rolls it back. Pure: preLaunch runs it with Files.exists
// and FabricLoader's origins only (no jar opened), the rebuild's worker with the same.
public final class StaleOps {
	public enum Why {
		// The mod is installed another way: ABANDONED in History.
		INSTALLED,
		// The download is gone and the mod isn't there: DISCARDED.
		GONE
	}

	// One stale group, by one of its enables: opId (unstaging it takes its group), the enable's mod id (null when staged
	// without one) and target file name, and for INSTALLED the jar the mod is installed as.
	public record Stale(String opId, Why why, @Nullable String modId, String file, @Nullable String installedAs) {
	}

	private StaleOps() {
	}

	// exists: whether a file is there now. loadedFrom: a loaded mod id -> the file names of the top-level jars it was loaded
	// from. modIdOf: an enable's mod id. halfDone: the groups the helper left half done. One entry per group (an INSTALLED
	// enable before a GONE one), in pending.json's order.
	public static List<Stale> find(List<Op> ops, Predicate<Path> exists, Map<String, Set<String>> loadedFrom, Function<Op, String> modIdOf,
			Set<String> halfDone) {
		// Jars some staged disable turns off at the next exit, whatever its group.
		Set<String> disabled = new HashSet<>();
		for (Op op : ops) {
			if (op != null && op.type() == PendingActions.Type.DISABLE_FILE && op.path() != null) {
				disabled.add(HistoryUpdates.fileName(op.path()));
			}
		}
		Map<String, Stale> byGroup = new LinkedHashMap<>();
		for (Op op : ops) {
			if (op == null || op.type() != PendingActions.Type.ENABLE_FILE || op.id() == null || op.from() == null || op.to() == null
					|| op.group() != null && halfDone.contains(op.group())) {
				continue;
			}
			Path from;
			Path to;
			try {
				from = Path.of(op.from());
				to = Path.of(op.to());
			} catch (InvalidPathException e) {
				continue;
			}
			String target = HistoryUpdates.fileName(op.to());
			String modId = modIdOf.apply(op);
			String elsewhere = modId == null ? null : loadedFrom.getOrDefault(modId, Set.of()).stream()
					.filter(jar -> !jar.equals(target) && !disabled.contains(jar)).sorted().findFirst().orElse(null);
			Stale stale;
			if (!exists.test(from)) {
				boolean atTarget = exists.test(to) && !disabled.contains(target);
				stale = atTarget || elsewhere != null ? new Stale(op.id(), Why.INSTALLED, modId, target, atTarget ? target : elsewhere)
						: new Stale(op.id(), Why.GONE, modId, target, null);
			} else if (elsewhere != null) {
				stale = new Stale(op.id(), Why.INSTALLED, modId, target, elsewhere);
			} else {
				continue;
			}
			String group = op.group() != null ? op.group() : "op:" + op.id();
			Stale before = byGroup.get(group);
			if (before == null || before.why() == Why.GONE && stale.why() == Why.INSTALLED) {
				byGroup.put(group, stale);
			}
		}
		return List.copyOf(byGroup.values());
	}
}
