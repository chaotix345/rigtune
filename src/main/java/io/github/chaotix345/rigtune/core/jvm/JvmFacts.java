package io.github.chaotix345.rigtune.core.jvm;

import java.util.Set;

// The jvm- facts in HardwareProfile.flags (docs/v0.4/SPEC.md 6 with amendments J-M1 and "Launcher steps"). PROBED is set
// only when the probe read the HotSpot diagnostic bean and the GC beans; without it every jvm- flag in a rule evaluates
// UNKNOWN (ConditionEvaluator). RULE_FLAGS is the vocabulary rules may use (PROBED is not part of it; the rules updater
// refuses it).
public final class JvmFacts {
	public static final String PREFIX = "jvm-";
	public static final String PROBED = "jvm-probed";
	public static final String GC_TYPED = "jvm-gc-typed";
	public static final String IGNORED_FLAGS = "jvm-ignored-flags";
	public static final String YOUNG_GEN_FIXED = "jvm-young-gen-fixed";
	public static final String SERVER_FLAGS = "jvm-server-flags";
	public static final String EXPLICIT_GC_DISABLED = "jvm-explicit-gc-disabled";
	public static final String XMX_DUPLICATE = "jvm-xmx-duplicate";
	public static final Set<String> RULE_FLAGS = Set.of("jvm-gc-g1", "jvm-gc-zgc", "jvm-gc-shenandoah", "jvm-gc-parallel", "jvm-gc-serial",
			"jvm-gc-epsilon", "jvm-gc-other", GC_TYPED, IGNORED_FLAGS, YOUNG_GEN_FIXED, SERVER_FLAGS, EXPLICIT_GC_DISABLED, XMX_DUPLICATE);

	private JvmFacts() {
	}
}
