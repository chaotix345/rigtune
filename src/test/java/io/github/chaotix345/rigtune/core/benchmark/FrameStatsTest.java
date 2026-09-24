package io.github.chaotix345.rigtune.core.benchmark;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameStatsTest {
	private static final long MS = 1_000_000L;

	@Test
	void emptyInputIsAllZeros() {
		assertEquals(new FrameStats(0, 0, 0, 0, 0), FrameStats.of(new long[0]));
	}

	@Test
	void steadyFrames() {
		long[] frames = new long[200];
		Arrays.fill(frames, 10 * MS);

		FrameStats stats = FrameStats.of(frames);

		assertEquals(200, stats.frames());
		assertEquals(100.0, stats.avgFps(), 1e-9);
		assertEquals(100.0, stats.onePercentLowFps(), 1e-9);
		assertEquals(10.0, stats.p99FrameMs(), 1e-9);
		assertEquals(10.0, stats.maxFrameMs(), 1e-9);
	}

	@Test
	void spikesDriveOnePercentLow() {
		long[] frames = new long[200];
		Arrays.fill(frames, 10 * MS);
		frames[17] = 40 * MS;
		frames[150] = 60 * MS;

		FrameStats stats = FrameStats.of(frames);

		assertEquals(200 / (2.08), stats.avgFps(), 1e-9);
		assertEquals(20.0, stats.onePercentLowFps(), 1e-9);
		assertEquals(10.0, stats.p99FrameMs(), 1e-9);
		assertEquals(60.0, stats.maxFrameMs(), 1e-9);
	}

	@Test
	void fewFramesUseAtLeastOneSlowFrame() {
		FrameStats stats = FrameStats.of(new long[] {10 * MS, 20 * MS, 50 * MS});

		assertEquals(3, stats.frames());
		assertEquals(3 / 0.08, stats.avgFps(), 1e-9);
		assertEquals(20.0, stats.onePercentLowFps(), 1e-9);
		assertEquals(50.0, stats.p99FrameMs(), 1e-9);
		assertEquals(50.0, stats.maxFrameMs(), 1e-9);
	}

	@Test
	void recorderGrowsAndResets() {
		FrameRecorder recorder = new FrameRecorder(2);
		for (int i = 0; i < 100; i++) {
			recorder.add(5 * MS);
		}
		assertEquals(100, recorder.size());
		assertEquals(200.0, recorder.stats().avgFps(), 1e-9);

		recorder.reset();
		assertEquals(0, recorder.stats().frames());
		recorder.add(20 * MS);
		assertEquals(50.0, recorder.stats().onePercentLowFps(), 1e-9);
	}
}
