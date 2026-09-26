package io.github.chaotix345.rigtune.core.benchmark;

import org.jspecify.annotations.Nullable;

import java.util.List;

// Benchmark history and regression alerts (docs/v0.4/SPEC.md 7): pure trend maths (median, MAD, noise floor, Regression)
// go here. The contracts commit only lands View, what the Benchmark history screen shows; WS-B owns and extends both.
public final class BenchmarkTrend {
	private BenchmarkTrend() {
	}

	// contextKey: the context shown; contextKeys: every context present (the selector); comparableRuns / otherRuns: the
	// "N comparable runs; M with different conditions not shown" note.
	public record View(@Nullable String contextKey, List<String> contextKeys, int comparableRuns, int otherRuns) {
		public static final View EMPTY = new View(null, List.of(), 0, 0);

		public View {
			contextKeys = contextKeys == null ? List.of() : List.copyOf(contextKeys);
		}
	}
}
