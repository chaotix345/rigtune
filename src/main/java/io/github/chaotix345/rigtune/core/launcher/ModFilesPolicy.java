package io.github.chaotix345.rigtune.core.launcher;

import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 4a: who changes this instance's mod files. RIGTUNE: RigTune, as in 0.4. LAUNCHER: the launcher keeps
// its own record of the mods, so mod-file changes become advice with its steps. PENDING: launcher detection hasn't
// answered yet, treated as LAUNCHER. Contracts stub (WS-K): of() answers RIGTUNE, 0.4's behaviour, until WS-L1 fills in
// the table.
public enum ModFilesPolicy {
	RIGTUNE,
	LAUNCHER,
	PENDING;

	// launcher null: detection hasn't answered. optIn: settings.json modFilesByRigTune.
	public static ModFilesPolicy of(@Nullable LauncherInfo launcher, InstanceEvidence evidence, boolean optIn) {
		return RIGTUNE;
	}
}
