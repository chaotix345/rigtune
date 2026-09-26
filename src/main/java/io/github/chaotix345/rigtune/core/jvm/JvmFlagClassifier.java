package io.github.chaotix345.rigtune.core.jvm;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Turns a JvmSnapshot into a JvmReport (docs/v0.4/SPEC.md 6, docs/research/v0.4/jvm-gc.md §1.3, §2 and §5.2): which
// collector runs and whether it was typed, the findings, and the jvm- facts. The runtime check needs no per-JDK table: a
// -XX flag the JVM says doesn't exist was ignored, and a boolean that reads back the other value was overridden. The rest
// is the curated rows of research §2 (re-check them with each new Java the game ships with). Without HotSpot's diagnostic
// bean or the GC beans nothing is concluded: no findings and no facts, so no jvm- rule can fire.
public final class JvmFlagClassifier {
	private static final long MIB = 1024L * 1024L;
	// Never checked: added by launchers or the version JSON, never typed by the player (research §1.1), and the argument-file
	// options, which the JVM reads while parsing and getVMOption doesn't know.
	static final Set<String> NOT_CHECKED = Set.of("HeapDumpPath", "MetaspaceSize", "Flags", "VMOptionsFile");
	// An option every HotSpot JVM has: a diagnostic bean that can't find it isn't a working one (OpenJ9 may register a stub).
	static final String ALWAYS_PRESENT = "MaxHeapSize";
	static final List<String> AIKAR_MARKERS = List.of("using.aikars.flags", "aikars.new.flags");
	// Aikar's distinctive flags (research §5.2); the launchers' own default set shares only G1NewSizePercent with them.
	static final List<String> AIKAR_DISTINCTIVE = List.of("G1NewSizePercent", "G1MaxNewSizePercent", "G1MixedGCLiveThresholdPercent",
			"SurvivorRatio", "MaxTenuringThreshold", "G1RSetUpdatingPauseTimePercent", "InitiatingHeapOccupancyPercent");
	static final int AIKAR_MIN_DISTINCTIVE = 4;

	private JvmFlagClassifier() {
	}

	public static JvmReport classify(JvmSnapshot snapshot) {
		JvmArgs args = JvmArgs.parse(snapshot.inputArguments());
		VmOptions options = snapshot.options();
		Collector running = collector(options, snapshot.gcBeanNames(), args);
		long maxHeapMb = snapshot.maxHeapBytes() > 0 ? snapshot.maxHeapBytes() / MIB : -1;
		long initialHeapMb = snapshot.initialHeapBytes() > 0 ? snapshot.initialHeapBytes() / MIB : -1;
		if (options == null || snapshot.gcBeanNames().isEmpty() || !lookup(options, ALWAYS_PRESENT).found()) {
			return new JvmReport(false, snapshot.javaVersion(), snapshot.vendor(), running.collector, running.typed, maxHeapMb, initialHeapMb,
					List.of(), Set.of());
		}
		List<JvmFinding> findings = new ArrayList<>();
		ignoredOrOverridden(args, options, findings);
		JvmCollector c = running.collector;
		if (c == JvmCollector.G1) {
			if (args.youngGenBytes() > 0) {
				findings.add(new JvmFinding(JvmFinding.Kind.YOUNG_GEN_FIXED, "-Xmn"));
			}
			for (String name : List.of("NewSize", "MaxNewSize")) {
				JvmArgs.XxFlag flag = args.xx(name);
				if (flag != null) {
					findings.add(new JvmFinding(JvmFinding.Kind.YOUNG_GEN_FIXED, flag.display()));
				}
			}
		}
		if ((c == JvmCollector.SERIAL || c == JvmCollector.PARALLEL) && running.typed) {
			findings.add(new JvmFinding(JvmFinding.Kind.STOP_THE_WORLD_GC, c.flag()));
		}
		if (c == JvmCollector.EPSILON) {
			findings.add(new JvmFinding(JvmFinding.Kind.NO_GC, c.flag()));
		}
		boolean serverSet = serverSet(args, findings);
		if (!serverSet && explicitDisabled(args, options)) {
			findings.add(new JvmFinding(JvmFinding.Kind.EXPLICIT_GC_DISABLED, "-XX:+DisableExplicitGC"));
		}
		if (args.maxHeapSettings() >= 2) {
			findings.add(new JvmFinding(JvmFinding.Kind.XMX_DUPLICATE, "-Xmx"));
		}
		List<JvmFinding> unique = List.copyOf(new LinkedHashSet<>(findings));
		Set<String> facts = new LinkedHashSet<>();
		facts.add(JvmFacts.PROBED);
		facts.add(c.fact());
		if (running.typed) {
			facts.add(JvmFacts.GC_TYPED);
		}
		for (JvmFinding finding : unique) {
			if (finding.kind().fact() != null) {
				facts.add(finding.kind().fact());
			}
		}
		return new JvmReport(true, snapshot.javaVersion(), snapshot.vendor(), c, running.typed, maxHeapMb, initialHeapMb, unique, facts);
	}

	private record Collector(JvmCollector collector, boolean typed) {
	}

	// The Use*GC option that reads true (and its origin) decides; the bean names when the options can't tell; OTHER when
	// neither can. Typed from the origin, or from the command line when the origin is unknown.
	private static Collector collector(@Nullable VmOptions options, List<String> beans, JvmArgs args) {
		if (options != null) {
			for (JvmCollector c : JvmCollector.values()) {
				if (c.option() == null) {
					continue;
				}
				VmOptions.Lookup lookup = lookup(options, c.option());
				if (lookup.isTrue()) {
					return new Collector(c, lookup.origin() != null && lookup.origin().typed());
				}
			}
		}
		JvmCollector fromBeans = JvmCollector.fromBeans(beans);
		JvmCollector c = fromBeans == null ? JvmCollector.OTHER : fromBeans;
		JvmArgs.XxFlag typed = c.option() == null ? null : args.xx(c.option());
		return new Collector(c, typed != null && Boolean.TRUE.equals(typed.enabled()));
	}

	// Research §1.3: "does not exist" = ignored by this JVM (obsolete, e.g. ZGenerational); a boolean reading back the other
	// value = overridden (UseNUMA, UseLargePages on Windows). A failed lookup concludes nothing.
	private static void ignoredOrOverridden(JvmArgs args, VmOptions options, List<JvmFinding> findings) {
		for (JvmArgs.XxFlag flag : args.xx().values()) {
			if (NOT_CHECKED.contains(flag.name())) {
				continue;
			}
			VmOptions.Lookup lookup = lookup(options, flag.name());
			if (lookup.status() == VmOptions.Status.MISSING) {
				findings.add(new JvmFinding(JvmFinding.Kind.IGNORED, flag.display()));
			} else if (lookup.found() && flag.enabled() != null && ("true".equals(lookup.value()) || "false".equals(lookup.value()))
					&& Boolean.parseBoolean(lookup.value()) != flag.enabled()) {
				findings.add(new JvmFinding(JvmFinding.Kind.OVERRIDDEN, flag.display()));
			}
		}
	}

	// Aikar's markers, or at least 4 of its distinctive flags (by name, so the >12 GB variant and sets based on it count
	// too): each marker and distinctive flag found is one finding, and so are the set's flags that keep memory reserved
	// (-Xms, AlwaysPreTouch) and DisableExplicitGC, so the found line lists what to remove.
	private static boolean serverSet(JvmArgs args, List<JvmFinding> findings) {
		List<String> markers = AIKAR_MARKERS.stream().filter(args.propertyKeys()::contains).toList();
		List<JvmArgs.XxFlag> distinctive = AIKAR_DISTINCTIVE.stream().map(args::xx).filter(f -> f != null).toList();
		if (markers.isEmpty() && distinctive.size() < AIKAR_MIN_DISTINCTIVE) {
			return false;
		}
		markers.forEach(m -> findings.add(new JvmFinding(JvmFinding.Kind.SERVER_SET, "-D" + m)));
		distinctive.forEach(f -> findings.add(new JvmFinding(JvmFinding.Kind.SERVER_SET, f.display())));
		if (args.initialHeapBytes() > 0) {
			findings.add(new JvmFinding(JvmFinding.Kind.SERVER_SET, "-Xms"));
		}
		for (String name : List.of("AlwaysPreTouch", "DisableExplicitGC")) {
			JvmArgs.XxFlag flag = args.xx(name);
			if (flag != null && Boolean.TRUE.equals(flag.enabled())) {
				findings.add(new JvmFinding(JvmFinding.Kind.SERVER_SET, flag.display()));
			}
		}
		return true;
	}

	// -XX:+DisableExplicitGC typed and in effect (or its effective value unreadable).
	private static boolean explicitDisabled(JvmArgs args, VmOptions options) {
		JvmArgs.XxFlag flag = args.xx("DisableExplicitGC");
		if (flag == null || !Boolean.TRUE.equals(flag.enabled())) {
			return false;
		}
		VmOptions.Lookup lookup = lookup(options, "DisableExplicitGC");
		return !lookup.found() ? lookup.status() == VmOptions.Status.FAILED : lookup.isTrue();
	}

	private static VmOptions.Lookup lookup(VmOptions options, String name) {
		try {
			VmOptions.Lookup lookup = options.lookup(name);
			return lookup == null ? VmOptions.Lookup.FAILED : lookup;
		} catch (RuntimeException e) {
			return VmOptions.Lookup.FAILED;
		}
	}
}
