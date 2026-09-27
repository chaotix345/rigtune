package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
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
		// Where each spike frame started, by its second mark.
		final Map<Integer, Long> spikeStarts = new java.util.HashMap<>();
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
					spikeStarts.put(second, now);
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

	// docs/v0.5/SPEC.md 2S (AC2S.14) for 2B's RW-6: a benchmark capture reports the CPU Distant Horizons' world generation
	// used during its sweeps, the sampler windows outside its pauses (a window counts by its midpoint).
	@Test
	void dhWorldGenCpuOverTheRecordedSweepsOnly() {
		Capture c = new Capture().frames(150, Map.of(), false);
		worldGen(c, 10, 2);
		c.rings.event(StutterRings.PAUSE_BEGIN, T0 + 30 * S, 0);
		worldGen(c, 30, 6);
		c.rings.event(StutterRings.PAUSE_END, T0 + 50 * S, 0);
		worldGen(c, 50, 1);
		assertEquals(1.5, c.analyze(true).dhWorldGenCores(), 1e-9, "20 s at 2 cores and 20 s at 1; the paused 20 s at 6 left out");

		Capture open = new Capture().frames(150, Map.of(), false);
		worldGen(open, 10, 2);
		open.rings.event(StutterRings.PAUSE_BEGIN, T0 + 30 * S, 0);
		worldGen(open, 30, 6);
		assertEquals(2.0, open.analyze(true).dhWorldGenCores(), 1e-9, "a pause that never ended lasts to the capture's end");
	}

	@Test
	void withoutSamplesThereIsNoWorldGenFigure() {
		assertNull(new Capture().frames(150, Map.of(), false).analyze(true).dhWorldGenCores());
	}

	// The dh tag and the busiest-group note keep their meaning: world generation is DH work.
	@Test
	void worldGenCpuStillCountsAsDh() {
		Capture c = new Capture().frames(150, Map.of(20, 80 * MS), false);
		long[] busy = new long[StutterRings.SAMPLE_STRIDE];
		for (int i = 0; i < 4; i++) {
			fill(busy, T0 + (20 + i) * S, 250 * MS, 0, 14 * 250 * MS);
			busy[StutterRings.S_DH_WORLD_GEN] = 3 * 250 * MS;
			c.rings.sample(busy);
		}
		assertEquals(Map.of(Attributor.DH, 1), c.analyze(true).report().tags());
		Attributor.Sample first = StutterAnalyzer.samples(new StutterAnalyzer.Input(c.ring.snapshot(), c.rings.snapshot(), T0, c.now, STARTED,
				StutterReport.MONITOR, null, null, 0, null, 16, false, false)).getFirst();
		assertEquals("dh", first.topGroup());
		assertEquals(3.0, first.dhCores(), 1e-9);
	}

	// 20 s of 250 ms sampler windows from `fromSecond`, with `cores` of DH world generation each.
	private static void worldGen(Capture c, int fromSecond, long cores) {
		long[] s = new long[StutterRings.SAMPLE_STRIDE];
		for (int i = 1; i <= 80; i++) {
			fill(s, T0 + fromSecond * S + i * 250 * MS, 250 * MS, 0, 4 * 250 * MS);
			s[StutterRings.S_DH_WORLD_GEN] = cores * 250 * MS;
			c.rings.sample(s);
		}
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

	// docs/v0.5/SPEC.md 2S SD-1 (AC2S.5; audit-verify's sd1FullGcSurvivesTheGcRingWrapping): a full GC early in a long
	// session is still counted, and its live-set sample still read, after 2048 later GC records wrapped the GC ring.
	@Test
	void sd1FullGcSurvivesTheGcRingWrapping() {
		Capture c = new Capture();
		c.frames(1200, Map.of(), false);
		c.gc(T0 + 60 * S, 40, GcKind.PAUSE | GcKind.FULL | GcKind.MAJOR, 3072);
		for (int i = 0; i < StutterRings.GC_CAPACITY; i++) {
			c.gc(T0 + 100 * S + i * 500 * MS, 3, GcKind.PAUSE, 0);
		}
		StutterAnalyzer.Result r = c.analyze(true);
		assertEquals(1, r.facts().gcFullPauses(), "the session had one full GC");
		assertEquals(1, r.report().facts().fullGcs());
		assertEquals(75.0, r.facts().liveSetPercent(), 0.01, "its live-set sample: 3 GB of 4");
	}

	// SD-1 (AC2S.5; audit-verify's sd1DhTagShareCoversTheWholeSession): 60 minutes at 62.5 FPS, 358 spikes, every one during
	// saturated DH work. The sampler ring holds only the newest ~17 minutes, so the dh share is taken over the spikes it
	// covers (not diluted by the older ones), and dh stays measured.
	@Test
	void sd1DhShareCountsTheCoveredSpikes() {
		Capture c = new Capture();
		Map<Integer, Long> spikes = new java.util.HashMap<>();
		for (int s = 20; s < 3600; s += 10) {
			spikes.put(s, 80 * MS);
		}
		c.frames(3600, spikes, false);
		long window = 250 * MS;
		long[] rec = new long[StutterRings.SAMPLE_STRIDE];
		for (long t = T0 + window; t <= c.now; t += window) {
			fill(rec, t, window, 4 * window, 15 * window);
			c.rings.sample(rec);
		}
		StutterAnalyzer.Result r = c.analyze(true);
		assertEquals(358, r.report().spikes().total());
		assertFalse(r.facts().unmeasured().contains(Attributor.DH));
		assertTrue(r.facts().taggedShares().get(Attributor.DH) >= 40, "dh share " + r.facts().taggedShares());
		assertEquals(100.0, r.facts().taggedShares().get(Attributor.DH), 0.01, "every covered spike was during DH work");
	}

	// SD-1: the GC claim share is taken over the spikes newer than the oldest GC record the ring still holds.
	@Test
	void sd1GcShareCountsTheSpikesTheGcRingCovers() {
		Capture c = new Capture();
		Map<Integer, Long> spikes = new java.util.HashMap<>();
		for (int s = 20; s < 3600; s += 20) {
			spikes.put(s, 80 * MS);
		}
		c.frames(3600, spikes, false);
		// A 50 ms pause inside every spike of the first half (forgotten once the ring wraps) and of the second half (held).
		for (int s = 20; s < 1800; s += 20) {
			c.gc(spikeStart(c, s) + 20 * MS, 50, GcKind.PAUSE, 0);
		}
		List<Long> late = new java.util.ArrayList<>();
		for (int s = 1800; s < 3600; s += 20) {
			late.add(spikeStart(c, s) + 20 * MS);
		}
		// Short pauses between the spikes fill the ring, all in the second half, in time order.
		java.util.TreeMap<Long, Long> second = new java.util.TreeMap<>();
		late.forEach(t -> second.put(t, 50L));
		for (int i = 0; second.size() < StutterRings.GC_CAPACITY; i++) {
			second.putIfAbsent(T0 + 1805 * S + i * 800 * MS, 0L);
		}
		second.forEach((t, ms) -> c.gc(t, ms, GcKind.PAUSE, 0));
		StutterAnalyzer.Result r = c.analyze(true);
		double lost = 80 - 16;
		assertEquals(100.0 * 51 / lost, r.facts().claimedShares().get(Attributor.GC), 1.0, "over the covered spikes: 51 of each 64 lost ms");
		assertFalse(r.facts().unmeasured().contains(Attributor.GC));
	}

	private static long spikeStart(Capture c, int second) {
		return c.spikeStarts.get(second);
	}

	// Recording a GC notification, the whole-capture counters and the live-set samples included, allocates nothing.
	@Test
	void aGcRecordAllocatesNothing() {
		org.junit.jupiter.api.Assumptions.assumeTrue(java.lang.management.ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
				&& bean.isThreadAllocatedMemorySupported());
		StutterRings rings = new StutterRings(ANCHOR);
		for (int i = 0; i < 20_000; i++) {
			rings.gc(T0 + i, i, i + 1, GcKind.PAUSE | (i % 7 == 0 ? GcKind.FULL | GcKind.MAJOR : 0) | (i % 11 == 0 ? GcKind.EXPLICIT : 0), 1L << 30);
		}
		com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
		long before = threads.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < 20_000; i++) {
			rings.gc(T0 + i, i, i + 1, GcKind.PAUSE | (i % 7 == 0 ? GcKind.FULL | GcKind.MAJOR : 0) | (i % 11 == 0 ? GcKind.EXPLICIT : 0), 1L << 30);
		}
		long allocated = threads.getCurrentThreadAllocatedBytes() - before;
		assertTrue(allocated < 64 * 1024, "20,000 GC records allocated " + allocated + " bytes");
	}

	// docs/v0.5/SPEC.md 2S SD-2 (AC2S.6; audit-verify's sd2OnePercentLowIsNeverAboveTheAverage): once the frame ring wrapped,
	// frames, average and 1 % low all cover its window (the newest frames), and the report says how long that window is.
	@Test
	void sd2OnePercentLowIsNeverAboveTheAverage() {
		FrameRing ring = new FrameRing(FrameRing.SESSION_FRAMES, FrameRing.SESSION_CANDIDATES);
		long now = T0;
		for (int i = 0; i < FrameRing.SESSION_FRAMES; i++) {
			now += 20 * MS;
			ring.frame(now, 20 * MS, false, 0, 0, 0, 0);
		}
		for (int i = 0; i < FrameRing.SESSION_FRAMES; i++) {
			now += 5 * MS;
			ring.frame(now, 5 * MS, false, 0, 0, 0, 0);
		}
		StutterReport report = StutterAnalyzer.analyze(new StutterAnalyzer.Input(ring.snapshot(), new StutterRings(ANCHOR).snapshot(), T0, now, STARTED,
				StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, false, false)).report();
		assertTrue(report.onePercentLowFps() <= report.avgFps(), "1% low " + report.onePercentLowFps() + " > average " + report.avgFps());
		assertEquals(200.0, report.avgFps(), 0.1, "the window's own average");
		assertEquals(FrameRing.SESSION_FRAMES - 1, report.frames(), "the window's frames (the first held one has no duration)");
		assertEquals(3276.8, report.gameplaySeconds(), 0.1, "gameplay time is still the whole capture's");
		assertEquals(655.4, report.windowSeconds(), 0.5, "the window: its frames at its average");

		// A window of excluded frames only (a long stay in a menu): the whole capture's numbers, no window.
		FrameRing menu = new FrameRing(FrameRing.SESSION_FRAMES, FrameRing.SESSION_CANDIDATES);
		long at = T0;
		for (int i = 0; i < 2 * FrameRing.SESSION_FRAMES; i++) {
			at += 5 * MS;
			menu.frame(at, 5 * MS, i >= 1000, 0, 0, 0, 0);
		}
		StutterReport inMenu = StutterAnalyzer.analyze(new StutterAnalyzer.Input(menu.snapshot(), new StutterRings(ANCHOR).snapshot(), T0, at, STARTED,
				StutterReport.MONITOR, "26.2", "g1", 4096, 32768L, 16, false, false)).report();
		assertEquals(1000, inMenu.frames());
		assertEquals(200.0, inMenu.avgFps(), 0.1);
		assertNull(inMenu.windowSeconds());

		Capture shortCapture = new Capture().frames(180, Map.of(20, 80 * MS), false);
		StutterReport whole = shortCapture.analyze(true).report();
		assertNull(whole.windowSeconds(), "a capture shorter than the ring has no window to name");
		assertEquals(Arrays.stream(whole.histogramCounts()).sum(), whole.frames());
	}

	// docs/v0.5/SPEC.md 2S RW-11 (AC2S.13): a settings change or resource reload at t tags the spikes ending in (t, t + 10 s]
	// settingsChanged, a tag that claims nothing and that the rules never see.
	@Test
	void rw11AReloadTagsTheNextTenSecondsAndClaimsNothing() {
		Map<Integer, Long> spikes = Map.of(20, 80 * MS, 32, 80 * MS, 38, 90 * MS, 45, 80 * MS);
		Capture plain = new Capture().frames(150, spikes, false);
		plain.gc(T0 + 32 * S + 20 * MS, 40, GcKind.PAUSE, 0);
		Capture changed = new Capture().frames(150, spikes, false);
		changed.gc(T0 + 32 * S + 20 * MS, 40, GcKind.PAUSE, 0);
		changed.rings.event(StutterRings.SETTINGS_CHANGED, T0 + 30 * S, 16);
		StutterAnalyzer.Result before = plain.analyze(true);
		StutterAnalyzer.Result after = changed.analyze(true);

		assertEquals(Map.of(Attributor.SETTINGS_CHANGED, 2), after.report().tags(), "the spikes at 32 s and 38 s, not those at 20 s and 45 s");
		assertEquals(before.report().causes(), after.report().causes(), "claims nothing");
		assertEquals(before.report().lostMs(), after.report().lostMs());
		assertEquals(before.facts().claimedShares(), after.facts().claimedShares());
		assertEquals(Map.of(), after.facts().taggedShares(), "not a rules tag");
		assertTrue(after.report().worst().stream().filter(w -> w.ms() == 90.0).findFirst().orElseThrow().causes().contains("settingsChanged:context"));
		assertFalse(Attributor.TAGS.contains(Attributor.SETTINGS_CHANGED));
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
