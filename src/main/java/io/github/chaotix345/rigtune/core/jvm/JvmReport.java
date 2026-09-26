package io.github.chaotix345.rigtune.core.jvm;

import org.jspecify.annotations.Nullable;

// What the JVM & memory screen shows (docs/v0.4/SPEC.md 6, C4). Never holds raw JVM arguments. Skeleton from the
// contracts commit; the JVM workstream owns and extends it.
public record JvmReport(boolean available, @Nullable String javaVersion, @Nullable String vendor, @Nullable String collector) {
	public static final JvmReport UNAVAILABLE = new JvmReport(false, null, null, null);
}
