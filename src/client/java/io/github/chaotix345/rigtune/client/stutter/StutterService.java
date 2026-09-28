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
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

// Stutter Doctor (docs/v0.4/SPEC.md 5): the opt-in session monitor (settings.json stutterMonitor), stutter.json and the
// analysis behind StutterScreen. RealController delegates every C4 stutter method here in one line; StutterHooks calls
// tick() and the benchmark methods. The session capture runs only while the setting is on and a world is loaded; leaving
// the world (or turning the monitor off, or a benchmark run starting) ends it: its summary goes to stutter.json and the
// buffers are released.
// Analysis runs on Probes.EXECUTOR from copies taken on the render thread. Nothing here touches the network.
public final class StutterService {
	static final long LIVE_REFRESH_NANOS = 5_000_000_000L;
	static final String DEFER_MODE = "sodium.performance.chunk_build_defer_mode";
	private static final long MIB = 1024 * 1024;

	private final RealController controller;
	private final Path configDir;
	private final Executor ioExecutor;
	private @Nullable StutterStore store;

	private record Analysis(StutterMonitor.@Nullable Capture capture, StutterReport report, List<StutterAdvisor.Fired> advice,
			@Nullable Double dhWorldGenCores) {
	}

	private enum Saved { UNKNOWN, LOADING, DONE }

	// What the analysis needs about the machine, taken on the render thread; settingsNow: the four RW-11 values.
	private record Machine(@Nullable RulesDocument rules, @Nullable HardwareProfile hardware, @Nullable List<InstalledMod> mods, SettingsSnapshot settings,
			Goal goal, @Nullable Map<String, String> settingsNow) {
	}

	// Render thread.
	private boolean analysing;
	private long lastAnalysis;
	private boolean benchmarkRunning;
	private boolean resumePaused;
	// v0.5 RW-15: the current benchmark step is left out of the capture.
	private boolean stepExcluded;
	private volatile @Nullable Analysis live;
	private volatile @Nullable Analysis saved;
	// v0.5 RW-18: the lengths (s) of the short sessions saved after `saved`.
	private volatile List<Double> savedShortSince = List.of();
	private volatile Saved savedState = Saved.UNKNOWN;
	private volatile @Nullable StutterReport lastBenchmark;
	private volatile @Nullable Double lastBenchmarkDhWorldGen;
	// stutter.json work runs in order on the shared executor (a save queued before a Clear never lands after it); a Clear
	// bumps the generation so an older save doesn't bring its summary back on screen.
	private CompletableFuture<Void> io = CompletableFuture.completedFuture(null);
	private volatile int generation;
	// Monitor sessions whose end was handled (saved, or left out around a benchmark), for the game tests.
	private final AtomicInteger sessionsEnded = new AtomicInteger();

	public StutterService(RealController controller, Path configDir) {
		this(controller, configDir, Probes.EXECUTOR);
	}

	// ioExecutor: where stutter.json's ordered chain runs (StutterServiceTest drains its own).
	StutterService(RealController controller, Path configDir, Executor ioExecutor) {
		this.controller = controller;
		this.configDir = configDir;
		this.ioExecutor = ioExecutor;
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
				isLive, shown == null ? null : shown.report(), shown == null ? List.of() : shown.advice(), session == null ? savedShortSince : List.of());
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
			try {
				StutterCapture.stop(session);
			} catch (RuntimeException e) {
				// L1's guard: detached anyway (stop's finally); Clear drops this session's data in any case.
				RigTune.LOGGER.warn("Stutter Doctor: the cleared session's capture couldn't be copied", e);
			}
			startSession(controller.minecraft());
		}
		live = null;
		saved = null;
		savedShortSince = List.of();
		savedState = Saved.DONE;
		generation++;
		io(() -> store().clear());
	}

	private synchronized void io(Runnable task) {
		io = io.handle((ignored, error) -> null).thenRunAsync(() -> safely(task), ioExecutor);
	}

	public String summary() {
		Analysis a = StutterMonitor.session() != null ? live : saved;
		return a == null ? "" : StutterSummary.text(a.report(), a.advice());
	}

	// END_CLIENT_TICK (render thread): start a session when the monitor is on and a world is loaded (not during a benchmark
	// run), end it otherwise.
	void tick(Minecraft minecraft) {
		boolean want = controller.settings().stutterMonitor && minecraft.level != null;
		StutterMonitor.Capture session = StutterMonitor.session();
		if (want && session == null && !benchmarkRunning && StutterMonitor.benchmark() == null) {
			startSession(minecraft);
			live = null;
		} else if (!want && session != null) {
			end(session, minecraft, false);
		}
	}

	// v0.5 RW-11: a session starts with the settings it starts with (SettingsWatch registers its listener the first time).
	private static StutterMonitor.Capture startSession(@Nullable Minecraft minecraft) {
		StutterMonitor.Capture session = StutterCapture.startSession();
		session.settingsAtStart = SettingsWatch.sessionStarted(minecraft);
		return session;
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
			try {
				StutterCapture.stop(bench);
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Stutter Doctor: the benchmark's capture couldn't be copied at quit", e);
			}
		}
	}

	private void end(StutterMonitor.Capture session, Minecraft minecraft, boolean now) {
		StutterCapture.Copy copy;
		try {
			copy = StutterCapture.stop(session);
		} catch (RuntimeException e) {
			// docs/v0.5/SPEC.md 2S L1 (review-10 R10-1): the capture is detached anyway (stop's finally); only this session's
			// summary is lost, and a benchmark starting or the world being left goes on.
			live = null;
			sessionsEnded.incrementAndGet();
			RigTune.LOGGER.warn("Stutter Doctor: the running session's summary was lost: its capture couldn't be copied", e);
			return;
		}
		live = null;
		Machine machine = machine(minecraft);
		int gen = generation;
		boolean aroundBenchmark = session.aroundBenchmark;
		Runnable save = () -> {
			Analysis a = analyze(copy, machine);
			try {
				if (!StutterStore.worthSaving(a.report(), aroundBenchmark)) {
					RigTune.LOGGER.info("Stutter Doctor: session not saved: {} spikes in {} s of gameplay around a benchmark run (the run's own capture is saved)",
							a.report().spikes().total(), Math.round(a.report().gameplaySeconds()));
					return;
				}
				JsonStateFile.Saved result = store().add(a.report());
				// SD-3: known only once this session is in `saved`; an unsaved one leaves the saved summaries to loadSaved().
				if (result == JsonStateFile.Saved.OK && gen == generation) {
					showSaved(a);
					savedState = Saved.DONE;
				}
				RigTune.LOGGER.info("Stutter Doctor: session saved ({}): {} spikes in {} s of gameplay, {}, GC offset {} ms", result,
						a.report().spikes().total(), Math.round(a.report().gameplaySeconds()), phases(copy), a.report().facts().gcOffsetMs());
			} finally {
				sessionsEnded.incrementAndGet();
			}
		};
		if (now) {
			safely(save);
		} else {
			io(save);
		}
	}

	// A benchmark run starts (BenchmarkController, render thread, before it changes any setting): a running session ends
	// here as on leaving the world, its summary judged against the player's own settings; no session runs until the run
	// ends (review-9 X3-1: a session's and a benchmark's rings together are over the monitorOnRetainedBytes budget; the
	// run's sweeps aren't play either).
	void benchmarkStarted(Minecraft minecraft) {
		benchmarkRunning = true;
		stepExcluded = false;
		endSessionForBenchmark(minecraft);
	}

	// The benchmark's capture (render thread): on during its sweeps only. It starts at the first sweep, after any session
	// ended, so only one capture's rings are ever held.
	void benchmarkSweep(Minecraft minecraft, boolean recording) {
		StutterMonitor.Capture bench = StutterMonitor.benchmark();
		if (bench == null && recording) {
			benchmarkRunning = true;
			endSessionForBenchmark(minecraft);
			bench = StutterCapture.startBenchmark();
			// Created paused, like every gap between sweeps.
			StutterMonitor.event(StutterRings.PAUSE_BEGIN, System.nanoTime(), 0);
		}
		if (bench != null) {
			pauseBenchmark(bench, !recording || stepExcluded);
		}
	}

	// docs/v0.5/SPEC.md 2B RW-15 (the contracts' seam; StutterHooks.benchmarkStepExcluded says how BenchmarkController calls
	// it): true for a step left out, false at its end. It never starts or resumes the capture: the capture stays paused
	// through the step, and the next benchmarkSweep(true) records again.
	void benchmarkStepExcluded(boolean excluded) {
		stepExcluded = excluded;
		StutterMonitor.Capture bench = StutterMonitor.benchmark();
		if (bench != null && excluded) {
			pauseBenchmark(bench, true);
		}
	}

	// v0.5 (docs/v0.5/SPEC.md 2S, RW-6's bucket): each flip of the benchmark capture's recording is an event too, as the
	// session's Pause is, so the analysis knows which sampler windows fell in the sweeps.
	private static void pauseBenchmark(StutterMonitor.Capture bench, boolean paused) {
		if (bench.paused != paused) {
			bench.paused = paused;
			StutterMonitor.event(paused ? StutterRings.PAUSE_BEGIN : StutterRings.PAUSE_END, System.nanoTime(), 0);
		}
	}

	private void endSessionForBenchmark(Minecraft minecraft) {
		StutterMonitor.Capture session = StutterMonitor.session();
		if (session != null) {
			// Saved only with 2 minutes of gameplay (review-8 P5A-F3): the benchmark world's settle frames aren't a session.
			session.aroundBenchmark = true;
			resumePaused = session.paused();
			end(session, minecraft, false);
		}
	}

	// The benchmark ended: its capture is released, a fresh session starts if the monitor is on in a world (it follows a
	// benchmark run: P5A-F3 as above; paused if the ended one was), then the run's capture is analysed here (it's small) for
	// the result screen's line; a finished run's summary is also saved to stutter.json.
	void benchmarkFinished(Minecraft minecraft, boolean keep) {
		benchmarkRunning = false;
		StutterMonitor.Capture bench = StutterMonitor.benchmark();
		lastBenchmark = null;
		lastBenchmarkDhWorldGen = null;
		stepExcluded = false;
		StutterCapture.Copy copy = null;
		if (bench != null) {
			try {
				copy = StutterCapture.stop(bench);
			} catch (RuntimeException e) {
				// L1's guard: detached anyway; only the run's stutter line is lost, and a session can still start.
				RigTune.LOGGER.warn("Stutter Doctor: the benchmark's capture couldn't be copied; its summary is lost", e);
			}
		}
		boolean paused = resumePaused;
		resumePaused = false;
		// Not after a failed tick: StutterHooks.tick, which ends a session on leaving the world, is off until the monitor is
		// turned on again.
		if (!StutterHooks.failed() && controller.settings().stutterMonitor && minecraft.level != null && StutterMonitor.session() == null) {
			startSession(minecraft).aroundBenchmark = true;
			live = null;
			if (paused) {
				pause(true);
			}
		}
		if (copy == null || !keep) {
			return;
		}
		Analysis a = analyze(copy, machine(minecraft));
		lastBenchmark = a.report();
		lastBenchmarkDhWorldGen = a.dhWorldGenCores();
		RigTune.LOGGER.info("Stutter Doctor: benchmark: {} spikes, causes {}, {}", a.report().spikes().total(), a.report().causes(), phases(copy));
		int gen = generation;
		// The newest saved summary is what StutterScreen shows when no session runs (review-8 P5A-F3).
		io(() -> {
			if (store().add(a.report()) == JsonStateFile.Saved.OK && gen == generation) {
				showSaved(a);
				savedState = Saved.DONE;
			}
		});
	}

	@Nullable StutterReport lastBenchmark() {
		return lastBenchmark;
	}

	@Nullable Double lastBenchmarkDhWorldGenCores() {
		return lastBenchmarkDhWorldGen;
	}



	int sessionsEnded() {
		return sessionsEnded.get();
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
				live = new Analysis(session, result.report(), result.advice(), result.dhWorldGenCores());
			}
		}));
	}

	private void loadSaved() {
		if (savedState != Saved.UNKNOWN) {
			return;
		}
		savedState = Saved.LOADING;
		// SD-4: a Clear pressed while this load waits in the queue wins.
		int gen = generation;
		io(() -> {
			try {
				if (gen == generation) {
					showSaved(null);
				}
			} catch (RuntimeException e) {
				RigTune.LOGGER.warn("Stutter Doctor: could not read the saved sessions", e);
			} finally {
				savedState = Saved.DONE;
			}
		});
	}

	// v0.5 RW-18 (the io chain): the saved summary to show (StutterStore.shown: the newest with enough data, so a quick
	// re-join doesn't hide the last real session) and the short sessions since it. justSaved: the analysis just written.
	private void showSaved(@Nullable Analysis justSaved) {
		StutterStore.Shown shown = StutterStore.shown(store().sessions());
		StutterReport r = shown.report();
		saved = r == null ? null
				: justSaved != null && r.startedAt().equals(justSaved.report().startedAt()) && r.source().equals(justSaved.report().source()) ? justSaved
				: new Analysis(null, r, adviceFor(r.advice(), controller.rules()), null);
		savedShortSince = shown.shortSince().stream().map(StutterReport::sessionSeconds).toList();
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
		return new Machine(controller.rules(), controller.hardwareProfile(), controller.mods(), settings, controller.goal(), SettingsWatch.values(minecraft));
	}

	private static Analysis analyze(StutterCapture.Copy c, Machine m) {
		HardwareProfile hw = m.hardware();
		String defer = m.settings().has(DEFER_MODE) ? m.settings().get(DEFER_MODE) : null;
		boolean waits = defer != null && (SettingValues.same(defer, "ZERO_FRAMES") || SettingValues.same(defer, "ONE_FRAME"));
		StutterAnalyzer.Result result = StutterAnalyzer.analyze(new StutterAnalyzer.Input(c.frames(), c.rings(), c.startNanos(), c.endNanos(), c.startedAt(),
				c.source(), HardwareProbe.minecraftVersion(), c.collector(), Runtime.getRuntime().maxMemory() / MIB,
				hw == null || hw.totalRamMb() <= 0 ? null : hw.totalRamMb(), Runtime.getRuntime().availableProcessors(), c.phaseTiming(), waits,
				c.gcMeasured(), c.idleNanos()));
		List<StutterAdvisor.Fired> advice = m.rules() == null || hw == null ? List.of()
				: StutterAdvisor.evaluate(m.rules(), StutterAdvisor.context(m.rules(), hw, m.mods(), m.settings(), m.goal(), result.facts()),
						result.report().enoughData());
		StutterReport report = result.report().withAdvice(advice.stream().map(StutterAdvisor.Fired::id).toList());
		// v0.5 RW-11: a session's settings at its start and now (its end, or the live view's moment); the advice above used
		// the ones now.
		if (c.settingsAtStart() != null) {
			report = report.withSettings(c.settingsAtStart(), m.settingsNow());
		}
		return new Analysis(null, report, advice, result.dhWorldGenCores());
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
