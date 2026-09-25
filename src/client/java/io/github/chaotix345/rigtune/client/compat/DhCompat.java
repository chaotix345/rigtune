package io.github.chaotix345.rigtune.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import org.jspecify.annotations.Nullable;

// The only class that touches the Distant Horizons API (docs/research/v0.2/dh-iris.md §3). Only call it through
// OptionalMods, which checks that DH is loaded first. Render thread.
// setValue on renderingEnabled also changes DH's saved setting (rendererMode in DistantHorizons.toml; seen with DH 3.3.2),
// so a restore writes the original value back and then drops the API override if there was none before.
final class DhCompat {
	private static boolean changed;
	private static boolean valueBefore;
	private static @Nullable Boolean apiValueBefore;

	private DhCompat() {
	}

	// DhApi.Delayed fields are filled once DH has initialised.
	static boolean ready() {
		return DhApi.Delayed.configs != null;
	}

	static boolean renderingEnabled() {
		Boolean on = rendering().getValue();
		return on == null || on;
	}

	// The API override on renderingEnabled, or null when the player's own setting applies.
	static @Nullable Boolean apiValue() {
		return rendering().getApiValue();
	}

	// setValue(T, String) only exists from API 7.2 (DH 3.3.2), so use the one-argument form.
	static void setRenderingEnabled(boolean on) {
		IDhApiConfigValue<Boolean> value = rendering();
		if (!changed) {
			valueBefore = renderingEnabled();
			apiValueBefore = value.getApiValue();
			changed = true;
		}
		if (!value.setValue(on)) {
			throw new IllegalStateException("Distant Horizons refused renderingEnabled = " + on);
		}
	}

	static void restoreRenderingEnabled() {
		if (!changed) {
			return;
		}
		put(apiValueBefore != null ? apiValueBefore : valueBefore, apiValueBefore == null);
		changed = false;
		apiValueBefore = null;
	}

	// After a crash mid-benchmark: DH saved the value RigTune set, so write the original back and drop the override.
	static void restoreAfterCrash(boolean on) {
		put(on, true);
	}

	private static void put(boolean on, boolean clearOverride) {
		IDhApiConfigValue<Boolean> value = rendering();
		if (!value.setValue(on)) {
			throw new IllegalStateException("Distant Horizons refused to restore renderingEnabled = " + on);
		}
		if (clearOverride) {
			value.clearValue();
		}
	}

	private static IDhApiConfigValue<Boolean> rendering() {
		IDhApiConfig configs = DhApi.Delayed.configs;
		if (configs == null) {
			throw new IllegalStateException("Distant Horizons is not initialised yet");
		}
		return configs.graphics().renderingEnabled();
	}
}
