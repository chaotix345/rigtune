package io.github.chaotix345.rigtune.core.tryit;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRecord;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.history.JournalChange;
import io.github.chaotix345.rigtune.core.history.JournalEntry;
import org.jspecify.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Try it fixtures (docs/v0.5/SPEC.md 6): Measure runs in the benchmark world at RD 12 / SD 8, 2560x1440 windowed, shaders
// and Distant Horizons off, mod set "hash-a", unless changed; journal entries; a restart try of Sodium's defer mode.
final class TryItFixtures {
	static final String KEY = "sodium.performance.chunk_build_defer_mode";
	static final String ENTRY = "e-try";
	static final String SESSION = "session-a";

	private TryItFixtures() {
	}

	static TryIt restartTry() {
		return tryOf(KEY, TryIt.Kind.RESTART, BenchmarkRequest.Scene.BENCHMARK_WORLD);
	}

	static TryIt tryOf(String key, TryIt.Kind kind, BenchmarkRequest.Scene scene) {
		Map<String, String> before = new LinkedHashMap<>();
		before.put("vanilla.renderDistance", "12");
		before.put("vanilla.particles", "0");
		before.put("vanilla.maxFps", "120");
		before.put(KEY, "ALWAYS");
		return TryIt.of(ENTRY, "setting:" + key, key, "ALWAYS", "ONE_FRAME", kind, scene, "2026-09-20T10:00:00Z", SESSION, "0.5.0", "26.2", before);
	}

	static Run run(String id) {
		return new Run(id);
	}

	static JournalEntry apply(String id, String at, String status) {
		return entry(id, at, JournalEntry.APPLY, null, JournalChange.setting(KEY, "ALWAYS", "ONE_FRAME", status, "op-" + id));
	}

	static JournalEntry entry(String id, String at, String kind, @Nullable String undoOf, JournalChange... changes) {
		return new JournalEntry(id, at, kind, "0.5.0", "26.2", undoOf, List.of(changes));
	}

	static JournalChange setting(String key, String status) {
		return JournalChange.setting(key, "1", "2", status, null);
	}

	static final class Run {
		private final String id;
		private String at = "2026-09-20T10:00:00Z";
		private @Nullable Double low = 500.0;
		private double avg = 800;
		private @Nullable Double cv = 0.01;
		private String scene = "BENCHMARK_WORLD";
		private String phase = BenchmarkRecord.SINGLE;
		private @Nullable String pairId;
		private int rd = 12;
		private int sd = 8;
		private int width = 2560;
		private int height = 1440;
		private boolean dh;
		private @Nullable Boolean dhGenerating;
		private boolean shaders;
		private @Nullable String pack;
		private @Nullable String hash = "hash-a";
		private @Nullable String cursor;

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

		Run scene(String value) {
			scene = value;
			return this;
		}

		Run before(String pair) {
			phase = BenchmarkRecord.BEFORE;
			pairId = pair;
			return this;
		}

		Run after(String pair) {
			phase = BenchmarkRecord.AFTER;
			pairId = pair;
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

		Run size(int w, int h) {
			width = w;
			height = h;
			return this;
		}

		Run dh(boolean value) {
			dh = value;
			return this;
		}

		Run dhGenerating(@Nullable Boolean value) {
			dhGenerating = value;
			return this;
		}

		Run shaders(boolean on, @Nullable String value) {
			shaders = on;
			pack = value;
			return this;
		}

		Run hash(@Nullable String value) {
			hash = value;
			return this;
		}

		Run cursor(@Nullable String value) {
			cursor = value;
			return this;
		}

		BenchmarkRecord build() {
			Map<String, BenchmarkRecord.KnobResult> knobs = new LinkedHashMap<>();
			knobs.put(BenchmarkRecord.RENDER_DISTANCE, new BenchmarkRecord.KnobResult(rd, rd, null, null, null));
			knobs.put(BenchmarkRecord.SIMULATION_DISTANCE, new BenchmarkRecord.KnobResult(sd, sd, null, null, null));
			BenchmarkRecord.Result result = low == null ? null : new BenchmarkRecord.Result(avg, low, 1000 / low, 2, cv);
			BenchmarkRecord.Context context = new BenchmarkRecord.Context(dh, shaders, pack, width, height, false, BenchmarkRecord.Context.PROTOCOL,
					hash, cursor).withDhGenerating(dhGenerating);
			return new BenchmarkRecord(id, at, "0.5.0+mc26.2", "26.2", "MEASURE", scene, phase, pairId, 144, true, knobs, result, Map.of(), Map.of(),
					null, false, context);
		}
	}
}
