package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.4: 1 M frames after warm-up allocate 0 bytes; wrap-around and snapshot order; the histogram; F-M1's accounting.
class FrameRingAllocationTest {
	private static final long MS = 1_000_000L;

	static long allocatedBytes() {
		return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
	}

	static boolean allocationMeasurable() {
		if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean) || !bean.isThreadAllocatedMemorySupported()) {
			System.out.println("FrameRingAllocationTest: thread allocation counting unsupported on this JVM; skipped");
			return false;
		}
		bean.setThreadAllocatedMemoryEnabled(true);
		return true;
	}

	@Test
	void aMillionFramesAllocateNothing() {
		Assumptions.assumeTrue(allocationMeasurable());
		FrameRing ring = new FrameRing(FrameRing.SESSION_FRAMES, FrameRing.SESSION_CANDIDATES);
		long now = 1_000_000_000L;
		for (int i = 0; i < 200_000; i++) {
			now += 7 * MS;
			ring.frame(now, i % 97 == 0 ? 40 * MS : 7 * MS, i % 1000 == 0, 300_000, 1_000_000, 5 * MS, i & 3);
		}
		// Anything allocated per frame would be at least 16 MB over a million frames; the bound only absorbs the probe's own
		// few bytes (CI once saw 24).
		long before = allocatedBytes();
		for (int i = 0; i < 1_000_000; i++) {
			now += 7 * MS;
			ring.frame(now, i % 97 == 0 ? 40 * MS : 7 * MS, i % 1000 == 0, 300_000, 1_000_000, 5 * MS, i & 3);
		}
		long allocated = allocatedBytes() - before;
		assertTrue(allocated < 1024, "bytes allocated by 1 M frames: " + allocated);
	}

	@Test
	void theRingWrapsAndSnapshotsOldestFirst() {
		FrameRing ring = new FrameRing(8, 4);
		for (int i = 1; i <= 11; i++) {
			ring.frame(i * 100L, 10 * MS, i == 10, 0, 0, 0, 0);
		}
		FrameRing.Snapshot s = ring.snapshot();
		assertEquals(11, s.frames());
		assertArrayEquals(new long[]{400, 500, 600, 700, 800, 900, 1001, 1100}, s.ends(), "the newest 8, bit 0 marking the excluded frame");
		assertTrue(FrameRing.Snapshot.excluded(s.ends()[6]));
		assertFalse(FrameRing.Snapshot.excluded(s.ends()[7]));
		assertEquals(10, s.gameplayFrames());
		assertEquals(1, s.excludedFrames());
		assertEquals(100 * MS, s.gameplayNanos());
	}

	@Test
	void candidatesWrapAndKeepTheirPhases() {
		FrameRing ring = new FrameRing(16, 2);
		long now = 0;
		for (int i = 0; i < 40; i++) {
			now += 10 * MS;
			ring.frame(now, 10 * MS, false, MS, 2 * MS, 6 * MS, 0);
		}
		for (int i = 1; i <= 3; i++) {
			now += 50 * MS;
			ring.frame(now, 50 * MS, false, MS + i * MS, 2 * MS, 6 * MS, i);
			now += 10 * MS;
			ring.frame(now, 10 * MS, false, MS, 2 * MS, 6 * MS, 0);
		}
		FrameRing.Snapshot s = ring.snapshot();
		assertEquals(3, s.candidateCount());
		assertEquals(2, s.candidateRecords(), "the newest 2 of 3");
		assertEquals(3 * MS, s.candidate(0, FrameRing.C_PACKETS));
		assertEquals(4 * MS, s.candidate(1, FrameRing.C_PACKETS));
		assertEquals(MS, s.candidate(1, FrameRing.C_PACKETS_BASE), 100_000, "the packets baseline barely moved");
		assertEquals(3L, s.candidate(1, FrameRing.C_CHUNKS) & 0xFFFFFFFFL);
		assertEquals(0L, s.candidate(1, FrameRing.C_CHUNKS) >>> 32, "the previous frame loaded none");
		assertEquals(10 * MS, s.candidate(1, FrameRing.C_BASELINE), MS);
	}

	// A frame is a candidate only at 20 ms or more and 1.5 x the running baseline.
	@Test
	void candidateThreshold() {
		FrameRing ring = new FrameRing(64, 16);
		long now = 0;
		for (int i = 0; i < 50; i++) {
			now += 16 * MS;
			ring.frame(now, 16 * MS, false, 0, 0, 0, 0);
		}
		ring.frame(now += 23 * MS, 23 * MS, false, 0, 0, 0, 0);
		ring.frame(now += 25 * MS, 25 * MS, false, 0, 0, 0, 0);
		ring.frame(now += 90 * MS, 90 * MS, true, 0, 0, 0, 0);
		assertEquals(1, ring.snapshot().candidateCount(), "25 ms qualifies; 23 ms is under 1.5 x 16; excluded frames never do");
	}

	@Test
	void histogramIsTimeWeightedOverGameplayFrames() {
		FrameRing ring = new FrameRing(64, 16);
		long[] durations = {3 * MS, 5 * MS, 10 * MS, 20 * MS, 40 * MS, 70 * MS, 200 * MS, 500 * MS, 1500 * MS};
		long now = 0;
		for (long d : durations) {
			ring.frame(now += d, d, false, 0, 0, 0, 0);
		}
		ring.frame(now += 3 * MS, 3 * MS, true, 0, 0, 0, 0);
		FrameRing.Snapshot s = ring.snapshot();
		assertArrayEquals(new long[]{1, 1, 1, 1, 1, 1, 1, 1, 1}, s.histogramCounts());
		assertArrayEquals(durations, s.histogramNanos());
		assertEquals(0, FrameRing.bucket(4_166_666L));
		assertEquals(1, FrameRing.bucket(4_166_667L));
		assertEquals(8, FrameRing.bucket(1000 * MS));
	}

	// F-M1: the explicit accounting WS-F's budget asserts on.
	@Test
	void retainedBytesCountTheRings() {
		FrameRing session = new FrameRing(FrameRing.SESSION_FRAMES, FrameRing.SESSION_CANDIDATES);
		assertEquals((131_072 + 40_960 + 18) * 8L, session.retainedBytes());
		StutterRings shared = new StutterRings(0);
		assertEquals((4096 * 3 + 2048 * 5 + 4096 * 13) * 8L, shared.retainedBytes());
		assertTrue(session.retainedBytes() + shared.retainedBytes() < 2_500_000L, "within SPEC 10's 2.5 MiB monitor-on allowance");
	}

	@Test
	void recordRingWrapsOldestFirst() {
		RecordRing ring = new RecordRing(3, 3);
		for (int i = 1; i <= 5; i++) {
			ring.add(i, i * 10L, i * 100L);
		}
		assertEquals(5, ring.added());
		assertArrayEquals(new long[]{3, 30, 300, 4, 40, 400, 5, 50, 500}, ring.snapshot());
	}

	@Test
	void gcRecordsCalibrateTheClock() {
		StutterRings rings = new StutterRings(1_000 * MS);
		assertFalse(rings.snapshot().clock().calibrated());
		rings.gc(1_000 * MS + 230 * MS, 199, 201, GcKind.PAUSE | GcKind.FULL, 12L << 20);
		StutterRings.Snapshot s = rings.snapshot();
		assertTrue(s.clock().calibrated());
		assertEquals(29.0, s.clock().offsetMs(), 1e-9);
		assertArrayEquals(new long[]{1_230 * MS, 199, 201, GcKind.PAUSE | GcKind.FULL, 12L << 20}, s.gc());
	}
}
