package io.github.chaotix345.rigtune.core.launcher;

import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import org.jspecify.annotations.Nullable;

// Which recommendations get the "In <launcher>: <steps>" line: every advice whose rule id starts with "ram-" (the
// convention documented in RULES_SCHEMA.md), and only when the launcher is known. v0.4 (docs/v0.4/SPEC.md 6): advice
// whose id starts with "jvm-" gets the launcher's Java-arguments steps instead.
public final class LauncherAdvice {
	public static final String RAM_ADVICE_PREFIX = "advice:ram-";
	public static final String JVM_ADVICE_PREFIX = "advice:jvm-";

	private LauncherAdvice() {
	}

	public static boolean isRamAdvice(Recommendation recommendation) {
		return recommendation.id().startsWith(RAM_ADVICE_PREFIX);
	}

	public static @Nullable String stepsKey(Recommendation recommendation, LauncherInfo launcher) {
		return isRamAdvice(recommendation) ? launcher.stepsKey() : null;
	}

	public static boolean isJvmAdvice(Recommendation recommendation) {
		return recommendation.id().startsWith(JVM_ADVICE_PREFIX);
	}

	// The Java-arguments steps under a jvm-* advice, when the launcher is known.
	public static @Nullable String jvmStepsKey(Recommendation recommendation, LauncherInfo launcher) {
		return isJvmAdvice(recommendation) ? launcher.jvmStepsKey() : null;
	}

	// Under a ram-* advice: the Java arguments also set -Xmx, and this launcher lets a typed -Xmx win over its memory
	// setting (docs/research/v0.4/launcher-steps.md finding 3), so the memory steps alone would change nothing.
	public static boolean typedXmxWins(Recommendation recommendation, LauncherInfo launcher, JvmReport jvm) {
		return isRamAdvice(recommendation) && launcher.typedXmxWins() && jvm.xmxDuplicate();
	}
}
