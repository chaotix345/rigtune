package io.github.chaotix345.rigtune.client.compat;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.benchmark.RestoreMarker;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

// Distant Horizons and Iris are optional. DhCompat and IrisCompat are only loaded after FabricLoader says the mod is
// present, and an API that doesn't match (LinkageError) counts as "not available".
public final class OptionalMods {
	public static final String DISTANT_HORIZONS = "distanthorizons";
	public static final String IRIS = "iris";

	public static final RestoreMarker.Target DH_RENDERING = new RestoreMarker.Target() {
		@Override
		public boolean loaded() {
			return dhLoaded();
		}

		@Override
		public boolean ready() {
			return dhReady();
		}

		@Override
		public void set(boolean value) {
			if (!dhLoaded()) {
				throw new IllegalStateException("Distant Horizons is not loaded");
			}
			DhCompat.restoreAfterCrash(value);
		}
	};

	public static final RestoreMarker.Target IRIS_SHADERS = new RestoreMarker.Target() {
		@Override
		public boolean loaded() {
			return irisLoaded();
		}

		@Override
		public boolean ready() {
			return irisReady();
		}

		@Override
		public void set(boolean value) {
			setShaders(value);
		}
	};

	private OptionalMods() {
	}

	public static boolean dhLoaded() {
		return FabricLoader.getInstance().isModLoaded(DISTANT_HORIZONS);
	}

	public static boolean irisLoaded() {
		return FabricLoader.getInstance().isModLoaded(IRIS);
	}

	static boolean dhReady() {
		try {
			return dhLoaded() && DhCompat.ready();
		} catch (RuntimeException | LinkageError e) {
			RigTune.LOGGER.warn("Distant Horizons API unavailable", e);
			return false;
		}
	}

	static boolean irisReady() {
		try {
			return irisLoaded() && IrisCompat.ready();
		} catch (RuntimeException | LinkageError e) {
			RigTune.LOGGER.warn("Iris API unavailable", e);
			return false;
		}
	}

	/** Distant Horizons is loaded, initialised and rendering. */
	public static boolean dhRendering() {
		try {
			return dhReady() && DhCompat.renderingEnabled();
		} catch (RuntimeException | LinkageError e) {
			RigTune.LOGGER.warn("Could not read Distant Horizons' renderingEnabled", e);
			return false;
		}
	}

	/** The API override on DH's renderingEnabled ("none" when the player's own setting applies), for diagnostics. */
	public static String dhRenderingOverride() {
		try {
			if (!dhReady()) {
				return "n/a";
			}
			Boolean value = DhCompat.apiValue();
			return value == null ? "none" : value.toString();
		} catch (RuntimeException | LinkageError e) {
			return "? (" + e + ")";
		}
	}

	// WS-S (docs/v0.5/SPEC.md 2S RW-11; an approved exception to this frozen file): the Stutter Doctor's settings check asks
	// once a second, so a failing Iris or Distant Horizons API warns once and then answers null (unknown) for the rest of the
	// game instead of a warning every second.
	private static volatile boolean irisQuietFailed;
	private static volatile boolean dhQuietFailed;

	public static @Nullable Boolean shadersInUseQuietly() {
		if (irisQuietFailed) {
			return null;
		}
		try {
			return irisLoaded() && IrisCompat.ready() && IrisCompat.shaderPackInUse();
		} catch (RuntimeException | LinkageError e) {
			irisQuietFailed = true;
			RigTune.LOGGER.warn("Could not read Iris' shader state; the Stutter Doctor stops asking", e);
			return null;
		}
	}

	public static @Nullable Boolean dhRenderingQuietly() {
		if (dhQuietFailed) {
			return null;
		}
		try {
			return dhLoaded() && DhCompat.ready() && DhCompat.renderingEnabled();
		} catch (RuntimeException | LinkageError e) {
			dhQuietFailed = true;
			RigTune.LOGGER.warn("Could not read Distant Horizons' renderingEnabled; the Stutter Doctor stops asking", e);
			return null;
		}
	}

	/** An Iris shader pack is loaded and in use. */
	public static boolean shadersInUse() {
		try {
			return irisReady() && IrisCompat.shaderPackInUse();
		} catch (RuntimeException | LinkageError e) {
			RigTune.LOGGER.warn("Could not read Iris' shader state", e);
			return false;
		}
	}

	public static void setDhRendering(boolean on) {
		if (!dhLoaded()) {
			throw new IllegalStateException("Distant Horizons is not loaded");
		}
		DhCompat.setRenderingEnabled(on);
	}

	/** Puts back the API value from before RigTune's first setDhRendering (normally none). */
	public static void restoreDhRendering() {
		if (!dhLoaded()) {
			throw new IllegalStateException("Distant Horizons is not loaded");
		}
		DhCompat.restoreRenderingEnabled();
	}

	public static void setShaders(boolean on) {
		if (!irisLoaded()) {
			throw new IllegalStateException("Iris is not loaded");
		}
		IrisCompat.setShadersEnabled(on);
	}
}
