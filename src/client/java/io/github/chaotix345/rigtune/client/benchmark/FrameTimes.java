package io.github.chaotix345.rigtune.client.benchmark;

import io.github.chaotix345.rigtune.core.benchmark.FrameRecorder;
import io.github.chaotix345.rigtune.core.benchmark.FrameStats;

public final class FrameTimes {
	private static final FrameRecorder RECORDER = new FrameRecorder(4096);
	private static volatile boolean recording;

	private FrameTimes() {
	}

	public static synchronized void start() {
		RECORDER.reset();
		recording = true;
	}

	public static synchronized FrameStats stop() {
		recording = false;
		return RECORDER.stats();
	}

	public static boolean recording() {
		return recording;
	}

	public static synchronized int frames() {
		return RECORDER.size();
	}

	public static void onFrame(long nanos) {
		if (recording) {
			synchronized (FrameTimes.class) {
				if (recording) {
					RECORDER.add(nanos);
				}
			}
		}
	}
}
