package io.github.chaotix345.rigtune.client.footprint;

import io.github.chaotix345.rigtune.client.RealController;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

// Startup-time report (docs/v0.4/SPEC.md 13): launch-to-title per launch in startup-times.json and the Tools screen's
// line. RealController delegates startupTimes() here in one line. Skeleton from the contracts commit; the footprint
// workstream owns it.
public final class StartupTimes {
	private final RealController controller;
	private final Path configDir;

	public StartupTimes(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	// lastMs/medianMs: the last launch and the median of the last 10 (null without runs); modSetChanged: the mod set
	// differs from the previous launch's.
	public record View(@Nullable Long lastMs, @Nullable Long medianMs, int runs, boolean modSetChanged) {
		public static final View EMPTY = new View(null, null, 0, false);
	}

	public View view() {
		return View.EMPTY;
	}
}
