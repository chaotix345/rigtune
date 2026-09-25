package io.github.chaotix345.rigtune.core.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.SessionResult.Measured;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

// Turns a finished session into a benchmarks.json run, and a run into what the rest of RigTune shows.
public final class BenchmarkRecords {
	private BenchmarkRecords() {
	}

	// A Measure run with a pairId is the "before" until a before with that pairId exists; then it is the "after".
	public static String phase(BenchmarkRequest request, BenchmarkHistory history) {
		if (request.mode() != BenchmarkRequest.Mode.MEASURE || request.pairId() == null) {
			return BenchmarkRecord.SINGLE;
		}
		return history.before(request.pairId()).isPresent() ? BenchmarkRecord.AFTER : BenchmarkRecord.BEFORE;
	}

	public static BenchmarkRecord of(SessionResult r, BenchmarkRequest request, String phase, String id, String createdAt,
			String rigtuneVersion, String mcVersion, BenchmarkRecord.@Nullable World world) {
		Knobs chosen = r.chosen();
		Knobs original = r.original();
		Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
		knobs.put(BenchmarkRecord.RENDER_DISTANCE, knob(chosen.renderDistance(), original.renderDistance(),
				measuredAt(r, Step.Kind.RENDER_DISTANCE, chosen)));
		knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, knob(chosen.simulationDistance(), original.simulationDistance(),
				measuredAt(r, Step.Kind.SIMULATION_DISTANCE, chosen)));
		Map<String, BenchmarkRecord.Cost> costs = new LinkedHashMap<>();
		if (r.dhCost() != null) {
			costs.put(BenchmarkRecord.DISTANT_HORIZONS, cost(r.dhCost()));
		}
		if (r.shaderCost() != null) {
			costs.put(BenchmarkRecord.SHADERS, cost(r.shaderCost()));
		}
		BenchmarkMath.Aggregate a = r.result();
		BenchmarkRecord.Result result = a == null ? null
				: new BenchmarkRecord.Result(a.avgFps(), a.onePercentLowFps(), a.p99FrameMs(), a.repeats(), a.cv());
		String pairId = request.mode() == BenchmarkRequest.Mode.MEASURE ? request.pairId() : null;
		return new BenchmarkRecord(id, createdAt, rigtuneVersion, mcVersion, request.mode().name(), request.scene().name(), phase,
				pairId, (int) Math.round(r.targetFps()), r.targetMet(), knobs, result, costs, world, r.deadlineHit());
	}

	private static @Nullable FrameStats measuredAt(SessionResult r, Step.Kind kind, Knobs knobs) {
		FrameStats found = null;
		for (Measured m : r.measurements()) {
			if (m.step().kind() == kind && m.step().knobs().equals(knobs)) {
				found = m.stats();
			}
		}
		return found;
	}

	private static BenchmarkRecord.KnobResult knob(int value, int original, @Nullable FrameStats stats) {
		return stats == null ? new BenchmarkRecord.KnobResult(value, original, null, null, null)
				: new BenchmarkRecord.KnobResult(value, original, stats.avgFps(), stats.onePercentLowFps(), stats.p99FrameMs());
	}

	private static BenchmarkRecord.Cost cost(SessionResult.Cost c) {
		return new BenchmarkRecord.Cost(c.baselineAvg(), c.baselineLow(), c.offAvg(), c.offLow());
	}

	public static BenchmarkSummary summary(BenchmarkRecord record) {
		BenchmarkRecord.KnobResult rd = record.knobs().get(BenchmarkRecord.RENDER_DISTANCE);
		BenchmarkRecord.Result result = record.result();
		return new BenchmarkSummary(record.createdAt(), record.mode(), record.scene(), record.targetFps(), rd == null ? 0 : rd.value(),
				result == null ? 0 : result.avgFps(), result == null ? 0 : result.onePercentLowFps(), record.targetMet());
	}

	public static BenchmarkMath.@Nullable Gain gain(BenchmarkRecord before, BenchmarkRecord after) {
		if (before.result() == null || after.result() == null) {
			return null;
		}
		return BenchmarkMath.gain(aggregate(before.result()), aggregate(after.result()));
	}

	private static BenchmarkMath.Aggregate aggregate(BenchmarkRecord.Result r) {
		return new BenchmarkMath.Aggregate(r.avgFps(), r.onePercentLowFps(), r.p99FrameMs(), r.repeats(), r.cv());
	}
}
