package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.apply.PendingActions;
import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

// The journal changes for ops just merged into pending.json (review H5): recorded after the merge, with the id and
// group each change has there, so the helper's results and an undo find them. An op that repeats a staged change
// keeps the staged op's id; if the journal already has a change for it, nothing new is recorded.
public final class StagedChanges {
	private StagedChanges() {
	}

	// How config-file ops map to settings keys (docs/v0.2/SPEC.md items 3 and 7).
	public interface ConfigKeys {
		// The settings key (e.g. "sodium.performance.x") of a key inside the op's file, or null for a file RigTune doesn't know.
		String key(Op op, String keyInFile);

		// The file's current value of that key, or null when it's absent.
		String current(Op op, String keyInFile);
	}

	// discardedOpIds: staged ops the merge replaced (a newer enable of the same mod).
	public record Outcome(List<JournalChange> changes, List<String> discardedOpIds) {
	}

	// base: pending.json as it was before the merge. journaledOpIds: op ids that already have a STAGED change.
	public static Outcome of(PendingActions base, List<Op> incoming, PendingActions.Merged merged, ConfigKeys config,
			Function<Path, String> modIdOf, Set<String> journaledOpIds) {
		Set<String> recorded = new HashSet<>(journaledOpIds);
		List<JournalChange> changes = new ArrayList<>();
		for (Op op : incoming) {
			String id = op == null || op.id() == null ? null : merged.survivingIds().get(op.id());
			if (id == null || !recorded.add(id)) {
				continue;
			}
			Op staged = merged.plan().ops().stream().filter(o -> o != null && id.equals(o.id())).findFirst().orElse(op);
			switch (op.type()) {
				case ENABLE_FILE -> changes.add(JournalChange.file(JournalChange.ENABLE, Objects.requireNonNullElse(staged.modId(), op.modId()),
						fileName(op.to()), JournalChange.STAGED, id, staged.group()));
				case DISABLE_FILE -> changes.add(JournalChange.file(JournalChange.DISABLE, modIdOf.apply(Path.of(op.path())),
						fileName(op.path()), JournalChange.STAGED, id, staged.group()));
				case PATCH_JSON, PATCH_TOML, PATCH_PROPERTIES -> {
					if (op.patches() == null) {
						continue;
					}
					for (Map.Entry<String, String> patch : op.patches().entrySet()) {
						String key = config.key(op, patch.getKey());
						if (key == null) {
							continue;
						}
						String before = stagedValue(base, op, patch.getKey());
						if (before == null) {
							before = config.current(op, patch.getKey());
						}
						if (!Objects.equals(before, patch.getValue())) {
							changes.add(JournalChange.setting(key, before, patch.getValue(), JournalChange.STAGED, id));
						}
					}
				}
			}
		}
		List<String> discarded = merged.replaced().stream().map(Op::id).filter(Objects::nonNull).toList();
		return new Outcome(List.copyOf(changes), discarded);
	}

	// The value the last already-staged op for this file and key sets, or null if none does.
	private static String stagedValue(PendingActions base, Op op, String keyInFile) {
		String value = null;
		for (Op staged : base.ops()) {
			if (staged != null && staged.type() == op.type() && Objects.equals(staged.path(), op.path())
					&& staged.patches() != null && staged.patches().containsKey(keyInFile)) {
				value = staged.patches().get(keyInFile);
			}
		}
		return value;
	}

	static String fileName(String path) {
		return HistoryUpdates.fileName(path);
	}
}
