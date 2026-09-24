package io.github.chaotix345.rigtune.core.history;

import java.util.List;

// What an undo would do, for the confirmation screen. Each item is one line: a revert or a skip with its reason.
public record UndoPlan(boolean all, List<Item> items) {
	public record Item(String description, boolean willRevert, String reason, boolean needsRestart) {
	}

	public UndoPlan {
		items = items == null ? List.of() : List.copyOf(items);
	}

	public boolean isEmpty() {
		return items.stream().noneMatch(Item::willRevert);
	}
}
