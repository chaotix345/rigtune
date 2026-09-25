package io.github.chaotix345.rigtune.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;

// The only class that touches the Distant Horizons API (docs/research/v0.2/dh-iris.md §3). Only call it through
// OptionalMods, which checks that DH is loaded first. Render thread.
final class DhCompat {
	private DhCompat() {
	}

	// DhApi.Delayed fields are filled once DH has initialised.
	static boolean ready() {
		return DhApi.Delayed.configs != null;
	}

	static boolean renderingEnabled() {
		Boolean on = configs().graphics().renderingEnabled().getValue();
		return on == null || on;
	}

	// setValue(T, String) only exists from API 7.2 (DH 3.3.2), so use the one-argument form.
	static void setRenderingEnabled(boolean on) {
		if (!configs().graphics().renderingEnabled().setValue(on)) {
			throw new IllegalStateException("Distant Horizons refused renderingEnabled = " + on);
		}
	}

	private static IDhApiConfig configs() {
		IDhApiConfig configs = DhApi.Delayed.configs;
		if (configs == null) {
			throw new IllegalStateException("Distant Horizons is not initialised yet");
		}
		return configs;
	}
}
