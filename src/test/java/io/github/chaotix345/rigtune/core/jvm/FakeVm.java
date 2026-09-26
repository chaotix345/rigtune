package io.github.chaotix345.rigtune.core.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// A HotSpot 25 stand-in for the classifier tests (behaviour as probed on Temurin 25.0.4.1, docs/research/v0.4/jvm-gc.md
// §1.3 and docs/v0.4/design/ws-j.md): an option the JVM knows reads back what was typed, with the origin of where it was
// typed; one it doesn't know (ZGenerational, removed in 24) is MISSING; experimental options are MISSING unless unlocked;
// UseNUMA/UseLargePages read false on Windows; with no collector typed, G1 is ERGONOMIC. Every argument comes from the
// command line unless it's in the JAVA_TOOL_OPTIONS list.
final class FakeVm implements VmOptions {
	static final Set<String> BOOLEANS = Set.of("UseG1GC", "UseZGC", "UseShenandoahGC", "UseParallelGC", "UseSerialGC", "UseEpsilonGC",
			"UnlockExperimentalVMOptions", "UseNUMA", "UseLargePages", "DisableExplicitGC", "AlwaysPreTouch", "ParallelRefProcEnabled",
			"PerfDisableSharedMem", "UseCompactObjectHeaders", "UseStringDeduplication", "UseCompressedOops");
	static final Set<String> VALUES = Set.of("HeapDumpPath", "MetaspaceSize", "G1ReservePercent", "MaxGCPauseMillis", "G1HeapRegionSize",
			"G1HeapWastePercent", "G1MixedGCCountTarget", "InitiatingHeapOccupancyPercent", "G1RSetUpdatingPauseTimePercent", "SurvivorRatio",
			"MaxTenuringThreshold", "NewSize", "MaxNewSize", "MaxHeapSize", "InitialHeapSize", "ErrorFile", "ThreadStackSize");
	static final Set<String> EXPERIMENTAL = Set.of("G1NewSizePercent", "G1MaxNewSizePercent", "G1MixedGCLiveThresholdPercent", "UseEpsilonGC");
	static final Set<String> WINDOWS_OFF = Set.of("UseNUMA", "UseLargePages");

	private final Map<String, Lookup> options = new HashMap<>();
	private final Set<String> failing;

	private FakeVm(List<String> commandLine, List<String> toolOptions, Set<String> failing) {
		this.failing = failing;
		for (String name : BOOLEANS) {
			options.put(name, Lookup.found("false", Origin.DEFAULT));
		}
		for (String name : VALUES) {
			options.put(name, Lookup.found("0", Origin.DEFAULT));
		}
		List<String> all = new ArrayList<>(toolOptions);
		all.addAll(commandLine);
		boolean unlocked = false;
		for (String arg : all) {
			Origin origin = toolOptions.contains(arg) && !commandLine.contains(arg) ? Origin.ENVIRON_VAR : Origin.VM_CREATION;
			JvmArgs one = JvmArgs.parse(List.of(arg));
			for (JvmArgs.XxFlag flag : one.xx().values()) {
				String name = flag.name();
				if (name.equals("UnlockExperimentalVMOptions") && Boolean.TRUE.equals(flag.enabled())) {
					unlocked = true;
				}
				boolean known = BOOLEANS.contains(name) || VALUES.contains(name) || EXPERIMENTAL.contains(name);
				if (!known) {
					continue;
				}
				String value = flag.enabled() == null ? "1" : WINDOWS_OFF.contains(name) ? "false" : flag.enabled().toString();
				options.put(name, Lookup.found(value, origin));
			}
			if (one.youngGenBytes() > 0) {
				options.put("NewSize", Lookup.found(Long.toString(one.youngGenBytes()), origin));
				options.put("MaxNewSize", Lookup.found(Long.toString(one.youngGenBytes()), origin));
			}
		}
		if (!unlocked) {
			EXPERIMENTAL.forEach(options::remove);
		}
		boolean collectorTyped = JvmCollectorsTyped.any(options);
		if (!collectorTyped) {
			options.put("UseG1GC", Lookup.found("true", Origin.ERGONOMIC));
		}
	}

	static FakeVm of(List<String> commandLine) {
		return new FakeVm(commandLine, List.of(), Set.of());
	}

	static FakeVm withToolOptions(List<String> toolOptions, List<String> commandLine) {
		return new FakeVm(commandLine, toolOptions, Set.of());
	}

	static FakeVm failing(List<String> commandLine, Set<String> failing) {
		return new FakeVm(commandLine, List.of(), failing);
	}

	// The GC bean names HotSpot 25 registers for the collector this VM runs.
	List<String> beans() {
		for (JvmCollector c : JvmCollector.values()) {
			if (c.option() != null && lookup(c.option()).isTrue()) {
				return switch (c) {
					case G1 -> List.of("G1 Young Generation", "G1 Concurrent GC", "G1 Old Generation");
					case ZGC -> List.of("ZGC Minor Cycles", "ZGC Minor Pauses", "ZGC Major Cycles", "ZGC Major Pauses");
					case SHENANDOAH -> List.of("Shenandoah Pauses", "Shenandoah Cycles");
					case PARALLEL -> List.of("PS MarkSweep", "PS Scavenge");
					case SERIAL -> List.of("Copy", "MarkSweepCompact");
					case EPSILON -> List.of("Epsilon Heap");
					case OTHER -> List.of();
				};
			}
		}
		return List.of();
	}

	@Override
	public Lookup lookup(String name) {
		if (failing.contains(name)) {
			throw new IllegalStateException("simulated failure");
		}
		return options.getOrDefault(name, Lookup.MISSING);
	}

	private static final class JvmCollectorsTyped {
		static boolean any(Map<String, Lookup> options) {
			for (JvmCollector c : JvmCollector.values()) {
				Lookup l = c.option() == null ? null : options.get(c.option());
				if (l != null && l.isTrue()) {
					return true;
				}
			}
			return false;
		}
	}
}
