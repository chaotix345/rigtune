package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// What a GarbageCollectorMXBean notification means (docs/research/v0.4/stutter.md §1.2, verified on G1, ZGC, Shenandoah,
// Serial and Parallel on Java 25), as int flags so the GC ring stays primitive:
// - gcAction "end of GC cycle" is a concurrent cycle (context only, never a pause); everything else is a stop-the-world
//   pause ("end of minor/major GC", "end of concurrent GC pause", "end of GC pause" and Shenandoah's phase names).
// - PHASE: one pause phase of a concurrent cycle (ZGC/Shenandoah pause beans, G1's Remark/Cleanup); not a collection
//   of its own. FULL: a whole-heap stop-the-world collection. EXPLICIT: cause System.gc(). STALL_HINT: application
//   threads waited for memory (ZGC "Allocation Stall", Shenandoah "Allocation Failure"); never measured, only noted.
//   MAJOR: its usage-after is a live-set sample.
public final class GcKind {
	public static final int PAUSE = 1;
	public static final int CYCLE = 1 << 1;
	public static final int FULL = 1 << 2;
	public static final int EXPLICIT = 1 << 3;
	public static final int STALL_HINT = 1 << 4;
	public static final int MAJOR = 1 << 5;
	public static final int PHASE = 1 << 6;

	private static final String CYCLE_ACTION = "end of GC cycle";
	private static final String[] NAMES = {"PAUSE", "CYCLE", "FULL", "EXPLICIT", "STALL_HINT", "MAJOR", "PHASE"};

	private GcKind() {
	}

	public static int classify(@Nullable String bean, @Nullable String action, @Nullable String cause) {
		String b = bean == null ? "" : bean;
		String a = action == null ? "" : action;
		String c = cause == null ? "" : cause;
		int flags = a.equals(CYCLE_ACTION) ? CYCLE : PAUSE;
		boolean shenandoahWhole = b.startsWith("Shenandoah") && (a.equals("Degenerated GC") || a.equals("Full GC"));
		if (b.equals("G1 Old Generation") || b.equals("MarkSweepCompact") || b.equals("PS MarkSweep") || shenandoahWhole) {
			flags |= FULL | MAJOR;
		}
		if ((flags & PAUSE) != 0 && !shenandoahWhole
				&& (b.equals("G1 Concurrent GC") || b.startsWith("ZGC") && b.endsWith("Pauses") || b.equals("Shenandoah Pauses"))) {
			flags |= PHASE;
		}
		if (b.equals("G1 Concurrent GC") || (flags & CYCLE) != 0 && (b.startsWith("ZGC Major") || b.startsWith("Shenandoah"))) {
			flags |= MAJOR;
		}
		if (c.equals("System.gc()")) {
			flags |= EXPLICIT;
		}
		if (c.equals("Allocation Stall") || b.startsWith("Shenandoah") && c.equals("Allocation Failure")) {
			flags |= STALL_HINT;
		}
		return flags;
	}

	// One collection: a concurrent cycle, or a pause that isn't a phase of one.
	public static boolean collection(int flags) {
		return (flags & CYCLE) != 0 || (flags & PAUSE) != 0 && (flags & PHASE) == 0;
	}

	public static boolean pause(int flags) {
		return (flags & PAUSE) != 0;
	}

	// The collector family, for the gcCollector condition key and stutter.json.
	public static @Nullable String family(@Nullable String bean) {
		if (bean == null) {
			return null;
		}
		if (bean.startsWith("G1 ")) {
			return "g1";
		}
		if (bean.startsWith("ZGC ")) {
			return "zgc";
		}
		if (bean.startsWith("Shenandoah ")) {
			return "shenandoah";
		}
		if (bean.startsWith("PS ")) {
			return "parallel";
		}
		if (bean.equals("Copy") || bean.equals("MarkSweepCompact")) {
			return "serial";
		}
		return null;
	}

	public static @Nullable String family(Collection<String> beans) {
		for (String bean : beans) {
			String family = family(bean);
			if (family != null) {
				return family;
			}
		}
		return null;
	}

	public static boolean oldPool(@Nullable String pool) {
		return pool != null && (pool.contains("Old") || pool.contains("Tenured"));
	}

	public static List<String> describe(int flags) {
		List<String> out = new ArrayList<>();
		for (int i = 0; i < NAMES.length; i++) {
			if ((flags & (1 << i)) != 0) {
				out.add(NAMES[i]);
			}
		}
		return out;
	}
}
