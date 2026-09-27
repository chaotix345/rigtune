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
	// A reload that lasts keeps its window open: its RELOAD bit repeats every 5 s while the loading overlay is up.
	static final int RELOAD_REPEAT_TICKS = 100;

	private static boolean registered;
	private static boolean iris;
	private static boolean dh;
	private static final State STATE = new State();
	// The session whose check threw: no more checks (or warnings) until another session starts.
	private static StutterMonitor.@Nullable Capture failed;

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
			Boolean shaders = OptionalMods.irisLoaded() ? OptionalMods.shadersInUseQuietly() : null;
			if (shaders != null) {
				out.put(StutterReport.SHADERS, shaders.toString());
			}
			Boolean dhRendering = OptionalMods.dhLoaded() ? OptionalMods.dhRenderingQuietly() : null;
			if (dhRendering != null) {
				out.put(StutterReport.DH_RENDERING, dhRendering.toString());
			}
			return out;
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: could not read the session's settings", e);
			return null;
		}
	}

	// The listener (package-private for SettingsWatchTest).
	static void tick(Minecraft minecraft) {
		StutterMonitor.Capture session = StutterMonitor.session();
		if (session == null || session == failed) {
			STATE.disarm();
			return;
		}
		try {
			long now = System.nanoTime();
			int changed = STATE.check(minecraft.options.renderDistance().get(), minecraft.options.simulationDistance().get(), minecraft.gui.overlay() != null,
					now);
			if ((iris || dh) && STATE.optionalDue(changed)) {
				// null: that API failed (it warned once); it isn't asked again.
				Boolean shaders = iris ? OptionalMods.shadersInUseQuietly() : null;
				Boolean dhRendering = dh ? OptionalMods.dhRenderingQuietly() : null;
				iris &= shaders != null;
				dh &= dhRendering != null;
				changed |= STATE.checkOptional(shaders, dhRendering, now);
			}
			if (changed != 0) {
				StutterMonitor.event(StutterRings.SETTINGS_CHANGED, STATE.since(), changed | STATE.leadMillis(now) << StutterRings.SETTINGS_LEAD_SHIFT);
			}
		} catch (RuntimeException e) {
			failed = session;
			STATE.disarm();
			RigTune.LOGGER.warn("Stutter Doctor: the settings check failed; settings changes aren't tagged in this session", e);
		}
	}

	// For StutterGameTest (render thread, a session on): the check's own cost measured as FootprintGameTest times the tick
	// listeners. The loop is a small method of its own, warmed up in short calls and left until the JIT has been quiet for
	// 200 ms (at most 3 s); then COST_BLOCKS blocks of `calls` checks, and the same blocks of an empty loop as the control.
	// Returns {nanos over every block, checks timed, bytes allocated summed over the blocks, the control's bytes}.
	static final int COST_BLOCKS = 20;

	static long[] cost(Minecraft minecraft, int calls) {
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		java.lang.management.CompilationMXBean jit = java.lang.management.ManagementFactory.getCompilationMXBean();
		for (int round = 0; round < 20; round++) {
			checks(minecraft, 2_000);
			control(2_000);
		}
		long waitStart = System.nanoTime();
		long quietSince = waitStart;
		long compiled = jit.getTotalCompilationTime();
		while (System.nanoTime() - quietSince < 200_000_000L && System.nanoTime() - waitStart < 3_000_000_000L) {
			checks(minecraft, 2_000);
			control(2_000);
			long now = jit.getTotalCompilationTime();
			if (now != compiled) {
				compiled = now;
				quietSince = System.nanoTime();
			}
		}
		long nanos = 0;
		long bytes = 0;
		long controlBytes = 0;
		for (int block = 0; block < COST_BLOCKS; block++) {
			long before = threads.getCurrentThreadAllocatedBytes();
			long start = System.nanoTime();
			checks(minecraft, calls);
			nanos += System.nanoTime() - start;
			long after = threads.getCurrentThreadAllocatedBytes();
			control(calls);
			long controlAfter = threads.getCurrentThreadAllocatedBytes();
			bytes += after - before;
			controlBytes += controlAfter - after;
		}
		return new long[]{nanos, (long) COST_BLOCKS * calls, bytes, controlBytes};
	}

	private static void checks(Minecraft minecraft, int calls) {
		for (int i = 0; i < calls; i++) {
			tick(minecraft);
		}
	}

	private static int controlSink;

	private static void control(int calls) {
		int sink = 0;
		for (int i = 0; i < calls; i++) {
			sink += i;
		}
		controlSink += sink;
	}

	// The comparison, free of Minecraft types (SettingsWatchTest). The first check of a session only takes the values; a
	// change's event is dated when the old value was last seen (the tick before, or the last Iris/DH read), so the 10 s
	// window also covers what happened between the two looks.
	static final class State {
		private boolean armed;
		private int renderDistance;
		private int simulationDistance;
		private boolean overlay;
		private int overlayTicks;
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
					overlayTicks = 0;
				} else if (overlay && ++overlayTicks >= RELOAD_REPEAT_TICKS) {
					changed |= RELOAD;
					overlayTicks = 0;
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

		// null: not known (its mod isn't there, or its API failed): no change, the last value kept.
		int checkOptional(@Nullable Boolean shaders, @Nullable Boolean dhRendering, long now) {
			sinceOptional = 0;
			int changed = 0;
			if (optionalArmed) {
				if (shaders != null && shaders != this.shaders) {
					changed |= SHADERS;
				}
				if (dhRendering != null && dhRendering != this.dhRendering) {
					changed |= DH_RENDERING;
				}
				if (changed != 0) {
					since = Math.min(since, lastOptional);
				}
			}
			optionalArmed = true;
			if (shaders != null) {
				this.shaders = shaders;
			}
			if (dhRendering != null) {
				this.dhRendering = dhRendering;
			}
			lastOptional = now;
			return changed;
		}

		// When the last change's old value was last seen (nanos).
		long since() {
			return since;
		}

		// How long before `now` that was, in whole milliseconds.
		long leadMillis(long now) {
			return Math.max(0, now - since) / 1_000_000L;
		}
	}
}
