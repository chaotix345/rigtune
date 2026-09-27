package io.github.chaotix345.rigtune.core.report;

import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;

import java.util.List;

// docs/v0.5/SPEC.md 4b (P0.4): under LAUNCHER or PENDING every mod-file recommendation becomes the launcher's own steps
// (apply: the report post-step, V05Hooks.afterRecommend, before ModrinthOffAdvice), and RealController.apply refuses what
// the policy forbids (guard: V05Hooks.beforeApply). Contracts stubs (WS-K): identity, until WS-L1 fills them in.
public final class LauncherModAdvice {
	private LauncherModAdvice() {
	}

	// launcher: LauncherInfo.UNKNOWN until detection answers (the policy is PENDING then).
	public static Report apply(Report report, ModFilesPolicy policy, LauncherInfo launcher) {
		return report;
	}

	public static List<Recommendation> guard(List<Recommendation> selected, ModFilesPolicy policy) {
		return selected;
	}
}
