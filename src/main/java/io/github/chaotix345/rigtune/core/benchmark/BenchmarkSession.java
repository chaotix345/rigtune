package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.SessionResult.Measured;
import io.github.chaotix345.rigtune.core.benchmark.Step.Kind;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

// Plans a benchmark's measurements (docs/v0.2/SPEC.md item 6 with plan-review amendment M16), one step at a time like
// RenderDistancePlanner, so the client can run it from ticks and tests can run it with a fake clock.
// TUNE: render distance (RenderDistancePlanner, full protocol), then simulation distance at the chosen render distance
// (quick protocol, singleplayer only), then the repeats of the chosen settings, then the Distant Horizons and shader
// cost reports (quick protocol, only for features that are on). MEASURE: the repeats of the current settings.
// A step only starts when its worst case still ends before the deadline; otherwise its stage ends and the next one
// is tried, so a slow machine loses the tail of the run, not all of it.
public final class BenchmarkSession {
	public static final double SD_MIN_IMPROVEMENT = 0.05;
	private static final int SD_STEP = 2;
	private static final int SD_POINTS = 3;

	public record TuneLimits(int minRd, int maxRd, double targetFps, boolean simulationTunable, int minSd) {
	}

	private enum Stage { RENDER_DISTANCE, SIMULATION_DISTANCE, REPEAT, COSTS, MORE_REPEATS, DONE }

	private final BenchmarkRequest.Mode mode;
	private final Knobs original;
	private final double targetFps;
	private final Timing timing;
	private final long startNanos;
	private final @Nullable RenderDistancePlanner planner;
	private final List<Integer> sdCandidates;
	private final Map<Integer, FrameStats> sdStats = new LinkedHashMap<>();
	private final List<FrameStats> repeats = new ArrayList<>();
	private final List<Measured> measurements = new ArrayList<>();
	private Stage stage;
	private Knobs chosen;
	private Knobs applied;
	private @Nullable Integer sdMet;
	private @Nullable FrameStats baseline;
	private @Nullable FrameStats dhOff;
	private @Nullable FrameStats shadersOff;
	private boolean baselineSkipped;
	private boolean dhSkipped;
	private boolean shadersSkipped;
	private boolean deadlineHit;
	private @Nullable Step pending;

	private BenchmarkSession(BenchmarkRequest.Mode mode, Knobs original, double targetFps, Timing timing, long startNanos,
			@Nullable RenderDistancePlanner planner, List<Integer> sdCandidates, Stage stage) {
		this.mode = mode;
		this.original = original;
		this.targetFps = targetFps;
		this.timing = timing;
		this.startNanos = startNanos;
		this.planner = planner;
		this.sdCandidates = List.copyOf(sdCandidates);
		this.stage = stage;
		this.chosen = original;
		this.applied = original;
	}

	public static BenchmarkSession tune(Knobs original, TuneLimits limits, Timing timing, long startNanos) {
		RenderDistancePlanner planner = new RenderDistancePlanner(limits.minRd(), limits.maxRd(), original.renderDistance(),
				limits.targetFps(), timing.maxRdSteps());
		List<Integer> sd = limits.simulationTunable() ? sdCandidates(original.simulationDistance(), limits.minSd()) : List.of();
		return new BenchmarkSession(BenchmarkRequest.Mode.TUNE, original, limits.targetFps(), timing, startNanos, planner,
				sd.size() >= 2 ? sd : List.of(), Stage.RENDER_DISTANCE);
	}

	public static BenchmarkSession measure(Knobs original, double targetFps, Timing timing, long startNanos) {
		return new BenchmarkSession(BenchmarkRequest.Mode.MEASURE, original, targetFps, timing, startNanos, null, List.of(), Stage.REPEAT);
	}

	// The current value, then 2 and 4 lower, never below the minimum.
	static List<Integer> sdCandidates(int current, int minSd) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < SD_POINTS; i++) {
			int sd = current - i * SD_STEP;
			if (sd >= minSd && !out.contains(sd)) {
				out.add(sd);
			}
		}
		return out;
	}

	public Knobs original() {
		return original;
	}

	public boolean done() {
		return stage == Stage.DONE;
	}

	/** The next step to measure; the same one until it is recorded. Empty when the session is done. */
	public Optional<Step> next(long nowNanos) {
		if (pending != null) {
			return Optional.of(pending);
		}
		while (stage != Stage.DONE) {
			Step step = candidate();
			if (step == null) {
				endStage();
				continue;
			}
			double needed = step.protocol().worstCaseSeconds() + (step.kind() == Kind.BASELINE ? timing.quickSettled().worstCaseSeconds() : 0);
			if ((nowNanos - startNanos) / 1e9 + needed > timing.deadlineSeconds()) {
				deadlineHit = true;
				skip(step);
				continue;
			}
			pending = step;
			applied = step.knobs();
			return Optional.of(step);
		}
		return Optional.empty();
	}

	public void record(Step step, FrameStats stats) {
		if (pending == null || !pending.equals(step)) {
			throw new IllegalStateException("Not the pending step: " + step);
		}
		pending = null;
		measurements.add(new Measured(step, stats));
		switch (step.kind()) {
			case RENDER_DISTANCE -> planner().record(step.knobs().renderDistance(), stats);
			case SIMULATION_DISTANCE -> {
				int sd = step.knobs().simulationDistance();
				sdStats.put(sd, stats);
				if (stats.onePercentLowFps() >= targetFps) {
					sdMet = sd;
				}
			}
			case REPEAT -> repeats.add(stats);
			case BASELINE -> baseline = stats;
			case DH_OFF -> dhOff = stats;
			case SHADERS_OFF -> shadersOff = stats;
		}
	}

	private @Nullable Step candidate() {
		return switch (stage) {
			case RENDER_DISTANCE -> {
				OptionalInt rd = planner().next();
				yield rd.isEmpty() ? null : new Step(Kind.RENDER_DISTANCE, original.withRenderDistance(rd.getAsInt()), timing.full());
			}
			case SIMULATION_DISTANCE -> {
				if (sdMet != null) {
					yield null;
				}
				Step next = null;
				for (int sd : sdCandidates) {
					if (!sdStats.containsKey(sd)) {
						next = quick(Kind.SIMULATION_DISTANCE, chosen.withSimulationDistance(sd));
						break;
					}
				}
				yield next;
			}
			// TUNE measures the cost reports after the first repeat, so a slow machine loses the second repeat (the CV)
			// before it loses the reports.
			case REPEAT -> repeats.size() < (mode == BenchmarkRequest.Mode.TUNE ? Math.min(1, timing.repeats()) : timing.repeats())
					? new Step(Kind.REPEAT, chosen, timing.full()) : null;
			case MORE_REPEATS -> repeats.size() < timing.repeats() ? new Step(Kind.REPEAT, chosen, timing.full()) : null;
			case COSTS -> costCandidate();
			case DONE -> null;
		};
	}

	private @Nullable Step costCandidate() {
		boolean wantDh = original.dhRendering() && dhOff == null && !dhSkipped;
		boolean wantShaders = original.shaders() && shadersOff == null && !shadersSkipped;
		if (!wantDh && !wantShaders) {
			return null;
		}
		if (baseline == null) {
			return baselineSkipped ? null : quick(Kind.BASELINE, chosen);
		}
		if (wantDh) {
			return quick(Kind.DH_OFF, chosen.withDhRendering(false));
		}
		return quick(Kind.SHADERS_OFF, chosen.withShaders(false));
	}

	// A quick step waits the full settle when the chunk sections rebuild first: another render distance than the one
	// in effect, or shaders toggled (an Iris reload).
	private Step quick(Kind kind, Knobs knobs) {
		boolean rebuild = knobs.renderDistance() != applied.renderDistance() || knobs.shaders() != applied.shaders();
		return new Step(kind, knobs, rebuild ? timing.quickSettled() : timing.quick());
	}

	private void skip(Step step) {
		switch (step.kind()) {
			case RENDER_DISTANCE, SIMULATION_DISTANCE -> endStage();
			// Both repeat stages end: a repeat that doesn't fit now won't fit after the cost reports either.
			case REPEAT -> {
				if (stage == Stage.REPEAT) {
					endStage();
				} else {
					stage = Stage.DONE;
				}
			}
			case BASELINE -> baselineSkipped = true;
			case DH_OFF -> dhSkipped = true;
			case SHADERS_OFF -> shadersSkipped = true;
		}
	}

	private void endStage() {
		switch (stage) {
			case RENDER_DISTANCE -> {
				chosen = original.withRenderDistance(chosenRd());
				stage = sdCandidates.isEmpty() ? Stage.REPEAT : Stage.SIMULATION_DISTANCE;
			}
			case SIMULATION_DISTANCE -> {
				chosen = chosen.withSimulationDistance(chosenSd());
				stage = Stage.REPEAT;
			}
			case REPEAT -> {
				// The simulation distance steps ran at exactly the chosen settings with the quick protocol.
				baseline = sdStats.get(chosen.simulationDistance());
				stage = mode == BenchmarkRequest.Mode.TUNE ? Stage.COSTS : Stage.DONE;
			}
			case COSTS -> stage = Stage.MORE_REPEATS;
			case MORE_REPEATS, DONE -> stage = Stage.DONE;
		}
	}

	private RenderDistancePlanner planner() {
		if (planner == null) {
			throw new IllegalStateException("No render distance search in " + mode);
		}
		return planner;
	}

	private int chosenRd() {
		if (planner == null || planner.result().measurements().isEmpty()) {
			return original.renderDistance();
		}
		return planner.result().suggestedRd();
	}

	// The highest candidate that met the target; otherwise the best one if it beats the current value clearly
	// (simulation distance is also a gameplay setting, so noise alone never lowers it).
	private int chosenSd() {
		if (sdMet != null) {
			return sdMet;
		}
		int current = original.simulationDistance();
		FrameStats currentStats = sdStats.get(current);
		if (currentStats == null) {
			return current;
		}
		int best = current;
		double bestLow = currentStats.onePercentLowFps();
		for (Map.Entry<Integer, FrameStats> e : sdStats.entrySet()) {
			if (e.getValue().onePercentLowFps() > bestLow) {
				best = e.getKey();
				bestLow = e.getValue().onePercentLowFps();
			}
		}
		return bestLow >= currentStats.onePercentLowFps() * (1 + SD_MIN_IMPROVEMENT) - 1e-9 ? best : current;
	}

	private Knobs chosenSoFar() {
		return switch (stage) {
			case RENDER_DISTANCE -> original.withRenderDistance(chosenRd());
			case SIMULATION_DISTANCE -> chosen.withSimulationDistance(chosenSd());
			default -> chosen;
		};
	}

	public SessionResult result() {
		Knobs picked = chosenSoFar();
		PlannerResult rd = planner != null ? planner.result()
				: new PlannerResult(original.renderDistance(), false, original.renderDistance(), List.of(), "Measured the current settings");
		BenchmarkMath.Aggregate aggregate = BenchmarkMath.aggregate(repeats);
		if (aggregate == null) {
			FrameStats fallback = fallback(picked);
			aggregate = fallback == null ? null : BenchmarkMath.aggregate(List.of(fallback));
		}
		return new SessionResult(mode, original, picked, targetFps, rd, measurements, aggregate,
				cost(baseline, dhOff), cost(baseline, shadersOff), deadlineHit);
	}

	// The last full-protocol measurement of these knobs, else the last quick one.
	private @Nullable FrameStats fallback(Knobs knobs) {
		FrameStats full = null;
		FrameStats any = null;
		for (Measured m : measurements) {
			if (m.step().knobs().equals(knobs)) {
				any = m.stats();
				if (m.step().protocol().equals(timing.full())) {
					full = m.stats();
				}
			}
		}
		return full != null ? full : any;
	}

	private static SessionResult.@Nullable Cost cost(@Nullable FrameStats on, @Nullable FrameStats off) {
		if (on == null || off == null) {
			return null;
		}
		return new SessionResult.Cost(on.onePercentLowFps(), on.avgFps(), off.onePercentLowFps(), off.avgFps());
	}
}
