package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.core.history.FirstRun;

// docs/v0.5/SPEC.md 8 (C02): whether the player is new (in memory only; nothing persisted). Reached only through
// RealController.v05() (X4); nothing happens in the constructor. Contracts skeleton (WS-K): UNKNOWN, so neither the guide
// nor the confirmation shows, until WS-F fills it in (load() once on Probes.EXECUTOR from the start hook, applied() from
// the after-apply hook, last in its list).
public final class FirstRunService {
	private final RealController controller;

	public FirstRunService(RealController controller) {
		this.controller = controller;
	}

	public FirstRun.Status status() {
		return FirstRun.Status.UNKNOWN;
	}

	// RigTuneController.firstApplyPending(): the Apply button opens the confirmation afterwards.
	public boolean firstApplyPending() {
		return false;
	}

	// The start hook (V05Services.afterStart), on Probes.EXECUTOR.
	public void load() {
	}

	// The after-apply hook (V05Hooks.afterApply), last: every Apply, by any path, retires NEW.
	public void applied(V05Hooks.ApplyFacts facts) {
	}
}
