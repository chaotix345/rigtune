package io.github.chaotix345.rigtune.core.launcher;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

// What RigTune knows about the launcher: which one, and for CurseForge whether this pack overrides the app's memory
// setting (null when that isn't known). Nothing else: no instance name, path or raw property value.
public record LauncherInfo(Launcher launcher, @Nullable Boolean memoryOverride) {
	public static final LauncherInfo UNKNOWN = new LauncherInfo(Launcher.UNKNOWN, null);

	public LauncherInfo {
		Objects.requireNonNull(launcher, "launcher");
	}

	public static LauncherInfo of(Launcher launcher) {
		return launcher == Launcher.UNKNOWN ? UNKNOWN : new LauncherInfo(launcher, null);
	}

	public boolean known() {
		return launcher != Launcher.UNKNOWN;
	}

	public @Nullable String nameKey() {
		return switch (launcher) {
			case PRISM -> "rigtune.launcher.name.prism";
			case MULTIMC -> "rigtune.launcher.name.multimc";
			case GDLAUNCHER -> "rigtune.launcher.name.gdlauncher";
			case MODRINTH_APP -> "rigtune.launcher.name.modrinth_app";
			case ATLAUNCHER -> "rigtune.launcher.name.atlauncher";
			case CURSEFORGE -> "rigtune.launcher.name.curseforge";
			case OFFICIAL -> "rigtune.launcher.name.official";
			case UNKNOWN -> null;
		};
	}

	// C-M2: the Modrinth App, Prism and ATLauncher get the instance's own memory steps (they work whether or not the
	// instance already overrides the global value). CurseForge gets its global Java settings only when this pack is known
	// not to override them; otherwise the pack's own steps, which work either way. The official launcher has no global
	// setting: each installation has its own JVM arguments.
	public @Nullable String stepsKey() {
		return switch (launcher) {
			case PRISM -> "rigtune.launcher.steps.prism";
			// docs/v0.4/SPEC.md 2g: the instance's own memory setting (launcher-steps.md, from each launcher's source).
			case MULTIMC -> "rigtune.launcher.steps.multimc";
			case GDLAUNCHER -> "rigtune.launcher.steps.gdlauncher";
			case MODRINTH_APP -> "rigtune.launcher.steps.modrinth_app";
			case ATLAUNCHER -> "rigtune.launcher.steps.atlauncher";
			case CURSEFORGE -> Boolean.FALSE.equals(memoryOverride) ? "rigtune.launcher.steps.curseforge.global" : "rigtune.launcher.steps.curseforge.pack";
			case OFFICIAL -> "rigtune.launcher.steps.official";
			case UNKNOWN -> null;
		};
	}
}
