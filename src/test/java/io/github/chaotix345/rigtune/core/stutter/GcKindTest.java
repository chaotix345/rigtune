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

	// The live set is read from the old generation's pool when there is one, else from every pool together.
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
