package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The session summary and facts over a synthetic capture: 3 minutes at 60 fps with known spikes, GC records, a save and
// a teleport.
class StutterAnalyzerTest {
	private static final long MS = 1_000_000L;
	private static final long S = 1_000 * MS;
	private static final long T0 = 50 * S;
	private static final long ANCHOR = 10 * S;
	private static final Instant STARTED = Instant.parse("2026-09-26T10:00:00Z");

	// Frames of 16 ms from T0 for `seconds`, with spikes at the given second marks (duration ms each), phases optional.
	static final class Capture {
		final FrameRing ring = new FrameRing(FrameRing.SESSION_FRAMES, FrameRing.SESSION_CANDIDATES);
		final StutterRings rings = new StutterRings(ANCHOR);
		long now = T0;

		Capture frames(double seconds, Map<Integer, Long> spikesAtSecond, boolean excluded) {
			long until = now + (long) (seconds * S);
			java.util.Set<Integer> done = new java.util.HashSet<>();
			while (now < until) {
				int second = (int) ((now - T0) / S);
				long d = 16 * MS;
				Long spike = spikesAtSecond.get(second);
				if (spike != null && done.add(second)) {
					d = spike;
				}
				now += d;
				ring.frame(now, d, excluded, 300_000, MS, 14 * MS, 0);
			}
			return this;
		}

		StutterAnalyzer.Result analyze(boolean phaseTiming) {
			return StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), rings.snapshot(), T0, now, STARTED, StutterReport.MONITOR, "26.2", "g1",
					4096, 32768L, 16, phaseTiming, false));
		}

		// A GC pause starting at atNanos: startMs/endMs on the GcInfo base (20 ms behind the uptime clock), received the
		// moment it ended, so the calibrated offset is exactly 20 ms.
		void gc(long atNanos, long pauseMs, int flags, long usedAfterMb) {
			long startMs = (atNanos - ANCHOR) / MS - 20;
			rings.gc(atNanos + pauseMs * MS, startMs, startMs + pauseMs, flags, usedAfterMb << 20);
		}
	}

	@Test
	void aSessionSummary() {
		Capture c = new Capture();
		c.frames(10, Map.of(), true);
		c.frames(170, Map.of(20, 80 * MS, 40, 60 * MS, 60, 45 * MS, 100, 600 * MS, 150, 150 * MS), false);
		// A 50 ms explicit full GC inside the 80 ms spike that starts right after second 20.
		c.gc(T0 + 20 * S + 20 * MS, 50, GcKind.PAUSE | GcKind.FULL | GcKind.EXPLICIT | GcKind.MAJOR, 1024);
		c.gc(T0 + 70 * S, 3, GcKind.PAUSE | GcKind.FULL | GcKind.MAJOR, 3072);
		c.gc(T0 + 80 * S, 0, GcKind.CYCLE | GcKind.STALL_HINT, 0);
		c.rings.event(StutterRings.SAVE_BEGIN, T0 + 39 * S, 0);
		c.rings.event(StutterRings.SAVE_END, T0 + 41 * S, 0);
		c.rings.event(StutterRings.TELEPORT, T0 + 95 * S, 0);
		StutterAnalyzer.Result r = c.analyze(true);
		StutterReport report = r.report();

		assertEquals(5, report.spikes().total());
		assertEquals(new StutterReport.Spikes(1, 2, 1, 1), report.spikes());
		assertTrue(report.enoughData());
		assertEquals(180.0, report.sessionSeconds(), 0.2);
		assertEquals(170.0, report.gameplaySeconds(), 0.2, "the 10 s of world loading aren't gameplay");
		assertEquals(62.5, report.avgFps(), 1.5);
		assertEquals("G1", report.collector());
		assertEquals("2026-09-26T10:00:00Z", report.startedAt());
		assertEquals(4096, report.heapMaxMb());

		assertEquals(5, report.worst().size());
		assertEquals(600.0, report.worst().getFirst().ms());
		assertEquals(100.6, report.worst().getFirst().t(), 0.1);
		assertEquals(16.0, report.worst().getFirst().baseMs(), 0.1);
		StutterReport.Worst gcSpike = report.worst().stream().filter(w -> w.ms() == 80.0).findFirst().orElseThrow();
		assertEquals(List.of("gc:high:FULL:EXPLICIT"), gcSpike.causes());
		assertTrue(report.worst().stream().filter(w -> w.ms() == 60.0).findFirst().orElseThrow().causes().contains("worldSave:low"));
		assertTrue(report.worst().getFirst().causes().contains("afterTeleport:context"));

		double lost = (80 + 60 + 45 + 600 + 150 - 5 * 16);
		assertEquals(lost, report.lostMs(), 0.5);
		assertEquals(Math.round(51.0 / lost * 100) / 100.0, report.causes().get(Attributor.GC), 0.011, "51 ms: the 50 ms pause + the guard");
		assertEquals(1.0, report.causes().values().stream().mapToDouble(Double::doubleValue).sum(), 0.02);
		assertEquals(Map.of(Attributor.WORLD_SAVE, 1, Attributor.AFTER_TELEPORT, 1), report.tags());
		assertEquals(new StutterReport.Facts(75, 1, 1, 1, 20.0), report.facts(), "live set: the (upper) median of 1 and 3 GB, of 4 GB");

		StutterFacts facts = r.facts();
		assertEquals(1, facts.gcExplicitPauses());
		assertEquals(1, facts.gcFullPauses());
		assertEquals(1, facts.gcStalls());
		assertEquals(75.0, facts.liveSetPercent(), 0.01);
		assertEquals(12288, facts.heapRaiseRoomMb(), "min(32 GB / 2, 32 GB - 4 GB) - 4 GB");
		assertEquals(5 / (170.0 / 60), facts.spikesPerMinute(), 0.05);
		assertEquals("g1", facts.gcCollector());
		assertEquals(20.0, facts.taggedShares().get(Attributor.WORLD_SAVE), 0.01);
		assertEquals(100.0 * 51 / lost, facts.claimedShares().get(Attributor.GC), 0.1);
		assertNull(facts.cpuContentionShare(), "no samples");
	}

	@Test
	void notEnoughDataUnderTwoMinutesOrThreeSpikes() {
		Capture shortSession = new Capture().frames(100, Map.of(10, 80 * MS, 20, 80 * MS, 30, 80 * MS, 40, 80 * MS), false);
		assertFalse(shortSession.analyze(true).report().enoughData(), "100 s of gameplay");
		Capture calm = new Capture().frames(200, Map.of(10, 80 * MS, 20, 80 * MS), false);
		StutterReport report = calm.analyze(true).report();
		assertFalse(report.enoughData(), "2 spikes");
		assertEquals(2, report.spikes().total());
	}

	// Until the first notification the pause times can't be mapped: GC claims nothing, the counts still work.
	@Test
	void uncalibratedGcClaimsNothing() {
		Capture c = new Capture().frames(150, Map.of(20, 80 * MS), false);
		StutterAnalyzer.Input in = new StutterAnalyzer.Input(c.ring.snapshot(), new StutterRings.Snapshot(new long[0],
				new long[]{T0 + 21 * S, 100, 150, GcKind.PAUSE | GcKind.FULL | GcKind.EXPLICIT, 0}, new long[0], new GcClock.Calibration(ANCHOR, Double.NaN)),
				T0, c.now, STARTED, StutterReport.MONITOR, "26.2", "g1", 4096, null, 16, true, false);
		StutterAnalyzer.Result r = StutterAnalyzer.analyze(in);
		assertNull(r.report().facts().gcOffsetMs());
		assertEquals(1, r.report().facts().explicitGcs());
		assertEquals(Map.of(Attributor.UNKNOWN, 1.0), r.report().causes());
		assertNull(r.facts().heapRaiseRoomMb(), "RAM unknown");
	}

	@Test
	void gcRecordsOutsideTheCaptureAreIgnored() {
		Capture c = new Capture().frames(150, Map.of(), false);
		c.gc(T0 - 5 * S, 40, GcKind.PAUSE | GcKind.FULL | GcKind.EXPLICIT, 100);
		c.gc(c.now + 5 * S, 40, GcKind.PAUSE | GcKind.FULL | GcKind.EXPLICIT, 100);
		assertEquals(0, c.analyze(true).report().facts().explicitGcs());
	}

	@Test
	void aSaveWithoutItsEndClosesAfterTenSeconds() {
		Capture c = new Capture().frames(160, Map.of(20, 80 * MS, 35, 80 * MS), false);
		c.rings.event(StutterRings.SAVE_BEGIN, T0 + 18 * S, 0);
		StutterReport report = c.analyze(true).report();
		assertEquals(Map.of(Attributor.WORLD_SAVE, 1), report.tags(), "the spike at 20 s is inside, the one at 35 s isn't");
	}

	@Test
	void samplesGiveContentionAndDh() {
		Capture c = new Capture().frames(150, Map.of(20, 80 * MS), false);
		long[] busy = new long[StutterRings.SAMPLE_STRIDE];
		long[] idle = new long[StutterRings.SAMPLE_STRIDE];
		for (int i = 0; i < 4; i++) {
			long t = T0 + (20 + i) * S;
			fill(busy, t, 250 * MS, 3 * 250 * MS, 14 * 250 * MS);
			c.rings.sample(busy);
		}
		for (int i = 0; i < 4; i++) {
			fill(idle, T0 + (60 + i) * S, 250 * MS, 0, 2 * 250 * MS);
			c.rings.sample(idle);
		}
		StutterAnalyzer.Result r = c.analyze(true);
		assertEquals(50.0, r.facts().cpuContentionShare(), 0.01);
		assertEquals(Map.of(Attributor.DH, 1), r.report().tags());
		List<Attributor.Sample> samples = StutterAnalyzer.samples(new StutterAnalyzer.Input(c.ring.snapshot(), c.rings.snapshot(), T0, c.now, STARTED,
				StutterReport.MONITOR, null, null, 0, null, 16, false, false));
		assertEquals("dh", samples.getFirst().topGroup());
		assertEquals(14.0, samples.getFirst().processCores(), 1e-9);
	}

	static void fill(long[] s, long t, long window, long dh, long process) {
		java.util.Arrays.fill(s, 0);
		s[StutterRings.S_TIME] = t;
		s[StutterRings.S_WINDOW] = window;
		s[StutterRings.S_RENDER] = window;
		s[StutterRings.S_DH] = dh;
		s[StutterRings.S_PROCESS] = process;
		s[StutterRings.S_BACKLOG] = -1;
		s[StutterRings.S_BUSY] = -1;
		s[StutterRings.S_TOTAL] = -1;
	}

	@Test
	void theHistogramComesFromTheRing() {
		Capture c = new Capture().frames(130, Map.of(5, 40 * MS), false);
		StutterReport report = c.analyze(true).report();
		long frames = report.histogramCounts()[2];
		assertEquals(1, report.histogramCounts()[4], "the 40 ms frame");
		assertEquals(report.frames(), frames + 1);
		assertArrayEquals(new long[]{0, 0, frames * 16, 0, 40, 0, 0, 0, 0}, report.histogramTimeMs());
	}

	// Review finding 2: what the capture couldn't measure stays UNKNOWN for the rules (also under `not`).
	@Test
	void unmeasuredCausesAndTags() {
		Capture c = new Capture().frames(150, Map.of(20, 80 * MS, 40, 80 * MS), false);
		StutterFacts noPhases = StutterAnalyzer.analyze(new StutterAnalyzer.Input(c.ring.snapshot(), c.rings.snapshot(), T0, c.now, STARTED,
				StutterReport.MONITOR, "26.2", "g1", 4096, null, 16, false, false, true)).facts();
		assertEquals(java.util.Set.of(Attributor.GC, Attributor.CHUNK_LOAD, Attributor.CHUNK_BUILD, Attributor.TICK, Attributor.RENDER, Attributor.DH,
				Attributor.CPU_CONTENTION), noPhases.unmeasured(), "no GC yet, no phase timers, no samples");
		assertTrue(noPhases.gcMeasured());
		c.gc(T0 + 60 * S, 2, GcKind.PAUSE, 0);
		StutterFacts measured = c.analyze(true).facts();
		assertEquals(java.util.Set.of(Attributor.RENDER, Attributor.DH, Attributor.CPU_CONTENTION), measured.unmeasured());
		StutterFacts noListener = StutterAnalyzer.analyze(new StutterAnalyzer.Input(c.ring.snapshot(), c.rings.snapshot(), T0, c.now, STARTED,
				StutterReport.MONITOR, "26.2", null, 4096, null, 16, true, false, false)).facts();
		assertFalse(noListener.gcMeasured());
		assertTrue(noListener.unmeasured().contains(Attributor.GC));
	}

	@Test
	void hitchesCountSpikesUnder100msApartOnce() {
		Capture c = new Capture().frames(125, Map.of(20, 40 * MS), false);
		// A burst: three 45 ms frames with one normal frame between them, one hitch.
		for (int i = 0; i < 3; i++) {
			c.now += 45 * MS;
			c.ring.frame(c.now, 45 * MS, false, 300_000, MS, 14 * MS, 0);
			c.now += 16 * MS;
			c.ring.frame(c.now, 16 * MS, false, 300_000, MS, 14 * MS, 0);
		}
		c.frames(5, Map.of(), false);
		StutterReport report = c.analyze(true).report();
		assertEquals(4, report.spikes().total());
		assertEquals(2, report.hitches());
	}

	// review-8 ST-2: later near-spike candidates (26 ms frames: over 1.5 x the 16 ms baseline, under the 32 ms spike line)
	// push the spike's candidate record out of a small candidate ring while its frame is still in the frame ring; its
	// measured phases still attribute it.
	@Test
	void aSpikeKeepsItsPhasesAfterItsCandidateRecordIsEvicted() {
		FrameRing ring = new FrameRing(8192, 4);
		StutterRings rings = new StutterRings(ANCHOR);
		long now = T0;
		for (int i = 0; i < 300; i++) {
			ring.frame(now += 16 * MS, 16 * MS, false, 300_000, MS, 14 * MS, 0);
		}
		ring.frame(now += 80 * MS, 80 * MS, false, 60 * MS, MS, 14 * MS, 12);
		long spikeEnd = now;
		for (int k = 0; k < 20; k++) {
			for (int i = 0; i < 10; i++) {
				ring.frame(now += 16 * MS, 16 * MS, false, 300_000, MS, 14 * MS, 0);
			}
			ring.frame(now += 26 * MS, 26 * MS, false, 300_000, MS, 14 * MS, 0);
		}
		FrameRing.Snapshot snapshot = ring.snapshot();
		assertEquals(4, snapshot.candidateRecords());
		assertTrue(snapshot.candidate(0, FrameRing.C_END) > spikeEnd, "the spike's own record is gone");
		StutterAnalyzer.Result r = StutterAnalyzer.analyze(new StutterAnalyzer.Input(snapshot, rings.snapshot(), T0, now, STARTED, StutterReport.MONITOR,
				"26.2", "g1", 4096, 32768L, 16, true, false));
		assertEquals(1, r.attributions().size());
		Attributor.Attribution a = r.attributions().getFirst();
		assertEquals(spikeEnd, a.spike().end());
		long exact = 60 * MS - 300_000;
		long claimed = a.claims().getOrDefault(Attributor.CHUNK_LOAD, 0L);
		assertTrue(claimed <= exact && claimed >= exact * 15 / 16 - 64, "chunk packets claimed from the frame's own phases: " + claimed);
	}

	// review-8 P5A-F2 (AC5.8 C): after a teleport into new terrain the chunk loads around each hitch tag it "chunks were
	// loading"; the tag never claims milliseconds, and a hitch with no chunk loads nearby isn't tagged.
	@Test
	void hitchesWhileChunksLoadCarryTheTagAndClaimNothing() {
		Capture c = new Capture();
		c.frames(100, Map.of(40, 70 * MS), false);
		c.rings.event(StutterRings.TELEPORT, c.now, 0);
		java.util.Set<Integer> spikeFrames = java.util.Set.of(30, 90, 150, 210, 270, 330, 390, 450);
		for (int i = 0; i < 500; i++) {
			long d = spikeFrames.contains(i) ? 60 * MS : 16 * MS;
			c.now += d;
			// No packets excess (the builder keeps up, research: P5-A C4): the render phase holds the excess, no backlog.
			c.ring.frame(c.now, d, false, 300_000, MS, d - 2 * MS, i % 7 == 0 ? 2 : 0);
		}
		c.frames(40, Map.of(), false);
		StutterAnalyzer.Result r = c.analyze(true);
		StutterReport report = r.report();
		assertEquals(9, report.spikes().total());
		assertEquals(8, report.tags().get(Attributor.CHUNKS_LOADING), "every hitch after the teleport, not the one at 40 s");
		assertEquals(8, report.tags().get(Attributor.AFTER_TELEPORT));
		assertEquals(Map.of(Attributor.UNKNOWN, 1.0), report.causes(), "a tag never claims milliseconds");
		assertEquals(100.0 * 8 / 9, r.facts().taggedShares().get(Attributor.CHUNKS_LOADING), 0.01);
		Attributor.Attribution tagged = r.attributions().getLast();
		assertTrue(tagged.notes().contains(Attributor.CHUNKS_LOADING + ":context"), tagged.notes().toString());
		assertTrue(tagged.claims().isEmpty());
		assertFalse(r.attributions().getFirst().tags().contains(Attributor.CHUNKS_LOADING), "no chunk loads near the hitch at 40 s");
	}

	// A hitch older than the frame ring keeps the tag through its candidate record's chunk loads.
	@Test
	void theTagSurvivesInCandidateRecordsOlderThanTheFrameRing() {
		FrameRing ring = new FrameRing(256, 64);
		StutterRings rings = new StutterRings(ANCHOR);
		long now = T0;
		for (int i = 0; i < 100; i++) {
			ring.frame(now += 16 * MS, 16 * MS, false, 300_000, MS, 14 * MS, 0);
		}
		ring.frame(now += 16 * MS, 16 * MS, false, 300_000, MS, 14 * MS, 3);
		ring.frame(now += 70 * MS, 70 * MS, false, 300_000, MS, 68 * MS, 0);
		for (int i = 0; i < 100; i++) {
			ring.frame(now += 16 * MS, 16 * MS, false, 300_000, MS, 14 * MS, 0);
		}
		ring.frame(now += 70 * MS, 70 * MS, false, 300_000, MS, 68 * MS, 0);
		for (int i = 0; i < 400; i++) {
			ring.frame(now += 16 * MS, 16 * MS, false, 300_000, MS, 14 * MS, 0);
		}
		StutterAnalyzer.Result r = StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), rings.snapshot(), T0, now, STARTED, StutterReport.MONITOR,
				"26.2", "g1", 4096, 32768L, 16, true, false));
		assertEquals(2, r.report().spikes().total());
		assertEquals(Map.of(Attributor.CHUNKS_LOADING, 1), r.report().tags(), "the first hitch's previous frame loaded chunks; nothing loaded near the second");
	}

	@Test
	void collectorDisplayNames() {
		assertEquals("ZGC", StutterAnalyzer.displayName("zgc"));
		assertEquals("Shenandoah", StutterAnalyzer.displayName("shenandoah"));
		assertNull(StutterAnalyzer.displayName(null));
	}
}
