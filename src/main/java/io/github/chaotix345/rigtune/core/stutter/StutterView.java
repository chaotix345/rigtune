package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.List;

// What StutterScreen shows (docs/v0.4/SPEC.md 5, C4). monitorOn: the session monitor setting; recording: a session
// capture is running (a world is loaded); paused: that capture is paused; analysing: an analysis is running and there is
// no report yet. report: the running session's latest analysis (live) or, without one, the last saved summary (null =
// nothing yet). advice: the stutterAdvice entries that fired for it.
public record StutterView(boolean monitorOn, boolean recording, boolean paused, boolean analysing, boolean live, @Nullable StutterReport report,
		List<StutterAdvisor.Fired> advice) {
	public static final StutterView EMPTY = new StutterView(false, false, false, false, false, null, List.of());

	public StutterView {
		advice = advice == null ? List.of() : List.copyOf(advice);
	}

	public boolean enoughData() {
		return report != null && report.enoughData();
	}
}
