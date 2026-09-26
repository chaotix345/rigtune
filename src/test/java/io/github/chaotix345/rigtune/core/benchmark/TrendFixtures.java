package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

// Benchmark runs for the trend tests (docs/v0.4/SPEC.md 7): a Measure run in the benchmark world at RD 12 / SD 8, 2560x1440
// windowed, shaders and Distant Horizons off, unless changed.
final class TrendFixtures {
	static final BenchmarkRecord.Context CONTEXT = new BenchmarkRecord.Context(false, false, null, 2560, 1440, false,
			BenchmarkRecord.Context.PROTOCOL).withModSet("hash-a", null);

	private TrendFixtures() {
	}

	static Run run(String id) {
		return new Run(id);
	}

	static final class Run {
		private final String id;
		private String at = "2026-09-20T10:00:00Z";
		private @Nullable Double low = 500.0;
		private double avg = 800;
		private @Nullable Double cv = 0.02;
		private String mc = "26.2";
		private String scene = "BENCHMARK_WORLD";
		private String version = "0.4.0+mc26.2";
		private int rd = 12;
		private int sd = 8;
		private BenchmarkRecord.@Nullable Context context = CONTEXT;

		private Run(String id) {
			this.id = id;
		}

		Run at(String value) {
			at = value;
			return this;
		}

		Run low(double value) {
			low = value;
			return this;
		}

		Run avg(double value) {
			avg = value;
			return this;
		}

		Run noResult() {
			low = null;
			return this;
		}

		Run cv(@Nullable Double value) {
			cv = value;
			return this;
		}

		Run mc(String value) {
			mc = value;
			return this;
		}

		Run scene(String value) {
			scene = value;
			return this;
		}

		Run version(String value) {
			version = value;
			return this;
		}

		Run rd(int value) {
			rd = value;
			return this;
		}

		Run sd(int value) {
			sd = value;
			return this;
		}

		Run context(BenchmarkRecord.@Nullable Context value) {
			context = value;
			return this;
		}

		Run size(int width, int height) {
			context = new BenchmarkRecord.Context(context.dhRendering(), context.shaders(), context.shaderPack(), width, height, context.fullscreen(),
					context.protocol(), context.modSetHash(), context.journalCursor());
			return this;
		}

		Run hash(@Nullable String value) {
			context = context.withModSet(value, context.journalCursor());
			return this;
		}

		Run cursor(@Nullable String value) {
			context = context.withModSet(context.modSetHash(), value);
			return this;
		}

		BenchmarkRecord build() {
			Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
			knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(rd, rd, null, null, null));
			knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(sd, sd, null, null, null));
			BenchmarkRecord.Result result = low == null ? null : new BenchmarkRecord.Result(avg, low, 1000 / low, 2, cv);
			return new BenchmarkRecord(id, at, version, mc, "MEASURE", scene, BenchmarkRecord.SINGLE, null, 144, true, knobs, result, Map.of(), Map.of(),
					null, false, context);
		}
	}
}
