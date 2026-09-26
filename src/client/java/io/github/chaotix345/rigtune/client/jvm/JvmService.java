package io.github.chaotix345.rigtune.client.jvm;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;

import java.nio.file.Path;

// JVM and GC advice (docs/v0.4/SPEC.md 6): the once-per-session JVM probe and the JVM & memory screen's report.
// RealController delegates jvmReport() here in one line. Skeleton from the contracts commit; the JVM workstream owns it.
public final class JvmService {
	private final RealController controller;
	private final Path configDir;

	public JvmService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	public JvmReport report() {
		return JvmReport.UNAVAILABLE;
	}
}
