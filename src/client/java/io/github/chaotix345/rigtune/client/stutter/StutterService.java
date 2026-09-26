package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.ClientSettings;
import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.SettingsSaver;
import io.github.chaotix345.rigtune.client.probe.HardwareProbe;
import io.github.chaotix345.rigtune.client.probe.Probes;
import io.github.chaotix345.rigtune.client.probe.SettingsBridge;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.HardwareProfile;
import io.github.chaotix345.rigtune.core.model.Impact;
import io.github.chaotix345.rigtune.core.model.InstalledMod;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import io.github.chaotix345.rigtune.core.recommend.SettingValues;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.store.JsonStateFile;
import io.github.chaotix345.rigtune.core.stutter.StutterAdvisor;
import io.github.chaotix345.rigtune.core.stutter.StutterAnalyzer;
import io.github.chaotix345.rigtune.core.stutter.StutterReport;
import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import io.github.chaotix345.rigtune.core.stutter.StutterStore;
import io.github.chaotix345.rigtune.core.stutter.StutterSummary;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// Stutter Doctor (docs/v0.4/SPEC.md 5): the opt-in session monitor (settings.json stutterMonitor), stutter.json and the
// analysis behind StutterScreen. RealController delegates every C4 stutter method here in one line; StutterHooks calls
// tick() and the benchmark methods. The session capture runs only while the setting is on and a world is loaded; leaving
// the world (or turning the monitor off) ends it: its summary goes to stutter.json and the buffers are released.
// Analysis runs on Probes.EXECUTOR from copies taken on the render thread. Nothing here touches the network.
public final class StutterService {
	static final long LIVE_REFRESH_NANOS = 5_000_000_000L;
	static final String DEFER_MODE = "sodium.performance.chunk_build_defer_mode";
	private static final long MIB = 1024 * 1024;

	private final RealController controller;
	private final Path configDir;
	private @Nullable StutterStore store;

	private record Analysis(StutterMonitor.@Nullable Capture capture, StutterReport report, List<StutterAdvisor.Fired> advice) {
	}

	private enum Saved { UNKNOWN, LOADING, DONE }

	// What the analysis needs about the machine, taken on the render thread.
	private record Machine(@Nullable RulesDocument rules, @Nullable HardwareProfile hardware, @Nullable List<InstalledMod> mods, SettingsSnapshot settings,
			Goal goal) {
	}

	// Render thread.
	private boolean analysing;
	private long lastAnalysis;
	private boolean sessionPausedForBenchmark;
	private volatile @Nullable Analysis live;
	private volatile @Nullable Analysis saved;
	private volatile Saved savedState = Saved.UNKNOWN;
	private volatile @Nullable StutterReport lastBenchmark;
	// stutter.json work runs in order on the shared executor (a save queued before a Clear never lands after it); a Clear
	// bumps the generation so an older save doesn't bring its summary back on screen.
	private CompletableFuture<Void> io = CompletableFuture.completedFuture(null);
	private volatile int generation;

	public StutterService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	private synchronized StutterStore store() {
		if (store == null) {
			store = new StutterStore(configDir);
		}
		return store;
	}

	// Render thread (StutterScreen). While a session runs, its analysis refreshes every 5 s.
	public StutterView view() {
		StutterMonitor.Capture session = StutterMonitor.session();
		ClientSettings settings = controller.settings();
		Analysis shown;
		boolean isLive = false;
		if (session != null) {
			refreshLive(session);
			Analysis a = live;
			isLive = a != null && a.capture() == session;
			shown = isLive ? a : null;
		} else {
			loadSaved();
			shown = saved;
		}
		return new StutterView(settings.stutterMonitor, session != null, session != null && session.paused(), session != null && shown == null && analysing,
				isLive, shown == null ? null : shown.report(), shown == null ? List.of() : shown.advice());
	}

	public void setMonitor(boolean on) {
		if (on) {
			StutterHooks.retry();
		}
		ClientSettings settings = controller.settings();
		if (settings.stutterMonitor != on) {
			settings.stutterMonitor = on;
			SettingsSaver.shared().save(settings, configDir);
		}
		Minecraft minecraft = controller.minecraft();
		if (minecraft != null) {
			tick(minecraft);
		}
	}

	public void pause(boolean paused) {
		StutterMonitor.Capture session = StutterMonitor.session();
		if (session != null && session.paused() != paused) {
			session.paused = paused;
			StutterMonitor.event(paused ? StutterRings.PAUSE_BEGIN : StutterRings.PAUSE_END, System.nanoTime(), 0);
		}
	}

	// Drops the saved summaries and starts the running session afresh.
	public void clear() {
		StutterMonitor.Capture session = StutterMonitor.session();
		if (session != null) {
			StutterCapture.stop(session);
			StutterCapture.startSession();
		}
		live = null;
		saved = null;
		savedState = Saved.DONE;
		generation++;
		io(() -> store().clear());
	}

	private synchronized void io(Runnable task) {
		io = io.handle((ignored, error) -> null).thenRunAsync(() -> safely(task), Probes.EXECUTOR);
	}

	public String summary() {
		Analysis a = StutterMonitor.session() != null ? live : saved;
		return a == null ? "" : StutterSummary.text(a.report(), a.advice());
	}

	// END_CLIENT_TICK (render thread): start a session when the monitor is on and a world is loaded, end it otherwise.
	void tick(Minecraft minecraft) {
		boolean want = controller.settings().stutterMonitor && minecraft.level != null;
		StutterMonitor.Capture session = StutterMonitor.session();
		if (want && session == null) {
			StutterCapture.startSession();
			live = null;
		} else if (!want && session != null) {
			end(session, minecraft, false);
		}
	}

	// CLIENT_STOPPING: the running session is saved right away, on this thread, after any save still queued (leaving the
	// world and quitting at once must not lose that session).
	void shutdown(Minecraft minecraft) {
		CompletableFuture<Void> pending;
		synchronized (this) {
			pending = io;
		}
		try {
			pending.get(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException e) {
			RigTune.LOGGER.warn("Stutter Doctor: a queued session save didn't finish before quitting", e);
		}
		StutterMonitor.Capture session = StutterMonitor.session();
		if (session != null) {
			end(session, minecraft, true);
		}
		StutterMonitor.Capture bench = StutterMonitor.benchmark();
		if (bench != null) {
			StutterCapture.stop(bench);
		}
	}

	private void end(StutterMonitor.Capture session, Minecraft minecraft, boolean now) {
		StutterCapture.Copy copy = StutterCapture.stop(session);
		live = null;
		savedState = Saved.DONE;
		Machine machine = machine(minecraft);
		int gen = generation;
		Runnable save = () -> {
			Analysis a = analyze(copy, machine);
			JsonStateFile.Saved result = store().add(a.report());
			if (result == JsonStateFile.Saved.OK && gen == generation) {
				saved = a;
			}
			RigTune.LOGGER.info("Stutter Doctor: session saved ({}): {} spikes in {} s of gameplay, {}, GC offset {} ms", result,
					a.report().spikes().total(), Math.round(a.report().gameplaySeconds()), phases(copy), a.report().facts().gcOffsetMs());
		};
		if (now) {
			safely(save);
		} else {
			io(save);
		}
	}

	// The benchmark's capture (BenchmarkController, render thread): on during its sweeps only. A running session pauses
	// for the whole run (its sweeps are the benchmark's, not play) and resumes when it ends.
	void benchmarkSweep(boolean recording) {
		StutterMonitor.Capture bench = StutterMonitor.benchmark();
		if (bench == null && recording) {
			bench = StutterCapture.startBenchmark();
			StutterMonitor.Capture session = StutterMonitor.session();
			if (session != null && !session.paused()) {
				pause(true);
				sessionPausedForBenchmark = true;
			}
		}
		if (bench != null) {
			bench.paused = !recording;
		}
	}

	// The benchmark ended: its capture is analysed here (it's small) for the result screen's line; a finished run's
	// summary is also saved to stutter.json.
	void benchmarkFinished(Minecraft minecraft, boolean keep) {
		if (sessionPausedForBenchmark) {
			sessionPausedForBenchmark = false;
			pause(false);
		}
		StutterMonitor.Capture bench = StutterMonitor.benchmark();
		lastBenchmark = null;
		if (bench == null) {
			return;
		}
		StutterCapture.Copy copy = StutterCapture.stop(bench);
		if (!keep) {
			return;
		}
		Analysis a = analyze(copy, machine(minecraft));
		lastBenchmark = a.report();
		RigTune.LOGGER.info("Stutter Doctor: benchmark: {} spikes, causes {}, {}", a.report().spikes().total(), a.report().causes(), phases(copy));
		io(() -> store().add(a.report()));
	}

	@Nullable StutterReport lastBenchmark() {
		return lastBenchmark;
	}

	private void refreshLive(StutterMonitor.Capture session) {
		long now = System.nanoTime();
		Analysis a = live;
		boolean stale = a == null || a.capture() != session || now - lastAnalysis > LIVE_REFRESH_NANOS;
		Minecraft minecraft = controller.minecraft();
		if (analysing || !stale || minecraft == null) {
			return;
		}
		analysing = true;
		lastAnalysis = now;
		StutterCapture.Copy copy = StutterCapture.copy(session);
		Machine machine = machine(minecraft);
		CompletableFuture.supplyAsync(() -> analyze(copy, machine), Probes.EXECUTOR).whenComplete((result, error) -> minecraft.execute(() -> {
			analysing = false;
			if (error != null) {
				RigTune.LOGGER.warn("Stutter Doctor: analysis failed", error);
			} else if (StutterMonitor.session() == session) {
				live = new Analysis(session, result.report(), result.advice());
			}
		}));
	}

	private void loadSaved() {
		if (savedState != Saved.UNKNOWN) {
			return;
		}
		savedState = Saved.LOADING;
		io(() -> {
			try {
				StutterReport latest = store().latest();
				saved = latest == null ? null : new Analysis(null, latest, adviceFor(latest.advice(), controller.rules()));
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Stutter Doctor: could not read the saved sessions", e);
			} finally {
				savedState = Saved.DONE;
			}
		});
	}

	// A saved session keeps only advice ids; their titles and texts come from the current rules.
	static List<StutterAdvisor.Fired> adviceFor(List<String> ids, @Nullable RulesDocument rules) {
		List<StutterAdvisor.Fired> out = new ArrayList<>();
		for (String id : ids) {
			RulesDocument.AdviceRule rule = rules == null || rules.stutterAdvice == null ? null
					: rules.stutterAdvice.stream().filter(r -> id.equals(r.id)).findFirst().orElse(null);
			out.add(rule == null ? new StutterAdvisor.Fired(id, "info", Impact.LOW, id, "")
					: new StutterAdvisor.Fired(id, rule.kind == null ? "info" : rule.kind, RulesDocument.impactOf(rule.impact, Impact.LOW),
							rule.title == null ? id : rule.title, rule.text == null ? "" : rule.text));
		}
		return out;
	}

	private Machine machine(Minecraft minecraft) {
		SettingsSnapshot settings;
		try {
			settings = SettingsBridge.read(minecraft);
		} catch (RuntimeException e) {
			settings = new SettingsSnapshot(Map.of());
		}
		return new Machine(controller.rules(), controller.hardwareProfile(), controller.mods(), settings, controller.goal());
	}

	private static Analysis analyze(StutterCapture.Copy c, Machine m) {
		HardwareProfile hw = m.hardware();
		String defer = m.settings().has(DEFER_MODE) ? m.settings().get(DEFER_MODE) : null;
		boolean waits = defer != null && (SettingValues.same(defer, "ZERO_FRAMES") || SettingValues.same(defer, "ONE_FRAME"));
		StutterAnalyzer.Result result = StutterAnalyzer.analyze(new StutterAnalyzer.Input(c.frames(), c.rings(), c.startNanos(), c.endNanos(), c.startedAt(),
				c.source(), HardwareProbe.minecraftVersion(), c.collector(), Runtime.getRuntime().maxMemory() / MIB,
				hw == null || hw.totalRamMb() <= 0 ? null : hw.totalRamMb(), Runtime.getRuntime().availableProcessors(), c.phaseTiming(), waits,
				c.gcMeasured()));
		List<StutterAdvisor.Fired> advice = m.rules() == null || hw == null ? List.of()
				: StutterAdvisor.evaluate(m.rules(), StutterAdvisor.context(m.rules(), hw, m.mods(), m.settings(), m.goal(), result.facts()),
						result.report().enoughData());
		StutterReport report = result.report().withAdvice(advice.stream().map(StutterAdvisor.Fired::id).toList());
		return new Analysis(null, report, advice);
	}

	// "phase timing ok (per-frame baselines: packets 12.0 us, ticks 8.1 us, render 450.2 us; timers seen 11111)", for the
	// logs (S-M1's first-run check). The tick baseline is small at high frame rates: most frames have no tick.
	static String phases(StutterCapture.Copy copy) {
		long[] b = copy.frames().phaseBaselines();
		return String.format(java.util.Locale.ROOT, "phase timing %s (per-frame baselines: packets %.1f us, ticks %.1f us, render %.1f us; timers seen %s)",
				copy.phaseTiming() ? "ok" : "unavailable", b[0] / 1e3, b[1] / 1e3, b[2] / 1e3, Integer.toBinaryString(StutterMonitor.phaseSeen()));
	}

	private static void safely(Runnable body) {
		try {
			body.run();
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Stutter Doctor: could not save the session summary", e);
		}
	}
}
