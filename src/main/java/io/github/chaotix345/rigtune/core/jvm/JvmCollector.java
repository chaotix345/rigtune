package io.github.chaotix345.rigtune.core.jvm;

import org.jspecify.annotations.Nullable;

import java.util.List;

// The garbage collector families (docs/research/v0.4/jvm-gc.md §1.4, bean names re-checked on Temurin 25.0.4.1): the Use*GC
// option that selects each, its bean names, and its fact. The display names are the collectors' own names, the same in
// every language.
public enum JvmCollector {
	G1("G1", "UseG1GC", "jvm-gc-g1", "G1 "),
	ZGC("ZGC", "UseZGC", "jvm-gc-zgc", "ZGC "),
	SHENANDOAH("Shenandoah", "UseShenandoahGC", "jvm-gc-shenandoah", "Shenandoah "),
	PARALLEL("Parallel", "UseParallelGC", "jvm-gc-parallel", "PS "),
	SERIAL("Serial", "UseSerialGC", "jvm-gc-serial", "Copy", "MarkSweepCompact"),
	EPSILON("Epsilon", "UseEpsilonGC", "jvm-gc-epsilon", "Epsilon "),
	OTHER(null, null, "jvm-gc-other");

	private final @Nullable String displayName;
	private final @Nullable String option;
	private final String fact;
	private final List<String> beanNames;

	// A bean name ending in a space is a prefix; otherwise the name must match exactly.
	JvmCollector(@Nullable String displayName, @Nullable String option, String fact, String... beanNames) {
		this.displayName = displayName;
		this.option = option;
		this.fact = fact;
		this.beanNames = List.of(beanNames);
	}

	public @Nullable String displayName() {
		return displayName;
	}

	// The Use*GC option name, null for OTHER.
	public @Nullable String option() {
		return option;
	}

	// How the flag that selects it is written: "-XX:+UseZGC"; null for OTHER.
	public @Nullable String flag() {
		return option == null ? null : "-XX:+" + option;
	}

	public String fact() {
		return fact;
	}

	// From the GarbageCollectorMXBean names; null when none match.
	public static @Nullable JvmCollector fromBeans(List<String> beans) {
		for (String bean : beans) {
			for (JvmCollector c : values()) {
				for (String name : c.beanNames) {
					if (name.endsWith(" ") ? bean.startsWith(name) : bean.equals(name)) {
						return c;
					}
				}
			}
		}
		return null;
	}
}
