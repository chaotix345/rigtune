package io.github.chaotix345.rigtune.core.launcher;

import io.github.chaotix345.rigtune.core.model.Recommendation;
import org.jspecify.annotations.Nullable;

// Which recommendations get the "In <launcher>: <steps>" line: every advice whose rule id starts with "ram-" (the
// convention documented in RULES_SCHEMA.md), and only when the launcher is known.
public final class LauncherAdvice {
	public static final String RAM_ADVICE_PREFIX = "advice:ram-";

	private LauncherAdvice() {
	}

	public static boolean isRamAdvice(Recommendation recommendation) {
		return recommendation.id().startsWith(RAM_ADVICE_PREFIX);
	}

	public static @Nullable String stepsKey(Recommendation recommendation, LauncherInfo launcher) {
		return isRamAdvice(recommendation) ? launcher.stepsKey() : null;
	}
}
