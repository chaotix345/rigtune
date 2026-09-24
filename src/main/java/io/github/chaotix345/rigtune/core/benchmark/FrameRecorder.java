package io.github.chaotix345.rigtune.core.benchmark;

import java.util.Arrays;

public final class FrameRecorder {
	private long[] buffer;
	private int size;

	public FrameRecorder() {
		this(1024);
	}

	public FrameRecorder(int initialCapacity) {
		buffer = new long[Math.max(1, initialCapacity)];
	}

	public void add(long nanos) {
		if (size == buffer.length) {
			buffer = Arrays.copyOf(buffer, buffer.length * 2);
		}
		buffer[size++] = nanos;
	}

	public void reset() {
		size = 0;
	}

	public int size() {
		return size;
	}

	public FrameStats stats() {
		return FrameStats.of(Arrays.copyOf(buffer, size));
	}
}
