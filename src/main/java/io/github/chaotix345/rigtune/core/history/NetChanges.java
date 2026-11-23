package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// #46: what the next start's "RigTune applied N change(s)" counts. Staging keeps every switch as its own op and journal
// change, chained on the staged values (A -> B, then B -> A), so the helper may run several patches of one key; the
// toast counts each settings key once, and only when its value after the queue differs from its value before it, in the
// order the helper ran the ops. "Before" is what the file had when the helper's first op on the key ran (OpResult.before),
// so an edit made outside RigTune after staging counts; the first journal change's before when the result doesn't say
// (a 0.5.x helper). Values compare as the file holds them (SettingValues.sameInFile). File changes count as before. A
// patch the journal has no change for (an older plan, an unreadable journal) counts as one change, as before.
public final class NetChanges {
	private NetChanges() {
	}

	public static ApplyResult.Counts counts(ApplyResult result, List<JournalEntry> entries) {
		Map<String, List<JournalChange>> byOp = new HashMap<>();
		for (JournalEntry entry : entries) {
			for (JournalChange c : entry.changes()) {
				if (c.isSetting() && c.opId() != null && c.key() != null) {
					byOp.computeIfAbsent(c.opId(), k -> new ArrayList<>()).add(c);
				}
			}
		}
		Map<String, Map<String, String>> before = new HashMap<>();
		for (ApplyResult.OpResult r : result.results()) {
			if (r != null && r.op() != null && r.op().id() != null && r.before() != null) {
				before.put(r.op().id(), r.before());
			}
		}
		return result.counts(patches -> net(patches, byOp, before));
	}

	static int net(List<Op> patches, Map<String, List<JournalChange>> byOp, Map<String, Map<String, String>> before) {
		Map<String, String[]> keys = new LinkedHashMap<>();
		int unjournaled = 0;
		for (Op op : patches) {
			List<JournalChange> changes = op.id() == null ? null : byOp.get(op.id());
			if (changes == null || changes.isEmpty()) {
				unjournaled++;
				continue;
			}
			Map<String, String> inFile = before.getOrDefault(op.id(), Map.of());
			for (JournalChange c : changes) {
				String[] span = keys.computeIfAbsent(c.key(), k -> new String[] {inFile.getOrDefault(keyInFile(k), c.before()), c.after()});
				span[1] = c.after();
			}
		}
		int changed = 0;
		for (String[] span : keys.values()) {
			if (!same(span[0], span[1])) {
				changed++;
			}
		}
		return changed + unjournaled;
	}

	private static boolean same(String a, String b) {
		return a == null || b == null ? Objects.equals(a, b) : SettingValues.sameInFile(a, b);
	}

	// "sodium.performance.x" -> "performance.x": the journal's keys carry the mod's prefix, the op's don't.
	private static String keyInFile(String key) {
		return key.substring(key.indexOf('.') + 1);
	}
}
