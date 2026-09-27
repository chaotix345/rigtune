package io.github.chaotix345.rigtune.core.stutter;

import io.github.chaotix345.rigtune.core.model.Report;

import java.util.List;

// docs/v0.5/SPEC.md 5 (C20), the main list's post-step (V05Hooks.afterRecommend, after ServerCap, never inside Recommender):
// a SetSetting that would move an actively fixed key away from its fix is unticked, with the hold's reason. Contracts stub
// (WS-K): the report unchanged, until WS-S2 fills it in.
public final class FixHold {
	// The fix moved key from `from` to `to`, applied on appliedOn (yyyy-MM-dd).
	public record Hold(String key, String from, String to, String appliedOn) {
	}

	private FixHold() {
	}

	public static Report apply(Report report, List<Hold> holds) {
		return report;
	}
}
