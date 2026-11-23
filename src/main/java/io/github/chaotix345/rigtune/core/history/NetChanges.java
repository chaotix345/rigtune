package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.ApplyResult;
import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.apply.PendingNet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// #46: what the next start's "RigTune applied N change(s)" counts. Staging keeps every switch as its own op and journal
// change, chained on the staged values (A -> B, then B -> A), so the helper may run several patches of one key; the
// toast counts each settings key once, and only when its value after the queue differs from its value before it, in the
// order the helper ran the ops. "Before" is what the file had when the helper's first op on the key ran (OpResult.before;
// a key the file didn't have is no value), so an edit made outside RigTune after staging counts. The first journal
// change's before is used instead when the result records nothing (a 0.5.x helper), and when the file already had the
// value that op writes: a helper killed after the patch and before last-apply.json leaves the op pending, and the next
// run finds its own write there. That case can't be told apart from the player setting the staged value by hand, which
// then counts as it did before 0.6.0 (no extra write before the patch, so the helper's crash-safe order stays). Values
// compare as the op's patcher compares them (PendingNet.sameInFile). File changes count as before. A patch the journal
// has no change for (an older plan, an unreadable journal) counts as one change, as before.
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
		Map<String, Span> keys = new LinkedHashMap<>();
		int unjournaled = 0;
		for (Op op : patches) {
			List<JournalChange> changes = op.id() == null ? null : byOp.get(op.id());
			if (changes == null || changes.isEmpty()) {
				unjournaled++;
				continue;
			}
			Map<String, String> inFile = before.get(op.id());
			for (JournalChange c : changes) {
				keys.computeIfAbsent(c.key(), k -> new Span(op.type(), baseline(op, inFile, keyInFile(k), c))).after = c.after();
			}
		}
		int changed = 0;
		for (Span span : keys.values()) {
			if (!same(span.type, span.before, span.after)) {
				changed++;
			}
		}
		return changed + unjournaled;
	}

	private static final class Span {
		final PendingActions.Type type;
		final String before;
		String after;

		Span(PendingActions.Type type, String before) {
			this.type = type;
			this.before = before;
		}
	}

	private static String baseline(Op op, Map<String, String> inFile, String keyInFile, JournalChange c) {
		if (inFile == null) {
			return c.before();
		}
		String was = inFile.get(keyInFile);
		String writes = op.patches() == null ? null : op.patches().get(keyInFile);
		return was != null && writes != null && same(op.type(), was, writes) ? c.before() : was;
	}

	private static boolean same(PendingActions.Type type, String a, String b) {
		return a == null || b == null ? Objects.equals(a, b) : PendingNet.sameInFile(type, a, b);
	}

	// "sodium.performance.x" -> "performance.x": the journal's keys carry the mod's prefix, the op's don't.
	private static String keyInFile(String key) {
		return key.substring(key.indexOf('.') + 1);
	}
}
