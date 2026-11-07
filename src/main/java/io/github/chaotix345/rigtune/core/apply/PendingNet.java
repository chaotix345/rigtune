package io.github.chaotix345.rigtune.core.apply;

import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

// #46: whether pending.json would change anything at the next exit. Profile switches back and forth leave a queue whose
// patches end every key on the value its file already has; Discard pending and Try it's "changes are waiting" refusal
// then have nothing to act on. A file op always changes something; a key whose file value can't be read counts as a
// change (onDisk answers null), so an unreadable config never hides the queue.
public final class PendingNet {
	private PendingNet() {
	}

	// onDisk: a config file's current value of a key in it (the op says which file), or null when it can't be read.
	public static boolean changesSomething(List<Op> ops, BiFunction<Op, String, String> onDisk) {
		Map<List<String>, Map.Entry<Op, String>> last = new LinkedHashMap<>();
		for (Op op : ops) {
			if (op == null) {
				continue;
			}
			if (op.type() != PendingActions.Type.PATCH_JSON && op.type() != PendingActions.Type.PATCH_TOML
					&& op.type() != PendingActions.Type.PATCH_PROPERTIES || op.patches() == null) {
				return true;
			}
			op.patches().forEach((key, value) -> last.put(List.of(op.type().name(), String.valueOf(op.path()), key), new AbstractMap.SimpleEntry<>(op, value)));
		}
		for (Map.Entry<List<String>, Map.Entry<Op, String>> e : last.entrySet()) {
			String disk = onDisk.apply(e.getValue().getKey(), e.getKey().get(2));
			String staged = e.getValue().getValue();
			if (disk == null || staged == null || !SettingValues.same(disk, staged)) {
				return true;
			}
		}
		return false;
	}
}
