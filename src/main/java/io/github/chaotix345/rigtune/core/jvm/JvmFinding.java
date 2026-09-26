package io.github.chaotix345.rigtune.core.jvm;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.regex.Pattern;

// One note about the Java arguments (docs/v0.4/SPEC.md 6): the kind and the flag's name as RigTune shows it, built only
// from a -XX name (sign kept, value dropped), -Xmn/-Xms/-Xmx or RigTune's own -D marker names. Never a value or a path.
public record JvmFinding(Kind kind, String flag) {
	private static final Pattern NAME = Pattern.compile("-XX:[+-]?[A-Za-z][A-Za-z0-9_]*|-Xm[nsx]|-D[a-z][a-z.]*");

	public enum Kind {
		// getVMOption finds no such option: this Java ignored it (e.g. ZGenerational, removed in Java 24).
		IGNORED("jvm-ignored-flags", JvmFacts.IGNORED_FLAGS),
		// A boolean reads back the other value: this Java turned it off or on anyway (e.g. UseNUMA on Windows).
		OVERRIDDEN("jvm-ignored-flags", JvmFacts.IGNORED_FLAGS),
		YOUNG_GEN_FIXED("jvm-young-gen-fixed", JvmFacts.YOUNG_GEN_FIXED),
		// Typed Serial or Parallel (the facts are the collector's and jvm-gc-typed).
		STOP_THE_WORLD_GC("jvm-stop-the-world-gc", null),
		// Epsilon (the fact is jvm-gc-epsilon).
		NO_GC("jvm-no-gc", null),
		SERVER_SET("jvm-server-flags", JvmFacts.SERVER_FLAGS),
		EXPLICIT_GC_DISABLED("jvm-explicit-gc-disabled", JvmFacts.EXPLICIT_GC_DISABLED),
		XMX_DUPLICATE("jvm-xmx-duplicate", JvmFacts.XMX_DUPLICATE);

		private final String adviceId;
		private final @Nullable String fact;

		Kind(String adviceId, @Nullable String fact) {
			this.adviceId = adviceId;
			this.fact = fact;
		}

		// The rules' advice id (without "advice:") whose "Found in your Java arguments" line lists this finding.
		public String adviceId() {
			return adviceId;
		}

		// The fact it adds to HardwareProfile.flags, or null when the collector facts carry it.
		public @Nullable String fact() {
			return fact;
		}

		// What JvmScreen says about it.
		public String reasonKey() {
			return switch (this) {
				case IGNORED -> "rigtune.jvm.finding.ignored";
				case OVERRIDDEN -> "rigtune.jvm.finding.overridden";
				case YOUNG_GEN_FIXED -> "rigtune.jvm.finding.young_gen_fixed";
				case STOP_THE_WORLD_GC -> "rigtune.jvm.finding.stop_the_world_gc";
				case NO_GC -> "rigtune.jvm.finding.no_gc";
				case SERVER_SET -> "rigtune.jvm.finding.server_set";
				case EXPLICIT_GC_DISABLED -> "rigtune.jvm.finding.explicit_gc_disabled";
				case XMX_DUPLICATE -> "rigtune.jvm.finding.xmx_duplicate";
			};
		}
	}

	public JvmFinding {
		Objects.requireNonNull(kind, "kind");
		if (flag == null || !NAME.matcher(flag).matches()) {
			throw new IllegalArgumentException("not a flag name RigTune shows");
		}
	}
}
