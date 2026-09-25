package io.github.chaotix345.rigtune.core.benchmark;

// What to run (docs/v0.2/SPEC.md item 6). TUNE searches settings; MEASURE measures the current settings.
// pairId links a MEASURE "before" run to its "after" run; null otherwise.
public record BenchmarkRequest(Mode mode, Scene scene, String pairId) {
	public enum Mode {
		TUNE, MEASURE
	}

	public enum Scene {
		CURRENT, BENCHMARK_WORLD
	}

	public static final BenchmarkRequest DEFAULT = new BenchmarkRequest(Mode.TUNE, Scene.CURRENT, null);
}
