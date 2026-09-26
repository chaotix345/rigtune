package io.github.chaotix345.rigtune.core.stutter;

// A fixed-size ring of fixed-width long records, allocated once. Writers on other threads (the server thread's saves,
// the GC Notification Thread, the sampler) go through its lock; writes don't allocate. When full, the oldest record is
// overwritten; `added()` keeps counting.
public final class RecordRing {
	private final long[] data;
	private final int stride;
	private final int capacity;
	private long added;

	public RecordRing(int capacity, int stride) {
		this.data = new long[capacity * stride];
		this.stride = stride;
		this.capacity = capacity;
	}

	public synchronized void add(long a, long b, long c) {
		int at = slot();
		data[at] = a;
		data[at + 1] = b;
		data[at + 2] = c;
	}

	// record.length must be the stride; the array is copied, so the caller may reuse it.
	public synchronized void add(long[] record) {
		System.arraycopy(record, 0, data, slot(), stride);
	}

	private int slot() {
		return (int) (added++ % capacity) * stride;
	}

	public int stride() {
		return stride;
	}

	public synchronized long added() {
		return added;
	}

	public long retainedBytes() {
		return data.length * 8L;
	}

	// The held records, oldest first, stride longs each.
	public synchronized long[] snapshot() {
		int held = (int) Math.min(added, capacity);
		long[] out = new long[held * stride];
		long first = added - held;
		for (int i = 0; i < held; i++) {
			System.arraycopy(data, (int) ((first + i) % capacity) * stride, out, i * stride, stride);
		}
		return out;
	}
}
