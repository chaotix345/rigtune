package io.github.chaotix345.rigtune.core.history;

import java.util.List;

// What an undo would do, for the confirmation screen, and what RigTuneController.undo(plan) then carries out. Execution
// re-checks each item and skips (with a reason) any whose state changed since the plan was made.
public record UndoPlan(boolean all, List<Item> items) {
	public enum Action {
		// Put a setting or a mod file back (settings now; mod files and config files after a restart).
		REVERT,
		// Drop a change that is still staged in pending.json (its whole group).
		DISCARD_STAGED,
		// Nothing is done; reason says why.
		SKIP
	}

	public record Item(String description, Action action, String reason, boolean needsRestart) {
	}

	public UndoPlan {
		items = items == null ? List.of() : List.copyOf(items);
	}

	public boolean isEmpty() {
		return items.stream().allMatch(item -> item.action() == Action.SKIP);
	}
}
