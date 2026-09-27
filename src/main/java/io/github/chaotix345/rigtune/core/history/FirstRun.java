package io.github.chaotix345.rigtune.core.history;

// docs/v0.5/SPEC.md 8 (C02): whether this player is new to RigTune. Contracts stub (WS-K): the status only; WS-F adds the
// pure check (isNew).
public final class FirstRun {
	// UNKNOWN until client/FirstRunService.load() has run (a load that finishes after an Apply never turns RETURNING back).
	public enum Status {
		UNKNOWN,
		NEW,
		RETURNING
	}

	private FirstRun() {
	}
}
