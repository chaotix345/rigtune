package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.core.stutter.StutterRings;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// ThreadSampler.Tally (docs/v0.4/SPEC.md 10, the sampler's CPU budget): the same per-group CPU attribution and census as
// the sampler's original loop (a HashMap<Long, Long> and a getThreadInfo for every thread in every sample, kept below as
// the reference), names read once per thread, and nothing allocated per sample in steady state.
class ThreadSamplerTest {
	private static final int GROUP_FIRST = StutterRings.S_RENDER;
	private static final int GROUP_LAST = StutterRings.S_OTHER;

	// A scripted set of threads: ids, names and CPU times the test changes between samples.
	private static final class FakeSource implements ThreadSampler.Source {
		final Map<Long, String> names = new LinkedHashMap<>();
		final Map<Long, Long> cpu = new HashMap<>();
		// Listed in threadIds() but ended by the time the CPU times are read.
		final List<Long> endedAfterListing = new ArrayList<>();
		int nameLookups;

		void thread(long id, String name) {
			names.put(id, name);
			cpu.put(id, 0L);
		}

		void end(long id) {
			names.remove(id);
			cpu.remove(id);
		}

		@Override
		public long[] threadIds() {
			long[] out = new long[names.size() + endedAfterListing.size()];
			int i = 0;
			for (long id : names.keySet()) {
				out[i++] = id;
			}
			for (long id : endedAfterListing) {
				out[i++] = id;
			}
			return out;
		}

		@Override
		public long[] cpuTimes(long[] ids) {
			long[] out = new long[ids.length];
			for (int i = 0; i < ids.length; i++) {
				out[i] = cpu.getOrDefault(ids[i], -1L);
			}
			return out;
		}

		@Override
		public @Nullable String[] names(long[] ids) {
			nameLookups += ids.length;
			@Nullable String[] out = new String[ids.length];
			for (int i = 0; i < ids.length; i++) {
				out[i] = names.get(ids[i]);
			}
			return out;
		}

		@Override
		public long processCpu() {
			return -1;
		}
	}

	// The sampler's loop as it was before the Tally (the per-thread part of one sample), for the equivalence check.
	private static final class Reference {
		Map<Long, Long> last = new HashMap<>();
		Map<Long, Long> next = new HashMap<>();
		boolean first = true;

		boolean add(long[] ids, long[] times, @Nullable String[] names, long[] record, @Nullable Map<String, Integer> census) {
			next.clear();
			for (int i = 0; i < ids.length; i++) {
				if (times[i] < 0 || names[i] == null) {
					continue;
				}
				String name = names[i];
				next.put(ids[i], times[i]);
				Long before = last.get(ids[i]);
				if (!first) {
					record[ThreadSampler.group(name)] += Math.max(0, times[i] - (before == null ? 0 : before));
				}
				if (census != null) {
					census.merge(ThreadSampler.censusName(name), 1, Integer::sum);
				}
			}
			Map<Long, Long> swap = last;
			last = next;
			next = swap;
			boolean counted = !first;
			first = false;
			return counted;
		}
	}

	@Test
	void sameAttributionAndCensusAsTheOriginalLoop() {
		assertEquals(12 + 64, equivalence(new ThreadSampler.Tally()), "each thread's name read once: 10 at the start, 2 later, a burst of 64");
	}

	// A table that starts at 8 slots: it grows mid-run with live entries in it, and ids that share hash slots probe past
	// each other.
	@Test
	void sameAttributionFromATinyTableThatGrowsWithLiveEntries() {
		ThreadSampler.Tally tally = new ThreadSampler.Tally(4);
		assertEquals(12 + 64, equivalence(tally));
		assertTrue(tally.capacity() >= 128, "grew: " + tally.capacity());
	}

	// Runs the Tally and the original loop side by side over 60 samples of a changing thread set; returns the names read.
	private static int equivalence(ThreadSampler.Tally tally) {
		FakeSource source = new FakeSource();
		String[] names = {"Render thread", "Server thread", "Worker-Main-1", "Worker-Main-2", "IO-Worker-7", "Chunk Render Task Executor #3",
				"DH-Render-1", "Netty Local IO #0", "Signal Dispatcher", "RigTune worker"};
		for (int i = 0; i < names.length; i++) {
			source.thread(10 + i, names[i]);
		}
		Reference reference = new Reference();
		Random random = new Random(42);
		for (int sample = 0; sample < 60; sample++) {
			// Threads come and go: a new pool thread, one that ends, one listed but ended before its CPU time is read.
			if (sample == 5) {
				source.thread(40, "Worker-Main-3");
			}
			if (sample == 9) {
				source.end(13);
			}
			if (sample == 12) {
				source.endedAfterListing.add(99L);
			}
			if (sample == 14) {
				source.endedAfterListing.clear();
				source.thread(41, "DH-Worker-2");
			}
			// A burst of 64 pool threads with widely spread ids, then half of them end.
			if (sample == 20) {
				for (int i = 0; i < 64; i++) {
					source.thread(1_000_003L * (i + 1) + (i % 3), (i % 2 == 0 ? "IO-Worker-" : "Chunk Render Task Executor #") + i);
				}
			}
			if (sample == 30) {
				for (int i = 0; i < 64; i += 2) {
					source.end(1_000_003L * (i + 1) + (i % 3));
				}
			}
			for (Map.Entry<Long, Long> e : source.cpu.entrySet()) {
				e.setValue(e.getValue() + random.nextInt(3_000_000));
			}
			long[] ids = source.threadIds();
			long[] times = source.cpuTimes(ids);
			long[] expected = new long[StutterRings.SAMPLE_STRIDE];
			long[] actual = new long[StutterRings.SAMPLE_STRIDE];
			Map<String, Integer> expectedCensus = sample == 0 ? new TreeMap<>() : null;
			Map<String, Integer> actualCensus = tally.first() ? new TreeMap<>() : null;
			assertEquals(reference.add(ids, times, allNames(source, ids), expected, expectedCensus), tally.add(ids, times, source, actual, actualCensus),
					"sample " + sample + " counted");
			assertArrayEquals(Arrays.copyOfRange(expected, GROUP_FIRST, GROUP_LAST + 1), Arrays.copyOfRange(actual, GROUP_FIRST, GROUP_LAST + 1),
					"sample " + sample + ": CPU per group");
			assertEquals(expectedCensus, actualCensus, "sample " + sample + ": census");
		}
		return source.nameLookups;
	}

	private static @Nullable String[] allNames(FakeSource source, long[] ids) {
		@Nullable String[] out = new String[ids.length];
		for (int i = 0; i < ids.length; i++) {
			out[i] = source.names.get(ids[i]);
		}
		return out;
	}

	@Test
	void groupsAndTheFirstSample() {
		FakeSource source = new FakeSource();
		source.thread(1, "Render thread");
		source.thread(2, "DH-Render-1");
		ThreadSampler.Tally tally = new ThreadSampler.Tally();
		long[] record = new long[StutterRings.SAMPLE_STRIDE];
		source.cpu.put(1L, 5_000_000L);
		assertFalse(tally.add(source.threadIds(), source.cpuTimes(source.threadIds()), source, record, null), "the first sample only sets the baseline");
		assertEquals(0, Arrays.stream(record).sum());
		source.cpu.put(1L, 7_000_000L);
		source.cpu.put(2L, 1_000_000L);
		source.thread(3, "Server thread");
		source.cpu.put(3L, 4_000_000L);
		assertTrue(tally.add(source.threadIds(), source.cpuTimes(source.threadIds()), source, record, null));
		assertEquals(2_000_000L, record[StutterRings.S_RENDER]);
		assertEquals(1_000_000L, record[StutterRings.S_DH]);
		assertEquals(4_000_000L, record[StutterRings.S_SERVER], "a new thread counts from 0");
	}

	@Test
	void moreThreadsThanTheTablesHoldStillAddUp() {
		FakeSource source = new FakeSource();
		for (int i = 0; i < 300; i++) {
			source.thread(1000 + 7L * i, "Worker-Main-" + i);
		}
		ThreadSampler.Tally tally = new ThreadSampler.Tally(64);
		long[] record = new long[StutterRings.SAMPLE_STRIDE];
		tally.add(source.threadIds(), source.cpuTimes(source.threadIds()), source, record, null);
		assertTrue(tally.capacity() >= 600, "grew: " + tally.capacity());
		source.cpu.replaceAll((id, t) -> t + 1000);
		tally.add(source.threadIds(), source.cpuTimes(source.threadIds()), source, record, null);
		assertEquals(300 * 1000L, record[StutterRings.S_WORKER]);
		assertEquals(300, source.nameLookups);

		// Growing again with the first 300 in the table: they stay known (no name read, deltas from their last time).
		int before = tally.capacity();
		for (int i = 0; i < 300; i++) {
			source.thread(500_000 + 13L * i, "IO-Worker-" + i);
		}
		source.cpu.replaceAll((id, t) -> t + 1000);
		long[] next = new long[StutterRings.SAMPLE_STRIDE];
		tally.add(source.threadIds(), source.cpuTimes(source.threadIds()), source, next, null);
		assertTrue(tally.capacity() > before, "grew with live entries: " + before + " -> " + tally.capacity());
		assertEquals(300 * 1000L, next[StutterRings.S_WORKER], "the known threads' deltas, not their whole CPU time");
		assertEquals(300 * 1000L, next[StutterRings.S_IO], "the new threads from 0");
		assertEquals(600, source.nameLookups);
	}

	// Steady state: the same threads sample after sample. The two JDK calls return fresh arrays in the game; here the arrays
	// are reused, so everything the Tally itself allocates shows.
	@Test
	void aSteadyStateSampleAllocatesNothing() {
		Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean && bean.isThreadAllocatedMemorySupported());
		FakeSource source = new FakeSource();
		for (int i = 0; i < 90; i++) {
			source.thread(1 + i, i % 2 == 0 ? "Worker-Main-" + i : "Chunk Render Task Executor #" + i);
		}
		long[] ids = source.threadIds();
		long[] times = source.cpuTimes(ids);
		long[] record = new long[StutterRings.SAMPLE_STRIDE];
		ThreadSampler.Tally tally = new ThreadSampler.Tally();
		for (int i = 0; i < 20_000; i++) {
			times[i % times.length] += 1000;
			tally.add(ids, times, source, record, null);
		}
		long before = StutterMonitorTest.allocated();
		for (int i = 0; i < 20_000; i++) {
			times[i % times.length] += 1000;
			tally.add(ids, times, source, record, null);
		}
		long allocated = StutterMonitorTest.allocated() - before;
		// A per-sample allocation would be at least 320 KB over 20,000 samples.
		assertTrue(allocated < StutterMonitorTest.NOISE_BYTES, "20,000 steady-state samples allocated " + allocated + " bytes");
	}

	// One thread whose CPU grows by `step` ns per sample, in group `name`; the second threadIds() call (the first sample
	// that counts) can hang, ignoring interrupts, until released, like a sampler thread the scheduler doesn't run.
	private static final class SteadySource implements ThreadSampler.Source {
		final String name;
		final long step;
		final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
		final java.util.concurrent.CountDownLatch release;
		volatile @Nullable Thread caller;
		long cpu;
		int calls;

		SteadySource(String name, long step, boolean hang) {
			this.name = name;
			this.step = step;
			this.release = new java.util.concurrent.CountDownLatch(hang ? 1 : 0);
		}

		@Override
		public long[] threadIds() {
			caller = Thread.currentThread();
			if (++calls == 2) {
				entered.countDown();
				boolean interrupted = false;
				while (release.getCount() > 0) {
					try {
						release.await();
					} catch (InterruptedException e) {
						interrupted = true;
					}
				}
				if (interrupted) {
					Thread.currentThread().interrupt();
				}
			}
			return new long[]{7};
		}

		@Override
		public long[] cpuTimes(long[] ids) {
			cpu += step;
			return new long[]{cpu};
		}

		@Override
		public @Nullable String[] names(long[] ids) {
			return new String[]{name};
		}

		@Override
		public long processCpu() {
			return -1;
		}
	}

	private static int samples(StutterRings rings) {
		return rings.snapshot().samples().length / StutterRings.SAMPLE_STRIDE;
	}

	// review-8 ST-1: stopping the last capture runs on the render thread, so stop() never waits for the sampler thread, even
	// one that doesn't answer its interrupt; that worker never writes into the rings of a capture started after it.
	@Test
	void stopNeverWaitsAndAStoppedWorkerStaysOutOfTheNextCapture() throws Exception {
		SteadySource hung = new SteadySource("DH-Worker-1", 5_000_000, true);
		SteadySource next = new SteadySource("Server thread", 1_000_000, false);
		java.util.Iterator<SteadySource> sources = List.of(hung, next).iterator();
		ThreadSampler sampler = new ThreadSampler(sources::next);
		StutterRings first = new StutterRings(0);
		StutterRings second = new StutterRings(0);
		sampler.start(first);
		assertTrue(hung.entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "the sampler took its first sample");

		long before = System.nanoTime();
		sampler.stop();
		long stopMs = (System.nanoTime() - before) / 1_000_000;
		assertTrue(stopMs < 100, "stop() returned after " + stopMs + " ms");
		assertFalse(sampler.running(), "no sampler for this capture any more");

		sampler.start(second);
		hung.release.countDown();
		Thread old = hung.caller;
		old.join(5_000);
		assertFalse(old.isAlive(), "the stopped worker ended once it could");
		long deadline = System.nanoTime() + 5_000_000_000L;
		while (samples(second) < 2 && System.nanoTime() < deadline) {
			Thread.sleep(20);
		}
		sampler.stop();
		assertTrue(samples(second) >= 2, "the new capture's worker samples");
		assertEquals(0, samples(first), "the stopped worker wrote nothing, not even into its own capture's rings");
		long[] s = second.snapshot().samples();
		for (int i = 0; i + StutterRings.SAMPLE_STRIDE <= s.length; i += StutterRings.SAMPLE_STRIDE) {
			assertEquals(0, s[i + StutterRings.S_DH], "only the new worker's threads in the new capture's rings");
			assertEquals(1_000_000, s[i + StutterRings.S_SERVER]);
		}
	}
}
