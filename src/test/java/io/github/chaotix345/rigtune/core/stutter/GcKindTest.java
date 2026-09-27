package io.github.chaotix345.rigtune.core.stutter;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// AC5.3 (GcKindTest): every bean/action/cause string of research §1.2 (G1, ZGC, Shenandoah, Serial, Parallel).
class GcKindTest {
	private static final Map<String, Integer> NAMES = Map.of("PAUSE", GcKind.PAUSE, "CYCLE", GcKind.CYCLE, "FULL", GcKind.FULL,
			"EXPLICIT", GcKind.EXPLICIT, "STALL_HINT", GcKind.STALL_HINT, "MAJOR", GcKind.MAJOR, "PHASE", GcKind.PHASE);

	record Row(String family, String bean, String action, String cause, int flags) {
	}

	static List<Row> rows() throws IOException {
		List<Row> rows = new ArrayList<>();
		try (InputStream in = GcKindTest.class.getResourceAsStream("/stutter/gc-strings.txt")) {
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] p = line.strip().split("\\|");
				int flags = Arrays.stream(p[4].split(",")).mapToInt(NAMES::get).reduce(0, (a, b) -> a | b);
				rows.add(new Row(p[0], p[1], p[2], p[3], flags));
			}
		}
		return rows;
	}

	@Test
	void everyProbedStringClassifiesAsExpected() throws IOException {
		List<Row> rows = rows();
		assertEquals(34, rows.size());
		for (Row r : rows) {
			assertEquals(GcKind.describe(r.flags()), GcKind.describe(GcKind.classify(r.bean(), r.action(), r.cause())), r.toString());
			assertEquals(r.family(), GcKind.family(r.bean()), r.toString());
		}
	}

	@Test
	void onlyTheEndOfACycleIsConcurrentAndEverythingElseIsAPause() throws IOException {
		for (Row r : rows()) {
			int flags = GcKind.classify(r.bean(), r.action(), r.cause());
			boolean cycle = r.action().equals("end of GC cycle");
			assertEquals(cycle, (flags & GcKind.CYCLE) != 0, r.toString());
			assertEquals(!cycle, (flags & GcKind.PAUSE) != 0, r.toString());
		}
	}

	// One System.gc() is one collection on every collector, however many pause phases it reports (ZGC: 8).
	@Test
	void collectionsCountCyclesAndWholePausesButNotPhases() {
		assertTrue(GcKind.collection(GcKind.classify("G1 Old Generation", "end of major GC", "System.gc()")));
		assertTrue(GcKind.collection(GcKind.classify("G1 Young Generation", "end of minor GC", "G1 Evacuation Pause")));
		assertFalse(GcKind.collection(GcKind.classify("G1 Concurrent GC", "end of concurrent GC pause", "No GC")));
		assertFalse(GcKind.collection(GcKind.classify("ZGC Major Pauses", "end of GC pause", "System.gc()")));
		assertTrue(GcKind.collection(GcKind.classify("ZGC Major Cycles", "end of GC cycle", "System.gc()")));
		assertFalse(GcKind.collection(GcKind.classify("Shenandoah Pauses", "Init Mark", "System.gc()")));
		assertTrue(GcKind.collection(GcKind.classify("Shenandoah Pauses", "Degenerated GC", "Allocation Failure")));
		assertTrue(GcKind.collection(GcKind.classify("MarkSweepCompact", "end of major GC", "System.gc()")));
	}

	@Test
	void unknownAndNullStringsAreSafe() {
		assertEquals(GcKind.PAUSE, GcKind.classify("Some Future GC", "end of something", "Why Not"));
		assertEquals(GcKind.PAUSE, GcKind.classify(null, null, null));
		assertNull(GcKind.family("Some Future GC"));
		assertNull(GcKind.family((String) null));
		assertEquals("g1", GcKind.family(List.of("G1 Young Generation", "G1 Old Generation")));
		assertEquals("zgc", GcKind.family(List.of("ZGC Minor Cycles", "ZGC Minor Pauses")));
		assertNull(GcKind.family(List.of()));
		assertEquals(new TreeSet<>(List.of("CYCLE", "EXPLICIT", "MAJOR")), new TreeSet<>(GcKind.describe(GcKind.CYCLE | GcKind.EXPLICIT | GcKind.MAJOR)));
	}

	// docs/v0.5/SPEC.md 2S NEW-1 (AC2S.11): the strings generational Shenandoah sent in the code-deciding run
	// (docs/v0.5/verification/stutter/new1-generational-shenandoah): young cycles, old markings and global cycles alike, so
	// none of them is a live-set sample there (the live set stays unmeasured); every other flag is unchanged. Without the
	// generational mode Shenandoah's cycles stay live-set samples.
	@Test
	void generationalShenandoahIsNeverALiveSetSample() {
		String[][] recorded = {
				{"Shenandoah Cycles", "end of GC cycle", "Concurrent GC"},
				{"Shenandoah Cycles", "end of GC cycle", "System.gc()"},
				{"Shenandoah Pauses", "Init Mark", "Concurrent GC"},
				{"Shenandoah Pauses", "Final Mark", "Concurrent GC"},
				{"Shenandoah Pauses", "Init Update Refs", "Concurrent GC"},
				{"Shenandoah Pauses", "Final Update Refs", "Concurrent GC"},
				{"Shenandoah Pauses", "Init Mark", "System.gc()"},
				{"Shenandoah Pauses", "Final Mark", "System.gc()"},
				{"Shenandoah Pauses", "Init Update Refs", "System.gc()"},
				{"Shenandoah Pauses", "Final Update Refs", "System.gc()"},
				// Not seen in the run (no allocation failure), the same collector's whole-heap pauses: still full pauses.
				{"Shenandoah Pauses", "Degenerated GC", "Allocation Failure"},
				{"Shenandoah Pauses", "Full GC", "Allocation Failure"}};
		for (String[] r : recorded) {
			int single = GcKind.classify(r[0], r[1], r[2]);
			int generational = GcKind.classify(r[0], r[1], r[2], true);
			assertEquals(0, generational & GcKind.MAJOR, String.join(" | ", r));
			assertEquals(single & ~GcKind.MAJOR, generational, String.join(" | ", r) + ": the other flags as without the mode");
			assertEquals(single, GcKind.classify(r[0], r[1], r[2], false));
		}
		assertTrue((GcKind.classify("Shenandoah Cycles", "end of GC cycle", "Concurrent GC", false) & GcKind.MAJOR) != 0);
		assertTrue((GcKind.classify("G1 Old Generation", "end of major GC", "G1 Compaction Pause", true) & GcKind.MAJOR) != 0, "only Shenandoah's");

		assertTrue(GcKind.shenandoahGenerational(List.of("Shenandoah Young Gen", "Shenandoah Old Gen")));
		assertFalse(GcKind.shenandoahGenerational(List.of("Shenandoah")));
		assertFalse(GcKind.shenandoahGenerational(List.of("G1 Eden Space", "G1 Old Gen", "G1 Survivor Space")));
		assertFalse(GcKind.shenandoahGenerational(List.of("ZGC Old Generation", "ZGC Young Generation")));
		assertFalse(GcKind.shenandoahGenerational(List.of()));
	}

	// The live set is read from the old generation's pool when there is one, else from the heap pools (GcListener).
	@Test
	void oldGenerationPools() {
		assertTrue(GcKind.oldPool("G1 Old Gen"));
		assertTrue(GcKind.oldPool("ZGC Old Generation"));
		assertTrue(GcKind.oldPool("PS Old Gen"));
		assertTrue(GcKind.oldPool("Tenured Gen"));
		assertFalse(GcKind.oldPool("G1 Eden Space"));
		assertFalse(GcKind.oldPool("Shenandoah"));
		assertFalse(GcKind.oldPool(null));
	}
}
