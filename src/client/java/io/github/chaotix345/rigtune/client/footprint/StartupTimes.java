package io.github.chaotix345.rigtune.client.footprint;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.core.awareness.AwarenessStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTimesStore;
import io.github.chaotix345.rigtune.core.footprint.StartupTrend;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

// Startup-time report (docs/v0.4/SPEC.md 13): launch-to-title per launch in startup-times.json and the Tools screen's
// line. Launch-to-title is RuntimeMXBean.getUptime() (JVM start) when the first TitleScreen initialises, once per JVM.
// Fabric Loader times no mod, so this is a trend only; nothing here names a mod. RealController delegates
// startupTimes() here in one line.
// v0.5 (docs/v0.5/SPEC.md 9, C18): the view also carries StartupTrend's assessment, computed with the summary on the same
// worker path (startup-times.json unchanged), and this launch's log line names it. The acknowledged launch-time regressions
// (awareness.json's acknowledgedStartupRegressions) are kept in memory: read with the view, only for a SLOWER launch, so
// the notice source never reads a file (X8).
public final class StartupTimes {
	private static final AtomicBoolean RECORDED = new AtomicBoolean();

	private final RealController controller;
	private final Path configDir;
	private @Nullable StartupTimesStore store;
	private volatile @Nullable View cached;
	private final Set<String> acknowledged = ConcurrentHashMap.newKeySet();

	public StartupTimes(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	// lastMs/medianMs: the last launch and the median of the last 10 (null without runs); modSetChanged: the mod set
	// differs from the previous launch's; assessment: the latest launch against the usual (v0.5; null when not computed).
	public record View(@Nullable Long lastMs, @Nullable Long medianMs, int runs, boolean modSetChanged, StartupTrend.@Nullable Assessment assessment) {
		public static final View EMPTY = new View(null, null, 0, false, null);

		public View(@Nullable Long lastMs, @Nullable Long medianMs, int runs, boolean modSetChanged) {
			this(lastMs, medianMs, runs, modSetChanged, null);
		}
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
			View view = cached;
			RigTune.LOGGER.info("Launch to title screen: {} ms ({} mods){}; launch-time trend: {}", ms, topLevel,
					saved == Saved.OK ? "" : "; not saved to " + StartupTimesStore.FILE_NAME + " (" + saved + ")",
					view == null || view.assessment() == null ? "none" : StartupTrend.describe(view.assessment()));
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

	// The worker after recording; public for the game tests, which seed startup-times.json.
	public synchronized void refresh() {
		cached = summarize();
	}

	// The view as last computed, or null before one was: never reads a file (the notice source's read).
	public @Nullable View computed() {
		return cached;
	}

	private View summarize() {
		List<StartupTimesStore.Run> runs = store().runs();
		StartupTimesStore.Summary s = StartupTimesStore.summarize(runs);
		StartupTrend.Assessment assessment = StartupTrend.assess(runs);
		String key = StartupTrend.key(assessment);
		if (assessment.slower() && key != null && !acknowledged.contains(key) && AwarenessStore.shared(configDir).acknowledgedStartupRegressions().contains(key)) {
			acknowledged.add(key);
		}
		return new View(s.lastMs(), s.medianMs(), s.runs(), s.modSetChanged(), assessment);
	}

	// Whether the player acknowledged this launch's regression (Got it): in memory, filled from awareness.json with the view.
	public boolean acknowledged(String key) {
		return acknowledged.contains(key);
	}

	// Got it: not shown again for this launch, here and at later launches (awareness.json, like TrendService.acknowledge).
	public void acknowledge(String key) {
		acknowledged.add(key);
		if (!AwarenessStore.shared(configDir).acknowledgeStartupRegression(key)) {
			RigTune.LOGGER.warn("Could not remember the acknowledged launch-time regression in {}", AwarenessStore.FILE_NAME);
		}
	}

	private synchronized StartupTimesStore store() {
		if (store == null) {
			store = new StartupTimesStore(configDir);
		}
		return store;
	}
}
