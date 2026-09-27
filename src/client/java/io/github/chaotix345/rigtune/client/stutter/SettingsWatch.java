package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

// docs/v0.5/SPEC.md 2S RW-11: a session that spans a settings change (render or simulation distance, shaders on/off,
// Distant Horizons rendering) or a resource reload gets a SETTINGS_CHANGED event, whose next 10 s of spikes the analysis
// tags settingsChanged, and the four values at the session's start and end (StutterReport.settingsAtStart/AtEnd).
// X4.4: its own END_CLIENT_TICK listener, registered when the first session starts (never at init), never on the path
// tickHookOnVsReference times (RigTuneClient.onTick + StutterHooks.tick). Without a session it returns after one volatile
// read; with one it compares two cached ints and the loading overlay's reference (a resource reload shows vanilla's
// loading overlay), allocation-free. Iris and Distant Horizons are asked only after a reload and at most once a second.
// Iris' API has no pack name, so switching packs with shaders on isn't seen (a residual, docs/v0.5/design/ws-s.md).
final class SettingsWatch {
	static final int RENDER_DISTANCE = 1;
	static final int SIMULATION_DISTANCE = 1 << 1;
	static final int SHADERS = 1 << 2;
	static final int DH_RENDERING = 1 << 3;
	static final int RELOAD = 1 << 4;
	static final int OPTIONAL_EVERY_TICKS = 20;

	private static boolean registered;
	private static boolean iris;
	private static boolean dh;
	private static final State STATE = new State();

	private SettingsWatch() {
	}

	// A session starts (render thread): the listener is registered the first time; returns the four values now (null
	// without a game).
	static @Nullable Map<String, String> sessionStarted(@Nullable Minecraft minecraft) {
		if (minecraft == null) {
			return null;
		}
		if (!registered) {
			registered = true;
			iris = OptionalMods.irisLoaded();
			dh = OptionalMods.dhLoaded();
			ClientTickEvents.END_CLIENT_TICK.register(SettingsWatch::tick);
		}
		return values(minecraft);
	}

	// The four values now, for settingsAtStart/settingsAtEnd; null when they can't be read.
	static @Nullable Map<String, String> values(@Nullable Minecraft minecraft) {
		if (minecraft == null) {
			return null;
		}
		try {
			Map<String, String> out = new LinkedHashMap<>();
			out.put(StutterReport.RENDER_DISTANCE, Integer.toString(minecraft.options.renderDistance().get()));
			out.put(StutterReport.SIMULATION_DISTANCE, Integer.toString(minecraft.options.simulationDistance().get()));
			if (OptionalMods.irisLoaded()) {
				out.put(StutterReport.SHADERS, Boolean.toString(OptionalMods.shadersInUse()));
			}
			if (OptionalMods.dhLoaded()) {
				out.put(StutterReport.DH_RENDERING, Boolean.toString(OptionalMods.dhRendering()));
			}
			return out;
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: could not read the session's settings", e);
			return null;
		}
	}

	private static void tick(Minecraft minecraft) {
		if (StutterMonitor.session() == null) {
			STATE.disarm();
			return;
		}
		try {
			long now = System.nanoTime();
			int changed = STATE.check(minecraft.options.renderDistance().get(), minecraft.options.simulationDistance().get(), minecraft.gui.overlay() != null,
					now);
			if ((iris || dh) && STATE.optionalDue(changed)) {
				changed |= STATE.checkOptional(iris && OptionalMods.shadersInUse(), dh && OptionalMods.dhRendering(), now);
			}
			if (changed != 0) {
				StutterMonitor.event(StutterRings.SETTINGS_CHANGED, STATE.since(), changed);
			}
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: the settings check failed; settings changes aren't tagged in this session", e);
			STATE.disarm();
		}
	}

	// For StutterGameTest: `calls` checks in a row after a warm-up, as the listener runs them (render thread, a session on).
	static long[] cost(Minecraft minecraft, int calls) {
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		for (int i = 0; i < calls / 10; i++) {
			tick(minecraft);
		}
		long bytes = threads.getCurrentThreadAllocatedBytes();
		long start = System.nanoTime();
		for (int i = 0; i < calls; i++) {
			tick(minecraft);
		}
		long nanos = System.nanoTime() - start;
		return new long[]{nanos, threads.getCurrentThreadAllocatedBytes() - bytes};
	}

	// The comparison, free of Minecraft types (SettingsWatchTest). The first check of a session only takes the values; a
	// change's event is dated when the old value was last seen (the tick before, or the last Iris/DH read), so the 10 s
	// window also covers what happened between the two looks.
	static final class State {
		private boolean armed;
		private int renderDistance;
		private int simulationDistance;
		private boolean overlay;
		private long lastCheck;
		private boolean optionalArmed;
		private boolean shaders;
		private boolean dhRendering;
		private long lastOptional;
		private int sinceOptional;
		private long since;

		void disarm() {
			armed = false;
			optionalArmed = false;
		}

		// The bits of what changed since the last check (RELOAD when the loading overlay appears or goes away).
		int check(int renderDistance, int simulationDistance, boolean overlay, long now) {
			int changed = 0;
			if (!armed) {
				armed = true;
				sinceOptional = OPTIONAL_EVERY_TICKS;
			} else {
				if (renderDistance != this.renderDistance) {
					changed |= RENDER_DISTANCE;
				}
				if (simulationDistance != this.simulationDistance) {
					changed |= SIMULATION_DISTANCE;
				}
				if (overlay != this.overlay) {
					changed |= RELOAD;
				}
			}
			since = lastCheck;
			this.renderDistance = renderDistance;
			this.simulationDistance = simulationDistance;
			this.overlay = overlay;
			lastCheck = now;
			return changed;
		}

		// Iris and DH: right after a reload, else once every OPTIONAL_EVERY_TICKS checks.
		boolean optionalDue(int changed) {
			return (changed & RELOAD) != 0 || ++sinceOptional >= OPTIONAL_EVERY_TICKS;
		}

		int checkOptional(boolean shaders, boolean dhRendering, long now) {
			sinceOptional = 0;
			int changed = 0;
			if (optionalArmed) {
				if (shaders != this.shaders) {
					changed |= SHADERS;
				}
				if (dhRendering != this.dhRendering) {
					changed |= DH_RENDERING;
				}
				if (changed != 0) {
					since = Math.min(since, lastOptional);
				}
			}
			optionalArmed = true;
			this.shaders = shaders;
			this.dhRendering = dhRendering;
			lastOptional = now;
			return changed;
		}

		// When the last change's old value was last seen (nanos).
		long since() {
			return since;
		}
	}
}
