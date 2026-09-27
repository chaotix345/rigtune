package io.github.chaotix345.rigtune.client.stutter;

import org.junit.jupiter.api.Test;

import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

// The live-set sample GcListener takes after a major collection (docs/v0.5/SPEC.md 2S SD-6): the old generation's pool
// when there is one, else only the heap pools (non-generational Shenandoah has one heap pool, "Shenandoah"; Metaspace,
// Compressed Class Space and the CodeHeaps are in the same map and aren't the heap).
class GcListenerTest {
	private static final long MB = 1024 * 1024;

	private static MemoryUsage used(long mb) {
		return new MemoryUsage(0, mb * MB, mb * MB, -1);
	}

	@Test
	void oldGenerationUsedSumsOnlyTheHeapPools() {
		Map<String, MemoryUsage> shenandoah = Map.of("Shenandoah", used(1024), "Metaspace", used(400), "CodeHeap 'profiled nmethods'", used(100),
				"Compressed Class Space", used(20), "CodeHeap 'non-nmethods'", used(2));
		assertEquals(1024 * MB, GcListener.oldGenerationUsed(shenandoah, List.of("Shenandoah")), "Shenandoah's one heap pool, not Metaspace or the CodeHeaps");

		Map<String, MemoryUsage> g1 = Map.of("G1 Old Gen", used(3072), "G1 Eden Space", used(1024), "G1 Survivor Space", used(100), "Metaspace", used(400));
		assertEquals(3072 * MB, GcListener.oldGenerationUsed(g1, List.of("G1 Eden Space", "G1 Old Gen", "G1 Survivor Space")), "G1: the old pool, as before");
		Map<String, MemoryUsage> zgc = Map.of("ZGC Old Generation", used(2048), "ZGC Young Generation", used(1024), "Metaspace", used(400));
		assertEquals(2048 * MB, GcListener.oldGenerationUsed(zgc, List.of("ZGC Old Generation", "ZGC Young Generation")), "ZGC: the old pool, as before");

		assertEquals(0, GcListener.oldGenerationUsed(shenandoah, List.of()), "no old pool and no heap pool names: no sample");
	}
}
