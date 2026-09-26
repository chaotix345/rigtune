package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.1: synthetic traces through the spike rule.
class SpikeDetectorTest {
	private static final long MS = 1_000_000L;

	// Frame ends for the given durations (ns); excluded[i] marks frame i excluded.
	static long[] ends(long[] durations, boolean[] excluded) {
		long[] ends = new long[durations.length + 1];
		long t = 1_000_000_000L;
		ends[0] = t;
		for (int i = 0; i < durations.length; i++) {
			t += durations[i];
			ends[i + 1] = excluded != null && excluded[i] ? t | 1L : t & ~1L;
		}
		return ends;
	}

	static long[] jittered(int frames, double fps, double jitter, long seed, IntFunction<Long> override) {
		Random random = new Random(seed);
		long[] d = new long[frames];
		for (int i = 0; i < frames; i++) {
			Long o = override == null ? null : override.apply(i);
			d[i] = o != null ? o : Math.round(1e9 / fps * (1 + random.nextGaussian() * jitter));
		}
		return d;
	}

	@Test
	void flatFrameRatesWithJitterHaveNoSpikes() {
		for (double fps : new double[]{60, 144, 240}) {
			long[] d = jittered(60 * (int) fps, fps, 0.08, 42, null);
			assertEquals(List.of(), SpikeDetector.detect(ends(d, null)), fps + " fps");
		}
	}

	@Test
	void aDoubledFrameAt240FpsIsUnderTheFloor() {
		long[] d = jittered(2400, 240, 0.02, 1, i -> i == 1500 ? 8_333_333L : null);
		assertEquals(List.of(), SpikeDetector.detect(ends(d, null)), "8.3 ms is 2x 4.2 ms but under 20 ms");
	}

	@Test
	void a45msFrameAt60FpsIsASpike() {
		long[] d = jittered(1200, 60, 0.03, 2, i -> i == 700 ? 45 * MS : null);
		List<SpikeDetector.Spike> spikes = SpikeDetector.detect(ends(d, null));
		assertEquals(1, spikes.size());
		SpikeDetector.Spike s = spikes.getFirst();
		assertEquals(45 * MS, s.duration());
		assertEquals(16.7, s.baseline() / 1e6, 0.3);
		assertEquals(45 * MS - s.baseline(), s.lost());
		assertEquals(SpikeDetector.Severity.MINOR, s.severity());
	}

	@Test
	void aBurstOfFiveIsOneHitch() {
		long[] d = jittered(1200, 60, 0.03, 3, i -> i >= 600 && i <= 608 && i % 2 == 0 ? 40 * MS : null);
		List<SpikeDetector.Spike> spikes = SpikeDetector.detect(ends(d, null));
		assertEquals(5, spikes.size());
		List<SpikeDetector.Hitch> hitches = SpikeDetector.hitches(spikes);
		assertEquals(1, hitches.size());
		assertEquals(spikes.stream().mapToLong(SpikeDetector.Spike::lost).sum(), hitches.getFirst().lost());
	}

	@Test
	void spikesAtLeast100msApartAreSeparateHitches() {
		long[] d = jittered(1200, 60, 0.03, 4, i -> i == 600 || i == 608 ? 40 * MS : null);
		List<SpikeDetector.Hitch> hitches = SpikeDetector.hitches(SpikeDetector.detect(ends(d, null)));
		assertEquals(2, hitches.size(), "7 normal frames (~117 ms) between them");
	}

	@Test
	void aDriftFrom60To30FpsIsNoSpikeStorm() {
		int n = 1800;
		long[] d = jittered(n, 60, 0.03, 5, null);
		for (int i = 0; i < n; i++) {
			double fps = i < 300 ? 60 : i > 1500 ? 30 : 60 - 30.0 * (i - 300) / 1200;
			d[i] = Math.round(d[i] * 60 / fps);
		}
		assertEquals(List.of(), SpikeDetector.detect(ends(d, null)));
	}

	// Beyond AC5.1: a sudden, lasting drop to under half the frame rate re-baselines instead of flagging every frame.
	@Test
	void aLastingStepDownIsANewBaselineNotAStorm() {
		long[] d = jittered(3000, 144, 0.03, 6, i -> i >= 1000 ? Long.valueOf(34 * MS + (i % 3) * MS) : null);
		List<SpikeDetector.Spike> spikes = SpikeDetector.detect(ends(d, null));
		assertTrue(spikes.isEmpty(), "spikes: " + spikes.size());
		long[] withHitch = jittered(3000, 144, 0.03, 6, i -> i == 2000 ? Long.valueOf(150 * MS) : i >= 1000 ? Long.valueOf(34 * MS + (i % 3) * MS) : null);
		List<SpikeDetector.Spike> after = SpikeDetector.detect(ends(withHitch, null));
		assertEquals(1, after.size(), "a real hitch at the new rate still counts");
		assertEquals(35 * MS, after.getFirst().baseline(), 1.5 * MS);
	}

	@Test
	void excludedFramesAreNeverSpikesNorBaseline() {
		int n = 1500;
		boolean[] excluded = new boolean[n];
		long[] d = jittered(n, 60, 0.03, 7, i -> i >= 400 && i < 1000 ? Long.valueOf(i % 7 == 0 ? 300 * MS : 90 * MS) : null);
		for (int i = 400; i < 1000; i++) {
			excluded[i] = true;
		}
		assertEquals(List.of(), SpikeDetector.detect(ends(d, excluded)), "menus, an unfocused window and the 10 s after a level change");
		long[] withSpike = jittered(n, 60, 0.03, 7, i -> i == 1001 ? Long.valueOf(45 * MS) : i >= 400 && i < 1000 ? Long.valueOf(90 * MS) : null);
		List<SpikeDetector.Spike> spikes = SpikeDetector.detect(ends(withSpike, excluded));
		assertEquals(1, spikes.size());
		assertEquals(16.7, spikes.getFirst().baseline() / 1e6, 0.3, "the baseline skipped the excluded frames");
	}

	@Test
	void severityByFrameDuration() {
		assertEquals(SpikeDetector.Severity.MINOR, SpikeDetector.Spike.severity(49 * MS));
		assertEquals(SpikeDetector.Severity.MAJOR, SpikeDetector.Spike.severity(50 * MS));
		assertEquals(SpikeDetector.Severity.MAJOR, SpikeDetector.Spike.severity(99 * MS));
		assertEquals(SpikeDetector.Severity.SEVERE, SpikeDetector.Spike.severity(100 * MS));
		assertEquals(SpikeDetector.Severity.SEVERE, SpikeDetector.Spike.severity(499 * MS));
		assertEquals(SpikeDetector.Severity.FREEZE, SpikeDetector.Spike.severity(500 * MS));
	}

	@Test
	void theRuleItself() {
		assertTrue(SpikeDetector.isSpike(21 * MS, 4 * MS), "20 ms floor");
		assertTrue(!SpikeDetector.isSpike(20 * MS, 4 * MS));
		assertTrue(!SpikeDetector.isSpike(33 * MS, 16_700_000L), "under 2 b");
		assertTrue(SpikeDetector.isSpike(34 * MS, 16_700_000L));
		assertTrue(!SpikeDetector.isSpike(28 * MS, 20 * MS), "b + 8 ms");
		assertTrue(SpikeDetector.isSpike(41 * MS, 20 * MS));
	}

	// Frames the frame ring no longer holds are judged from their candidate records.
	@Test
	void olderFramesComeFromTheCandidates() {
		FrameRing ring = new FrameRing(64, 16);
		long now = 0;
		List<Long> spikeEnds = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			long d = i == 50 ? 60 * MS : i == 60 ? 26 * MS : 16 * MS;
			now += d;
			ring.frame(now, d, false, 0, 0, 0, 0);
			if (i == 50) {
				spikeEnds.add(now);
			}
		}
		FrameRing.Snapshot s = ring.snapshot();
		List<SpikeDetector.Spike> old = SpikeDetector.fromCandidates(s, s.ends()[0] & ~1L);
		assertEquals(1, old.size(), "the 26 ms candidate isn't a spike (under 2 b)");
		assertEquals(spikeEnds.getFirst(), old.getFirst().end());
		assertEquals(16 * MS, old.getFirst().baseline());
		assertEquals(List.of(), SpikeDetector.detect(s.ends()), "the ring's own frames are flat");
	}
}
