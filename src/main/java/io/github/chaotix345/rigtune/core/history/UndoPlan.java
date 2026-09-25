package io.github.chaotix345.rigtune.core.history;

import io.github.chaotix345.rigtune.core.model.Text;

import java.util.List;

// What an undo would do, for the confirmation screen, and what RigTuneController.undo(plan) then carries out. Execution
// re-checks each item and skips (with a reason) any whose state changed since the plan was made.
// undoOf: the undone entry's id, "all", or null when there is nothing to undo. at: when the undone apply was (Undo
// last). problem: a translation key saying why no plan could be made (null normally).
public record UndoPlan(boolean all, String undoOf, List<Item> items, String at, String problem) {
	public enum Action {
		// Put a setting or a mod file back (settings now; mod files and config files after a restart).
		REVERT,
		// Drop a change that is still staged in pending.json (its whole group).
		DISCARD_STAGED,
		// Nothing is done; reason says why.
		SKIP
	}

	// changeIds: the journal changes this item covers; opIds: for DISCARD_STAGED, every pending op its group removal
	// drops. Execution re-plans exactly these changes (review M8). descriptionText/reasonText (docs/v0.3/SPEC.md item 9):
	// what the Undo screen shows, translated where the language has the key; description/reason are their English.
	public record Item(String description, Action action, String reason, boolean needsRestart, List<String> changeIds, List<String> opIds,
			Text descriptionText, Text reasonText) {
		public Item {
			changeIds = changeIds == null ? List.of() : List.copyOf(changeIds);
			opIds = opIds == null ? List.of() : List.copyOf(opIds);
			descriptionText = descriptionText != null ? descriptionText : Text.literal(description);
			reasonText = reasonText != null || reason == null ? reasonText : Text.literal(reason);
		}

		public Item(String description, Action action, String reason, boolean needsRestart, List<String> changeIds, List<String> opIds) {
			this(description, action, reason, needsRestart, changeIds, opIds, null, null);
		}

		public Item(String description, Action action, String reason, boolean needsRestart) {
			this(description, action, reason, needsRestart, List.of(), List.of());
		}

		// reason: null for none.
		public static Item of(Text description, Action action, Text reason, boolean needsRestart, List<String> changeIds, List<String> opIds) {
			return new Item(description.english(), action, reason == null ? null : reason.english(), needsRestart, changeIds, opIds, description, reason);
		}
	}

	public UndoPlan {
		items = items == null ? List.of() : List.copyOf(items);
	}

	public UndoPlan(boolean all, String undoOf, List<Item> items) {
		this(all, undoOf, items, null, null);
	}

	public UndoPlan(boolean all, List<Item> items) {
		this(all, all ? UndoPlanner.ALL : null, items);
	}

	// No plan: problem says why (a translation key).
	public static UndoPlan unavailable(boolean all, String problem) {
		return new UndoPlan(all, null, List.of(), null, problem);
	}

	public boolean isEmpty() {
		return items.stream().allMatch(item -> item.action() == Action.SKIP);
	}
}
