package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// review-8 ST-2 and ST-3: every frame in the frame ring keeps its own compact phase excess and chunk loads (so a spike's
// evidence lives exactly as long as its frame), and the phase baselines re-warm after a long excluded span.
class FrameRingPhasesTest {
	private static final long MS = 1_000_000L;

	@Test
	void theCompactEncodingNeverOverstatesAndKeepsSixPercent() {
		assertEquals(0, FrameRing.decodePhase(FrameRing.encodePhase(0)));
		assertEquals(0, FrameRing.decodePhase(FrameRing.encodePhase(-5)));
		for (long v = 1; v < 40_000 * MS; v = v * 3 / 2 + 7) {
			long back = FrameRing.decodePhase(FrameRing.encodePhase(v));
			assertTrue(back <= v, v + " came back as " + back);
			assertTrue(v < 2048 || back >= v * 15 / 16 - 64, v + " came back as " + back);
			assertTrue(FrameRing.encodePhase(v) < 512, "9 bits");
		}
		assertEquals(FrameRing.decodePhase(511), FrameRing.decodePhase(FrameRing.encodePhase(Long.MAX_VALUE)), "saturates");
	}

	@Test
	void eachFrameKeepsItsPhaseExcessAndChunkLoads() {
		FrameRing ring = new FrameRing(64, 4);
		long now = 0;
		for (int i = 0; i < 40; i++) {
			ring.frame(now += 16 * MS, 16 * MS, false, MS, 2 * MS, 12 * MS, 0);
		}
		ring.frame(now += 70 * MS, 70 * MS, false, 41 * MS, 2 * MS, 12 * MS, 9);
		ring.frame(now += 16 * MS, 16 * MS, true, 0, 0, 0, 40);
		FrameRing.Snapshot s = ring.snapshot();
		assertEquals(s.ends().length, s.framePhases().length);
		int spike = s.framePhases().length - 2;
		assertEquals(40 * MS, FrameRing.packetsExcess(s.framePhases()[spike]), 40 * MS / 16, "41 ms over a 1 ms baseline");
		assertEquals(0, FrameRing.ticksExcess(s.framePhases()[spike]), 70_000, "ticks at their baseline");
		assertEquals(9, FrameRing.chunkLoads(s.framePhases()[spike]));
		assertEquals(31, FrameRing.chunkLoads(s.framePhases()[spike + 1]), "saturates at 31; excluded frames keep their chunk loads");
		assertEquals(0, FrameRing.packetsExcess(s.framePhases()[spike + 1]), "an excluded frame claims nothing");
		assertEquals(0, FrameRing.packetsExcess(s.framePhases()[0]), "the first frame has no baseline yet");
	}

	@Test
	void theFramePhasesWrapWithTheFrames() {
		FrameRing ring = new FrameRing(8, 2);
		long now = 0;
		for (int i = 1; i <= 11; i++) {
			ring.frame(now += 10 * MS, 10 * MS, false, 0, 0, 0, i);
		}
		FrameRing.Snapshot s = ring.snapshot();
		assertEquals(8, s.framePhases().length);
		for (int i = 0; i < 8; i++) {
			assertEquals(i + 4, FrameRing.chunkLoads(s.framePhases()[i]), "oldest first, like the ends");
		}
	}

	// ST-3: after 10 s of world loading the new dimension streams chunks all the time; its packets baseline starts from what
	// it measures now, not from before the load.
	@Test
	void phaseBaselinesReWarmAfterALongExcludedSpan() {
		FrameRing ring = new FrameRing(4096, 64);
		long now = 0;
		for (int i = 0; i < 300; i++) {
			ring.frame(now += 10 * MS, 10 * MS, false, 200_000, MS, 8 * MS, 0);
		}
		for (int i = 0; i < 1000; i++) {
			ring.frame(now += 10 * MS, 10 * MS, true, 6 * MS, 5 * MS, 9 * MS, 3);
		}
		for (int i = 0; i < 40; i++) {
			ring.frame(now += 10 * MS, 10 * MS, false, 3 * MS, MS, 8 * MS, 1);
		}
		long[] b = ring.snapshot().phaseBaselines();
		assertEquals(3 * MS, b[0], 300_000, "the packets baseline re-warmed on the new steady state");
		assertEquals(MS, b[1], 100_000);
	}

	@Test
	void aShortExclusionKeepsTheBaselines() {
		FrameRing ring = new FrameRing(4096, 64);
		long now = 0;
		for (int i = 0; i < 300; i++) {
			ring.frame(now += 10 * MS, 10 * MS, false, 200_000, MS, 8 * MS, 0);
		}
		for (int i = 0; i < 50; i++) {
			ring.frame(now += 10 * MS, 10 * MS, true, 6 * MS, 5 * MS, 9 * MS, 0);
		}
		ring.frame(now += 10 * MS, 10 * MS, false, 3 * MS, MS, 8 * MS, 0);
		long[] b = ring.snapshot().phaseBaselines();
		assertTrue(b[0] < 500_000, "half a second in a menu doesn't reset the packets baseline: " + b[0]);
	}

	// A pause (the capture skips frames, then one excluded frame spans the gap) counts as a long excluded span too.
	@Test
	void phaseBaselinesReWarmAfterAPause() {
		FrameRing ring = new FrameRing(4096, 64);
		long now = 0;
		for (int i = 0; i < 300; i++) {
			ring.frame(now += 10 * MS, 10 * MS, false, 200_000, MS, 8 * MS, 0);
		}
		now += 30_000 * MS;
		ring.frame(now, 30_000 * MS, true, 0, 0, 0, 0);
		for (int i = 0; i < 40; i++) {
			ring.frame(now += 10 * MS, 10 * MS, false, 3 * MS, MS, 8 * MS, 0);
		}
		assertEquals(3 * MS, ring.snapshot().phaseBaselines()[0], 300_000);
	}
}
