package io.github.chaotix345.p5a;

import io.github.chaotix345.rigtune.client.benchmark.FrameTimes;

import java.util.Arrays;

// Raw frame times: while the driver's own recording is on, or (mirror) while RigTune's benchmark records its sweeps.
public final class P5aFrames {
	private static long[] buf = new long[1 << 16];
	private static int size;
	public static volatile boolean recording;
	public static volatile boolean mirrorBenchmark;

	private P5aFrames() {
	}

	public static void onFrame(long nanos) {
		if (recording || (mirrorBenchmark && FrameTimes.recording())) {
			synchronized (P5aFrames.class) {
				if (size == buf.length) {
					buf = Arrays.copyOf(buf, buf.length * 2);
				}
				buf[size++] = nanos;
			}
		}
	}

	public static synchronized void reset() {
		size = 0;
	}

	public static synchronized long[] copy() {
		return Arrays.copyOf(buf, size);
	}
}
