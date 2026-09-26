package io.github.chaotix345.rigtune.client.server;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

// Server-aware advice (docs/v0.4/SPEC.md 8): the live limits the connected server sent (fed by the packet mixin) and
// server-limits.json. RealController delegates serverLimits() here in one line. Skeleton from the contracts commit; the
// server-aware workstream owns it.
public final class ServerLimitsTracker {
	private final RealController controller;
	private final Path configDir;

	public ServerLimitsTracker(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	// Null when not connected (or not known yet).
	public @Nullable ServerLimits live() {
		return null;
	}
}
