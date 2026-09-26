package io.github.chaotix345.rigtune.client.stutter;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.core.stutter.StutterView;

import java.nio.file.Path;

// Stutter Doctor (docs/v0.4/SPEC.md 5): the opt-in session monitor (settings.json stutterMonitor), stutter.json and the
// analysis behind StutterScreen. RealController delegates every C4 stutter method here in one line. Skeleton from the
// contracts commit; the Stutter Doctor workstream owns it.
public final class StutterService {
	private final RealController controller;
	private final Path configDir;

	public StutterService(RealController controller, Path configDir) {
		this.controller = controller;
		this.configDir = configDir;
	}

	public StutterView view() {
		return StutterView.EMPTY;
	}

	public void setMonitor(boolean on) {
	}

	public void pause(boolean paused) {
	}

	public void clear() {
	}

	public String summary() {
		return "";
	}
}
