package io.github.chaotix345.rigtune.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import org.jspecify.annotations.Nullable;

// The only class that touches the Distant Horizons API (docs/research/v0.2/dh-iris.md §3). Only call it through
// OptionalMods, which checks that DH is loaded first. Render thread.
// An API value overrides the player's own setting (the "true value") until it is cleared, so RigTune remembers the API
// value from before its first change and puts exactly that back: normally none, which clearValue() restores.
final class DhCompat {
	private static boolean changed;
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
		IDhApiConfigValue<Boolean> value = rendering();
		boolean ok = apiValueBefore == null ? value.clearValue() : value.setValue(apiValueBefore);
		if (!ok) {
			throw new IllegalStateException("Distant Horizons refused to restore renderingEnabled");
		}
		changed = false;
		apiValueBefore = null;
	}

	// After a crash: an API value is kept apart from the player's saved setting, so clearing RigTune's leftover override
	// brings that setting back.
	static void clearOverride() {
		rendering().clearValue();
	}

	private static IDhApiConfigValue<Boolean> rendering() {
		IDhApiConfig configs = DhApi.Delayed.configs;
		if (configs == null) {
			throw new IllegalStateException("Distant Horizons is not initialised yet");
		}
		return configs.graphics().renderingEnabled();
	}
}
