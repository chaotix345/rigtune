package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

// Benchmark history and regression alerts (docs/v0.4/SPEC.md 7): the trend over comparable runs of benchmarks.json.
// RealController delegates benchmarkTrend() here in one line. Skeleton from the contracts commit; WS-B owns it.
public final class TrendService {
	private final RealController controller;
	private final Path configDir;

	public TrendService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	public BenchmarkTrend.View trend(@Nullable String contextKey) {
		return BenchmarkTrend.View.EMPTY;
	}
}
