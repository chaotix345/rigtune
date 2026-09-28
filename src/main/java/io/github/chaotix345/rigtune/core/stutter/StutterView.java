package io.github.chaotix345.rigtune.core.stutter;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// What StutterScreen shows (docs/v0.4/SPEC.md 5, C4). monitorOn: the session monitor setting; recording: a session
// capture is running (a world is loaded); paused: that capture is paused; analysing: an analysis is running and there is
// no report yet. report: the running session's latest analysis (live) or, without one, the last saved summary (null =
// nothing yet). advice: the stutterAdvice entries that fired for it.
// v0.5 (docs/v0.5/SPEC.md 5, C20): fixes, per fired advice id the one-click fix offered under it (or why not yet), only
// for an analysis made in this game run (a saved summary has no facts); tracked, the stutter fix the "Your stutter fix"
// block shows (the newest one not dismissed), or null.
public record StutterView(boolean monitorOn, boolean recording, boolean paused, boolean analysing, boolean live, @Nullable StutterReport report,
		List<StutterAdvisor.Fired> advice, Map<String, FixOffer> fixes, FixTracker.@Nullable Record tracked) {
	public static final StutterView EMPTY = new StutterView(false, false, false, false, false, null, List.of());

	public StutterView {
		advice = advice == null ? List.of() : List.copyOf(advice);
		fixes = fixes == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fixes));
	}

	public StutterView(boolean monitorOn, boolean recording, boolean paused, boolean analysing, boolean live, @Nullable StutterReport report,
			List<StutterAdvisor.Fired> advice) {
		this(monitorOn, recording, paused, analysing, live, report, advice, Map.of(), null);
	}

	public boolean enoughData() {
		return report != null && report.enoughData();
	}
}
