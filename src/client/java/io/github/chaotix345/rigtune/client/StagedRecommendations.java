package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.InstalledMod;

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
// while one of its ops is still there, so after a drop, an undo or a discard the report matches what is really staged.
final class StagedRecommendations {
	private final Map<String, Set<String>> opIds = new LinkedHashMap<>();

	// opIdsByRecommendation: each staged recommendation's op ids as handed to the merge; survivingIds: the merge's
	// op id -> the id that change has in pending.json (Staging.Merge).
	void add(Map<String, List<String>> opIdsByRecommendation, Map<String, String> survivingIds) {
		opIdsByRecommendation.forEach((id, ops) -> {
			Set<String> recorded = opIds.computeIfAbsent(id, k -> new LinkedHashSet<>());
			for (String op : ops) {
				String surviving = survivingIds.get(op);
				if (surviving != null) {
					recorded.add(surviving);
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

	// Keeps the recommendations with an op still in pending.json; returns the ones that stopped being staged.
	Set<String> retainPending(Collection<Op> pendingOps) {
		Set<String> present = idsOf(pendingOps);
		Set<String> gone = new LinkedHashSet<>();
		opIds.entrySet().removeIf(entry -> {
			boolean drop = entry.getValue().stream().noneMatch(present::contains);
			if (drop) {
				gone.add(entry.getKey());
			}
			return drop;
		});
		return gone;
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

	// For the notice after a queued-update drop: the loaded mods (from the scan) whose own update is waiting, by name, not
	// an addition that went with one of them in its group. Their mod ids if the scan doesn't have them.
	static List<String> droppedModNames(List<Op> dropped, Set<String> queued, List<InstalledMod> scanned) {
		Set<String> names = new LinkedHashSet<>();
		Set<String> modIds = new LinkedHashSet<>();
		for (Op op : dropped) {
			if (op == null || op.type() != PendingActions.Type.ENABLE_FILE || op.modId() == null || !queued.contains(op.modId())) {
				continue;
			}
			modIds.add(op.modId());
			scanned.stream().filter(m -> op.modId().equals(m.modId())).findFirst()
					.ifPresent(m -> names.add(m.name() != null ? m.name() : m.modId()));
		}
		return List.copyOf(names.isEmpty() ? modIds : names);
	}
}
