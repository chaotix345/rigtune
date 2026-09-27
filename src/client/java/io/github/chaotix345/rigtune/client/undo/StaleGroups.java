package io.github.chaotix345.rigtune.client.undo;

import io.github.chaotix345.rigtune.core.apply.PendingActions.Op;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.Set;

// docs/v0.5/SPEC.md 2H RW-3: at each rebuild, next to Staging.dropQueuedUpdates (on the rebuild's worker), a staged group
// that can never run (its download gone, or its mod already loaded from another jar) is unstaged; the render thread then
// shows one status for it. Contracts stubs (WS-K): nothing dropped, no status, until WS-H fills them in.
public final class StaleGroups {
	private StaleGroups() {
	}

	// The ops dropped from pending.json (empty: none). loaded: the loaded mod ids (ModScanner.loadedIds()).
	public static List<Op> drop(Staging staging, Set<String> loaded) throws IOException {
		return List.of();
	}

	// The status line for what drop() dropped, or null for none.
	public static @Nullable Component status(List<Op> dropped, List<InstalledMod> scanned) {
		return null;
	}
}
