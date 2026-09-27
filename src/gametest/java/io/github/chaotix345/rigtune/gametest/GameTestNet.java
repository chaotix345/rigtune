package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.core.model.Report;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

// Every game-test class that turns RigTune's network switch off (X1) or back on goes through here (v0.5 ws-ci). The switch
// is saved, the controller rescans (settingsChanged), and the call returns once a report built after that (a new instance,
// not the one from before the switch) is published with the matching online state: offline with the network off; with it on (and Modrinth allowed), online, from the fake
// Modrinth the production game tests run against. Flipping ClientSettings.networkEnabled alone left the report on screen
// online or offline depending on when something else rebuilt it (BenchmarkHistoryGameTest's screenshots, WS-X).
final class GameTestNet {
	private static final int TIMEOUT_TICKS = 1200;

	private GameTestNet() {
	}

	// Returns the switch as it was, for the restore.
	static boolean set(ClientGameTestContext context, RigTuneController controller, boolean on) {
		return set(context, controller, FabricLoader.getInstance().getConfigDir(), on);
	}

	static boolean set(ClientGameTestContext context, RigTuneController controller, Path configDir, boolean on) {
		boolean before = context.computeOnClient(mc -> ClientSettings.shared(configDir).networkEnabled);
		Report[] old = new Report[1];
		boolean online = context.computeOnClient(mc -> {
			old[0] = controller.report();
			ClientSettings settings = ClientSettings.shared(configDir);
			settings.networkEnabled = on;
			settings.save(configDir);
			controller.settingsChanged();
			return settings.modrinthAllowed();
		});
		context.waitFor(mc -> {
			Report report = controller.report();
			return report != null && report != old[0] && report.online() == online;
		}, TIMEOUT_TICKS);
		return before;
	}
}
