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
		FakeSource source = new FakeSource();
		String[] names = {"Render thread", "Server thread", "Worker-Main-1", "Worker-Main-2", "IO-Worker-7", "Chunk Render Task Executor #3",
				"DH-Render-1", "Netty Local IO #0", "Signal Dispatcher", "RigTune worker"};
		for (int i = 0; i < names.length; i++) {
			source.thread(10 + i, names[i]);
		}
		ThreadSampler.Tally tally = new ThreadSampler.Tally();
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
		assertEquals(12, source.nameLookups, "each thread's name read once: 10 at the start, then Worker-Main-3 and DH-Worker-2");
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
}
