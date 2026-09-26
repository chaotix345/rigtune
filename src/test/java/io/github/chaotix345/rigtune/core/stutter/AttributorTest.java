package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.stutter.Attributor.Attribution;
import io.github.chaotix345.rigtune.core.stutter.Attributor.Context;
import io.github.chaotix345.rigtune.core.stutter.Attributor.GcEvent;
import io.github.chaotix345.rigtune.core.stutter.Attributor.Interval;
import io.github.chaotix345.rigtune.core.stutter.Attributor.Phases;
import io.github.chaotix345.rigtune.core.stutter.Attributor.Sample;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.2: research §4.4's worked example exactly, plus the attribution rules.
class AttributorTest {
	private static final long MS = 1_000_000L;

	// research/v0.4 proto/Proto.java: 50 s at 60 fps (Random(7) jitter), a 45 ms G1 pause inside frame 600, +38 ms of
	// packets with 24 chunk loads at 1200, a 70 ms frame inside a save window at 1800, a 90 ms frame with nothing at 2400
	// followed by a 35 ms frame holding a 14 ms pause at 2401.
	record Trace(long[] ends, Map<Long, Phases> phases, Context context) {
	}

	static Trace workedExample() {
		Random r = new Random(7);
		int n = 60 * 50;
		long[] ends = new long[n + 1];
		Map<Long, Phases> phases = new HashMap<>();
		List<GcEvent> gc = new ArrayList<>();
		List<Interval> saves = new ArrayList<>();
		long t = 0;
		ends[0] = 0;
		for (int i = 0; i < n; i++) {
			long d = 16_667_000L + (long) (r.nextGaussian() * 700_000);
			long p = 300_000;
			long tk = 1_200_000;
			long rd = d - p - tk;
			int chunks = 0;
			if (i == 600) {
				d = 16_700_000L + 45 * MS;
				gc.add(new GcEvent(t + 5 * MS, t + 50 * MS + MS, GcKind.PAUSE));
				rd += 45 * MS;
			}
			if (i == 1200) {
				d = 16_700_000L + 38 * MS;
				p += 38 * MS;
				chunks = 24;
			}
			if (i == 1800) {
				d = 70 * MS;
				rd += 53 * MS;
				saves.add(new Interval(t - 400 * MS, t + 900 * MS));
			}
			if (i == 2400) {
				d = 90 * MS;
				rd += 73 * MS;
			}
			if (i == 2401) {
				d = 35 * MS;
				gc.add(new GcEvent(t + 2 * MS, t + 16 * MS + MS, GcKind.PAUSE));
				rd += 18 * MS;
			}
			t += d;
			ends[i + 1] = t & ~1L;
			phases.put(ends[i + 1], new Phases(p, tk, rd, 300_000, 1_200_000, 15_167_000, chunks, 0));
		}
		Context ctx = new Context(gc, saves, List.of(), List.of(), List.of(), 16, true, false);
		return new Trace(ends, phases, ctx);
	}

	@Test
	void theWorkedExampleExactly() {
		Trace trace = workedExample();
		List<SpikeDetector.Spike> spikes = SpikeDetector.detect(trace.ends());
		assertEquals(5, spikes.size());
		List<Attribution> a = spikes.stream().map(s -> Attributor.attribute(s, trace.phases().get(s.end()), trace.context())).toList();

		double[] durations = {61.7, 54.7, 70.0, 90.0, 35.0};
		double[] baselines = {16.6, 16.6, 16.7, 16.8, 16.8};
		double[] excess = {45.1, 38.1, 53.3, 73.2, 18.2};
		for (int i = 0; i < 5; i++) {
			assertEquals(durations[i], ms(spikes.get(i).duration()), 0.05, "duration " + i);
			assertEquals(baselines[i], ms(spikes.get(i).baseline()), 0.05, "baseline " + i);
			assertEquals(excess[i], ms(spikes.get(i).lost()), 0.05, "excess " + i);
		}
		assertEquals(45.1, ms(a.get(0).claims().get(Attributor.GC)), 0.05);
		assertEquals("gc:high", a.get(0).notes().getFirst());
		assertEquals(Set.of(Attributor.GC), a.get(0).claims().keySet());

		assertEquals(38.0, ms(a.get(1).claims().get(Attributor.CHUNK_LOAD)), 0.05);
		assertEquals("chunkLoad:high", a.get(1).notes().getFirst());

		assertEquals(Map.of(), a.get(2).claims(), "the save tag claims nothing");
		assertEquals(Set.of(Attributor.WORLD_SAVE), a.get(2).tags());
		assertTrue(a.get(2).notes().contains("worldSave:low"));
		assertEquals(53.3, ms(a.get(2).unexplained()), 0.05);

		assertEquals(Map.of(), a.get(3).claims());
		assertEquals(73.2, ms(a.get(3).unexplained()), 0.05);

		assertEquals(15.0, ms(a.get(4).claims().get(Attributor.GC)), 0.05, "14 ms pause + the 1 ms truncation guard");
		assertEquals("gc:high", a.get(4).notes().getFirst());

		long lost = spikes.stream().mapToLong(SpikeDetector.Spike::lost).sum();
		assertEquals(228.0, ms(lost), 0.05);
		Map<String, Long> totals = new LinkedHashMap<>();
		for (Attribution x : a) {
			x.claims().forEach((k, v) -> totals.merge(k, v, Long::sum));
			totals.merge(Attributor.UNKNOWN, x.unexplained(), Long::sum);
		}
		assertEquals(26, Math.round(100.0 * totals.get(Attributor.GC) / lost));
		assertEquals(17, Math.round(100.0 * totals.get(Attributor.CHUNK_LOAD) / lost));
		assertEquals(57, Math.round(100.0 * totals.get(Attributor.UNKNOWN) / lost));

		List<SpikeDetector.Hitch> hitches = SpikeDetector.hitches(spikes);
		assertEquals(4, hitches.size(), "2400 and 2401 are 35 ms apart");
		SpikeDetector.Hitch last = hitches.getLast();
		assertEquals(91.4, ms(last.lost()), 0.1, "73.2 + 18.2 as the research printed them");
		assertEquals(16, Math.round(100.0 * a.get(4).claims().get(Attributor.GC) / last.lost()));
	}

	static SpikeDetector.Spike spike(long end, long duration, long baseline) {
		return new SpikeDetector.Spike(end, duration, baseline);
	}

	static Context gcOnly(GcEvent... events) {
		return new Context(List.of(events), List.of(), List.of(), List.of(), List.of(), 8, true, false);
	}

	@Test
	void aPauseStraddlingTwoFramesIsSplit() {
		long t = 1_000 * MS;
		SpikeDetector.Spike first = spike(t + 40 * MS, 40 * MS, 10 * MS);
		SpikeDetector.Spike second = spike(t + 80 * MS, 40 * MS, 10 * MS);
		Context ctx = gcOnly(new GcEvent(t + 30 * MS, t + 55 * MS, GcKind.PAUSE));
		assertEquals(10 * MS, Attributor.attribute(first, null, ctx).claims().get(Attributor.GC));
		assertEquals(15 * MS, Attributor.attribute(second, null, ctx).claims().get(Attributor.GC));
	}

	// A pause GcInfo reports as ending at ms 201 may have ended at 201.9: the mapped end carries +1 ms (GcClock).
	@Test
	void theTruncationGuardCounts() {
		GcClock.Calibration clock = new GcClock.Calibration(0, 0.0);
		long end = clock.pauseEnd(201);
		long start = clock.pauseStart(190);
		SpikeDetector.Spike s = spike(202 * MS, 30 * MS, 5 * MS);
		Attribution a = Attributor.attribute(s, null, gcOnly(new GcEvent(start, end, GcKind.PAUSE)));
		assertEquals(12 * MS, a.claims().get(Attributor.GC), "190..202 with the guard, not 190..201");
	}

	@Test
	void concurrentCyclesNeverClaim() {
		SpikeDetector.Spike s = spike(100 * MS, 40 * MS, 10 * MS);
		Attribution a = Attributor.attribute(s, null, gcOnly(new GcEvent(50 * MS, 100 * MS, GcKind.CYCLE | GcKind.MAJOR)));
		assertEquals(Map.of(), a.claims());
		assertEquals(30 * MS, a.unexplained());
	}

	@Test
	void anAllocationStallIsNotedButClaimsNothing() {
		SpikeDetector.Spike s = spike(100 * MS, 40 * MS, 10 * MS);
		Attribution a = Attributor.attribute(s, null, gcOnly(new GcEvent(150 * MS, 170 * MS, GcKind.CYCLE | GcKind.STALL_HINT)));
		assertEquals(Map.of(), a.claims());
		assertEquals(List.of("gc:medium:STALL"), a.notes());
	}

	@Test
	void fullAndExplicitFlagsAreNoted() {
		SpikeDetector.Spike s = spike(100 * MS, 60 * MS, 10 * MS);
		Attribution a = Attributor.attribute(s, null, gcOnly(new GcEvent(45 * MS, 95 * MS, GcKind.PAUSE | GcKind.FULL | GcKind.EXPLICIT | GcKind.MAJOR)));
		assertEquals("gc:high:FULL:EXPLICIT", a.notes().getFirst());
		assertEquals(50 * MS, a.claims().get(Attributor.GC));
	}

	@Test
	void tagsNeverClaim() {
		SpikeDetector.Spike s = spike(10_000 * MS, 80 * MS, 10 * MS);
		Context ctx = new Context(List.of(), List.of(new Interval(9_000 * MS, 10_020 * MS)), List.of(new Interval(5_000 * MS, 15_000 * MS)),
				List.of(new Interval(9_990 * MS, 10_500 * MS)), List.of(new Sample(9_750 * MS, 10_000 * MS, 3.0, 7.9, "dh", false)), 8, true, false);
		Attribution a = Attributor.attribute(s, null, ctx);
		assertEquals(Map.of(), a.claims());
		assertEquals(70 * MS, a.unexplained());
		assertEquals(Set.of(Attributor.WORLD_SAVE, Attributor.DH, Attributor.AFTER_TELEPORT, Attributor.MOVING_FAST), a.tags());
		assertTrue(a.notes().contains("dh:medium"));

		Context busy = new Context(List.of(), List.of(), List.of(), List.of(), List.of(new Sample(9_750 * MS, 10_000 * MS, 0.2, 7.5, "builder", false)), 8, true,
				false);
		Attribution b = Attributor.attribute(s, null, busy);
		assertEquals(Set.of(Attributor.CPU_CONTENTION), b.tags());
		assertEquals(List.of("cpuContention:low:builder"), b.notes());
	}

	@Test
	void claimsNeverExceedTheLostTime() {
		SpikeDetector.Spike s = spike(1_000 * MS, 70 * MS, 10 * MS);
		Phases p = new Phases(52 * MS, 51 * MS, 60 * MS, 2 * MS, MS, 5 * MS, 30, 0);
		Context ctx = new Context(List.of(new GcEvent(930 * MS, 1_000 * MS, GcKind.PAUSE)), List.of(), List.of(), List.of(), List.of(), 8, true, true);
		Attribution a = Attributor.attribute(s, p, ctx);
		assertEquals(60 * MS, a.claims().get(Attributor.GC), "GC first, capped at the lost time");
		assertEquals(Set.of(Attributor.GC), a.claims().keySet());
		assertEquals(0, a.unexplained());

		Attribution b = Attributor.attribute(s, p, gcOnly());
		long total = b.claims().values().stream().mapToLong(Long::longValue).sum();
		assertEquals(60 * MS, total + b.unexplained());
		assertEquals(50 * MS, b.claims().get(Attributor.CHUNK_LOAD));
		assertEquals(10 * MS, b.claims().get(Attributor.TICK), "ticks take what's left");
		assertTrue(total <= s.lost());
	}

	@Test
	void theRenderPhaseCountsAsChunkBuildingOnlyWithEvidence() {
		SpikeDetector.Spike s = spike(1_000 * MS, 50 * MS, 10 * MS);
		Phases p = new Phases(MS, MS, 44 * MS, MS, MS, 8 * MS, 0, 0);
		Attribution none = Attributor.attribute(s, p, gcOnly());
		assertEquals(Map.of(), none.claims());
		assertEquals(List.of("render:low"), none.notes());

		Context backlog = new Context(List.of(), List.of(), List.of(), List.of(), List.of(new Sample(800 * MS, 1_050 * MS, 0, 2, "builder", true)), 8, true,
				false);
		assertEquals(36 * MS, Attributor.attribute(s, p, backlog).claims().get(Attributor.CHUNK_BUILD));
		Context waits = new Context(List.of(), List.of(), List.of(), List.of(), List.of(), 8, true, true);
		Attribution defer = Attributor.attribute(s, p, waits);
		assertEquals(36 * MS, defer.claims().get(Attributor.CHUNK_BUILD));
		assertEquals(List.of("chunkBuild:medium"), defer.notes());
	}

	// S-M1: without complete phase timers the phases are ignored; a chunk burst is then only a low-confidence note.
	@Test
	void withoutPhaseTimingChunkLoadingIsCorrelationalOnly() {
		SpikeDetector.Spike s = spike(1_000 * MS, 50 * MS, 10 * MS);
		Phases p = new Phases(40 * MS, MS, 5 * MS, MS, MS, 5 * MS, 6, 3);
		Context off = new Context(List.of(), List.of(), List.of(), List.of(), List.of(), 8, false, false);
		Attribution a = Attributor.attribute(s, p, off);
		assertEquals(Map.of(), a.claims());
		assertEquals(List.of("chunkLoad:low"), a.notes());
		assertEquals(Map.of(Attributor.CHUNK_LOAD, 39 * MS), Attributor.attribute(s, p, gcOnly()).claims());
	}

	// review-8 P5A-F2: chunk loads within 250 ms of the spike tag it "chunks were loading"; the tag claims nothing and adds
	// no note next to a chunk-loading claim or note, which already says so.
	@Test
	void chunksLoadingNearbyIsATagThatClaimsNothing() {
		SpikeDetector.Spike s = spike(10_000 * MS, 60 * MS, 16 * MS);
		Context near = new Context(List.of(), List.of(), List.of(), List.of(), List.of(), 8, true, false, List.of(new Interval(10_180 * MS, 10_200 * MS)));
		Attribution a = Attributor.attribute(s, null, near);
		assertEquals(Set.of(Attributor.CHUNKS_LOADING), a.tags());
		assertEquals(List.of("chunksLoading:context"), a.notes());
		assertEquals(Map.of(), a.claims());
		assertEquals(44 * MS, a.unexplained());

		Context far = new Context(List.of(), List.of(), List.of(), List.of(), List.of(), 8, true, false, List.of(new Interval(10_300 * MS, 10_400 * MS),
				new Interval(9_000 * MS, 9_680 * MS)));
		assertEquals(Set.of(), Attributor.attribute(s, null, far).tags(), "250 ms either side of the frame, no more");

		Phases packets = new Phases(40 * MS, MS, 5 * MS, MS, MS, 5 * MS, 6, 0);
		Attribution claimed = Attributor.attribute(s, packets, near);
		assertEquals(List.of("chunkLoad:high"), claimed.notes());
		assertEquals(Set.of(Attributor.CHUNKS_LOADING), claimed.tags(), "still counted as a tag");
	}

	static double ms(Long nanos) {
		return nanos / 1e6;
	}
}
