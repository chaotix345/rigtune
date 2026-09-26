package io.github.chaotix345.rigtune.client.jvm;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.probe.JvmProbe;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;

import java.nio.file.Path;

// JVM and GC advice (docs/v0.4/SPEC.md 6): the JVM & memory screen's and the share report's view of the once-per-session
// JVM probe (client/probe/JvmProbe, started by HardwareProbe with every scan). RealController delegates jvmReport() here
// in one line. Advice-only: it writes nothing.
public final class JvmService {
	private final RealController controller;
	private final Path configDir;

	public JvmService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	// The latest finished probe; if none has started yet (the screen opened before the first scan), start one.
	public JvmReport report() {
		JvmReport current = JvmProbe.current();
		if (current.javaVersion() == null) {
			JvmProbe.ensureStarted();
		}
		return current;
	}
}
