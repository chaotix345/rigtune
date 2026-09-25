package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.InstalledMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

// The recommendations this session staged, each with the ids its ops have in pending.json (plan review A-M1: pending.json
// knows only op ids, and a merge can give an op the id of the staged op it repeats). A recommendation stays staged only
// while one of its ops is still there and none of its enables was dropped or replaced (an undo's re-enable of the same
// mod replaces an update's enable and takes over its disable), so after a drop, an undo or a discard the report matches
// what is really staged.
final class StagedRecommendations {
	private final Map<String, Set<String>> opIds = new LinkedHashMap<>();
	private final Map<String, Set<String>> enableIds = new LinkedHashMap<>();

	// ops: what was handed to the merge; opIdsByRecommendation: each staged recommendation's op ids among them;
	// survivingIds: the merge's op id -> the id that change has in pending.json (Staging.Merge).
	void add(List<Op> ops, Map<String, List<String>> opIdsByRecommendation, Map<String, String> survivingIds) {
		Set<String> enables = new HashSet<>();
		ops.stream().filter(op -> op != null && op.type() == PendingActions.Type.ENABLE_FILE && op.id() != null).forEach(op -> enables.add(op.id()));
		opIdsByRecommendation.forEach((id, ids) -> {
			Set<String> recorded = opIds.computeIfAbsent(id, k -> new LinkedHashSet<>());
			Set<String> recordedEnables = enableIds.computeIfAbsent(id, k -> new LinkedHashSet<>());
			for (String op : ids) {
				String surviving = survivingIds.get(op);
				if (surviving != null) {
					recorded.add(surviving);
					if (enables.contains(op)) {
						recordedEnables.add(surviving);
					}
				}
			}
		});
	}

	boolean contains(String id) {
		return opIds.containsKey(id);
	}

	int size() {
		return opIds.size();
	}

	Set<String> ids() {
		return Set.copyOf(opIds.keySet());
	}

	Set<String> opIdsOf(String id) {
		return Set.copyOf(opIds.getOrDefault(id, Set.of()));
	}

	// Keeps the recommendations with an op still in pending.json and all of their enables; returns the ones that
	// stopped being staged.
	Set<String> retainPending(Collection<Op> pendingOps) {
		Set<String> present = idsOf(pendingOps);
		Set<String> gone = new LinkedHashSet<>();
		opIds.entrySet().removeIf(entry -> {
			boolean drop = entry.getValue().stream().noneMatch(present::contains)
					|| !present.containsAll(enableIds.getOrDefault(entry.getKey(), Set.of()));
			if (drop) {
				gone.add(entry.getKey());
				enableIds.remove(entry.getKey());
			}
			return drop;
		});
		return gone;
	}

	// After a drop, an undo or a discard: retainPending against pending.json (none when it's missing), returning the
	// carried-over count (unowned). -1, changing nothing, when pending.json can't be read.
	int recount(Path pendingFile) {
		List<Op> ops;
		try {
			ops = Files.exists(pendingFile) ? PendingActions.load(pendingFile).ops() : List.of();
		} catch (IOException e) {
			return -1;
		}
		retainPending(ops);
		return unowned(ops);
	}

	// The staged ops no recommendation of this session owns: carried over from another session (or without an id).
	int unowned(Collection<Op> pendingOps) {
		Set<String> owned = new HashSet<>();
		opIds.values().forEach(owned::addAll);
		return (int) pendingOps.stream().filter(op -> op == null || op.id() == null || !owned.contains(op.id())).count();
	}

	private static Set<String> idsOf(Collection<Op> ops) {
		Set<String> out = new HashSet<>();
		ops.stream().filter(Objects::nonNull).map(Op::id).filter(Objects::nonNull).forEach(out::add);
		return out;
	}

	// For the notice after a queued-update drop: the loaded mods whose own update is waiting (not an addition that went
	// with one of them in its group), by their name in the scan, else by mod id.
	static List<String> droppedModNames(List<Op> dropped, Set<String> queued, Set<String> loaded, List<InstalledMod> scanned) {
		Set<String> names = new LinkedHashSet<>();
		for (Op op : dropped) {
			if (op == null || op.type() != PendingActions.Type.ENABLE_FILE || op.modId() == null
					|| !queued.contains(op.modId()) || !loaded.contains(op.modId())) {
				continue;
			}
			names.add(scanned.stream().filter(m -> op.modId().equals(m.modId()) && m.name() != null).map(InstalledMod::name)
					.findFirst().orElse(op.modId()));
		}
		return List.copyOf(names);
	}
}
