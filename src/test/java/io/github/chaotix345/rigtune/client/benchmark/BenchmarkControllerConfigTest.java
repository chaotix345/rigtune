package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.Timing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkControllerConfigTest {
	@Test
	void defaultTimingMatchesSpec() {
		assertEquals(Timing.DEFAULT, BenchmarkController.Config.DEFAULT.timing());
	}

	@Test
	void shortConfigScalesWarmupAndQuick() {
		Timing t = new BenchmarkController.Config(2, 1.5, 1.0, 20.0).timing();
		assertEquals(2, t.maxRdSteps());
		assertEquals(0.75, t.warmupSeconds());
		assertEquals(1.5, t.quickSeconds());
		assertEquals(2.0, t.quickSettleTimeoutSeconds());
		assertEquals(2, t.repeats());
		assertEquals(300.0, t.deadlineSeconds());
	}

	@Test
	void quickSettleNeverExceedsTheTimeout() {
		assertEquals(1.0, new BenchmarkController.Config(3, 1.0, 0.5, 1.0).timing().quickSettleTimeoutSeconds());
	}

	@Test
	void theShortConstructorKeepsTheDerivedTargetAndTheFullRange() {
		BenchmarkController.Config config = new BenchmarkController.Config(3, 1.0, 0.5, 60.0);
		assertEquals(null, config.targetFps());
		assertEquals(BenchmarkController.MAX_RD, config.maxRenderDistance());
		assertEquals(60.0, config.timing().settleTimeoutSeconds());
		assertEquals(null, BenchmarkController.Config.DEFAULT.targetFps());
	}

	@Test
	void devTargetFpsIsReadOnlyWhenValid() {
		assertEquals(null, BenchmarkController.parseTargetFps(null));
		assertEquals(null, BenchmarkController.parseTargetFps("fast"));
		assertEquals(null, BenchmarkController.parseTargetFps("0"));
		assertEquals(null, BenchmarkController.parseTargetFps("-5"));
		assertEquals(300.0, BenchmarkController.parseTargetFps(" 300 "));
	}
}
