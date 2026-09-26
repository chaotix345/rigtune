package io.github.chaotix345.rigtune.client.footprint;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.model.ModSetHash;
import io.github.chaotix345.rigtune.core.store.JsonStateFile.Saved;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

// Startup-time report (docs/v0.4/SPEC.md 13): launch-to-title per launch in startup-times.json and the Tools screen's
// line. Launch-to-title is RuntimeMXBean.getUptime() (JVM start) when the first TitleScreen initialises, once per JVM.
// Fabric Loader times no mod, so this is a trend only; nothing here names a mod. RealController delegates
// startupTimes() here in one line.
public final class StartupTimes {
	private static final AtomicBoolean RECORDED = new AtomicBoolean();

	private final RealController controller;
	private final Path configDir;
	private @Nullable StartupTimesStore store;
	private @Nullable View cached;

	public StartupTimes(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	// lastMs/medianMs: the last launch and the median of the last 10 (null without runs); modSetChanged: the mod set
	// differs from the previous launch's.
	public record View(@Nullable Long lastMs, @Nullable Long medianMs, int runs, boolean modSetChanged) {
		public static final View EMPTY = new View(null, null, 0, false);
	}

	// Render thread, on every title-screen init; only the first one of this JVM counts. The file is written off-thread.
	public void titleScreenShown() {
		if (!RECORDED.compareAndSet(false, true)) {
			return;
		}
		long ms;
		try {
			ms = ManagementFactory.getRuntimeMXBean().getUptime();
		} catch (RuntimeException | LinkageError e) {
			RigTune.LOGGER.debug("No JVM uptime; this launch's startup time isn't recorded", e);
			return;
		}
		CompletableFuture.runAsync(() -> record(ms), Probes.EXECUTOR);
	}

	private void record(long ms) {
		try {
			Map<String, String> mods = new HashMap<>();
			int topLevel = 0;
			for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
				if ("builtin".equals(mod.getMetadata().getType())) {
					continue;
				}
				mods.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString());
				if (mod.getContainingMod().isEmpty()) {
					topLevel++;
				}
			}
			StartupTimesStore.Run run = new StartupTimesStore.Run(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), ms,
					FabricLoader.getInstance().getRawGameVersion(), controller.modVersion(), topLevel, ModSetHash.ofLoadedMods(mods));
			Saved saved = store().record(run);
			refresh();
			RigTune.LOGGER.info("Launch to title screen: {} ms ({} mods){}", ms, topLevel,
					saved == Saved.OK ? "" : "; not saved to " + StartupTimesStore.FILE_NAME + " (" + saved + ")");
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not record this launch's startup time", e);
		}
	}

	// Synchronized with refresh(), so a view read while a launch is being recorded is never cached after it. Usually
	// refresh() has already filled it on the worker, and the Tools screen reads no file.
	public synchronized View view() {
		if (cached == null) {
			cached = summarize();
		}
		return cached;
	}

	private synchronized void refresh() {
		cached = summarize();
	}

	private View summarize() {
		StartupTimesStore.Summary s = StartupTimesStore.summarize(store().runs());
		return new View(s.lastMs(), s.medianMs(), s.runs(), s.modSetChanged());
	}

	private synchronized StartupTimesStore store() {
		if (store == null) {
			store = new StartupTimesStore(configDir);
		}
		return store;
	}
}
