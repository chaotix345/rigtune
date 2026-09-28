package io.github.chaotix345.rigtune.core.launcher;

import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 4a: who changes this instance's mod files. RIGTUNE: RigTune, as in 0.4. LAUNCHER: the launcher keeps
// its own record of the mods, so mod-file changes become advice with its steps. PENDING: launcher detection hasn't
// answered yet, treated as LAUNCHER (a late answer rebuilds the report once).
public enum ModFilesPolicy {
	RIGTUNE,
	LAUNCHER,
	PENDING;

	// The table of SPEC 4a, in its order (lm §3.7, SPEC-14's evidence first): the opt-in; a packwiz index (whatever
	// launcher is detected, and whether or not detection has answered); a detection that hasn't answered; the launchers
	// that keep a per-mod record; else RigTune, as 0.4. launcher null: detection hasn't answered. evidence null: the
	// .index/ listing hasn't answered, so a launcher that would be RIGTUNE stays PENDING until it does. optIn: settings.json
	// modFilesByRigTune.
	public static ModFilesPolicy of(@Nullable LauncherInfo launcher, @Nullable InstanceEvidence evidence, boolean optIn) {
		if (optIn) {
			return RIGTUNE;
		}
		if (evidence != null && evidence.packwizIndex()) {
			return LAUNCHER;
		}
		if (launcher == null) {
			return PENDING;
		}
		if (keepsRecord(launcher.launcher())) {
			return LAUNCHER;
		}
		return evidence == null ? PENDING : RIGTUNE;
	}

	// The Modrinth App (brand theseus), the CurseForge app (minecraftinstance.json), ATLauncher and GDLauncher each keep
	// their own record of the mod files (lm §1, §3.3-3.5; GDLauncher fully, the coordinator's decision on lm §8).
	static boolean keepsRecord(Launcher launcher) {
		return switch (launcher) {
			case MODRINTH_APP, CURSEFORGE, ATLAUNCHER, GDLAUNCHER -> true;
			case PRISM, MULTIMC, OFFICIAL, UNKNOWN -> false;
		};
	}

	// Mod-file changes are advice (LAUNCHER, and PENDING treated as LAUNCHER).
	public boolean launcherManages() {
		return this != RIGTUNE;
	}
}
