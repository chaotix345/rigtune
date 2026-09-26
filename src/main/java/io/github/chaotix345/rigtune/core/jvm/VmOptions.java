package io.github.chaotix345.rigtune.core.jvm;

import org.jspecify.annotations.Nullable;

// HotSpotDiagnosticMXBean.getVMOption as a lookup core can test without the JDK class (docs/research/v0.4/jvm-gc.md
// §1.3): a found option's effective value and origin, MISSING when the JVM says the option doesn't exist (it ignored an
// obsolete flag, or an experimental one is locked), FAILED when the call went wrong for another reason (then nothing is
// concluded about that flag).
@FunctionalInterface
public interface VmOptions {
	Lookup lookup(String name);

	enum Origin {
		DEFAULT, VM_CREATION, ENVIRON_VAR, CONFIG_FILE, MANAGEMENT, ERGONOMIC, ATTACH_ON_DEMAND, OTHER;

		// Set by the player (command line, JDK_JAVA_OPTIONS, JAVA_TOOL_OPTIONS or a flags file), not by the JVM.
		public boolean typed() {
			return this == VM_CREATION || this == ENVIRON_VAR || this == CONFIG_FILE;
		}
	}

	enum Status { FOUND, MISSING, FAILED }

	record Lookup(Status status, @Nullable String value, @Nullable Origin origin) {
		public static final Lookup MISSING = new Lookup(Status.MISSING, null, null);
		public static final Lookup FAILED = new Lookup(Status.FAILED, null, null);

		public static Lookup found(String value, Origin origin) {
			return new Lookup(Status.FOUND, value, origin);
		}

		public boolean found() {
			return status == Status.FOUND;
		}

		public boolean isTrue() {
			return found() && "true".equals(value);
		}
	}
}
