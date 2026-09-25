package io.github.chaotix345.rigtune.core.launcher;

// The launchers RigTune can name (docs/v0.3/SPEC.md item 5). The display name is English, for the share report; the
// UI uses LauncherInfo's translation keys.
public enum Launcher {
	PRISM("Prism Launcher"),
	MODRINTH_APP("Modrinth App"),
	ATLAUNCHER("ATLauncher"),
	CURSEFORGE("CurseForge"),
	UNKNOWN("");

	private final String displayName;

	Launcher(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}
}
