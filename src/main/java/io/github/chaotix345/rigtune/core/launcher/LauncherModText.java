package io.github.chaotix345.rigtune.core.launcher;

import io.github.chaotix345.rigtune.core.model.Text;
import org.jspecify.annotations.Nullable;

// docs/v0.5/SPEC.md 4b: the one source of launcher wording for mod files, for RigTune's own screens and other features
// (C02's guide joins guideLine). The launcher names in en_us.json carry their article ("the Modrinth App"), so a name
// never starts a sentence here.
public final class LauncherModText {
	public static final String YOUR_LAUNCHER = "your launcher";

	private LauncherModText() {
	}

	// Who manages the mod files, as the screens name it: a detected launcher with a name, but not the official launcher (a
	// packwiz index there isn't its record) and not Unknown. Null: "your launcher".
	public static @Nullable Text launcherName(@Nullable LauncherInfo launcher) {
		if (launcher == null) {
			return null;
		}
		return switch (launcher.launcher()) {
			case PRISM -> Text.of("rigtune.launcher.name.prism", "Prism Launcher");
			case MULTIMC -> Text.of("rigtune.launcher.name.multimc", "MultiMC");
			case GDLAUNCHER -> Text.of("rigtune.launcher.name.gdlauncher", "GDLauncher");
			case MODRINTH_APP -> Text.of("rigtune.launcher.name.modrinth_app", "the Modrinth App");
			case ATLAUNCHER -> Text.of("rigtune.launcher.name.atlauncher", "ATLauncher");
			case CURSEFORGE -> Text.of("rigtune.launcher.name.curseforge", "the CurseForge app");
			case OFFICIAL, UNKNOWN -> null;
		};
	}

	// The name, or "your launcher".
	// The launcher a launcherName() names (an Undo reason's argument), null for anything else ("your launcher" too).
	public static @Nullable Launcher launcherNamed(@Nullable Object name) {
		for (Launcher launcher : Launcher.values()) {
			Text named = launcherName(LauncherInfo.of(launcher));
			if (named != null && named.equals(name)) {
				return launcher;
			}
		}
		return null;
	}

	public static Text nameOrYours(@Nullable LauncherInfo launcher) {
		Text name = launcherName(launcher);
		return name != null ? name : Text.of("rigtune.launcher.mod_files.your_launcher", YOUR_LAUNCHER);
	}

	// AC4b.5: LAUNCHER's sentence, the opted-in sentence (RIGTUNE because of the opt-in), and null for plain RIGTUNE and
	// for PENDING (nothing is claimed about mod files before detection answers). optedIn: settings.json modFilesByRigTune.
	public static @Nullable Text guideLine(ModFilesPolicy policy, @Nullable LauncherInfo launcher, boolean optedIn) {
		return switch (policy) {
			case LAUNCHER -> Text.of("rigtune.launcher.mod_files.guide", "This instance's mods are managed by %s: RigTune changes settings only.",
					nameOrYours(launcher));
			case RIGTUNE -> optedIn ? Text.of("rigtune.launcher.mod_files.opted_in", "RigTune changes mod files here; %s's own list may go out of date.",
					nameOrYours(launcher)) : null;
			case PENDING -> null;
		};
	}

	// Preview's line (4b): how many of the report's rows are mod changes to make in the launcher. Null when there are none
	// or RigTune changes mod files itself.
	public static @Nullable Text previewLine(ModFilesPolicy policy, @Nullable LauncherInfo launcher, int count) {
		if (count <= 0) {
			return null;
		}
		return switch (policy) {
			case LAUNCHER -> Text.of("rigtune.launcher.mod_files.preview", "Mod changes to make in %s: %s (listed on the main screen)",
					nameOrYours(launcher), count);
			case PENDING -> Text.of("rigtune.launcher.mod_files.preview.pending",
					"Mod changes waiting until RigTune knows which launcher manages this instance's mods: %s (listed on the main screen)", count);
			case RIGTUNE -> null;
		};
	}

	// The share report's "- Mod files: <this>" (4b, English only, like its "- Launcher:" line); null for plain RIGTUNE
	// (0.4's report). ShareReport escapes it like every other field.
	public static @Nullable String shareLine(ModFilesPolicy policy, @Nullable LauncherInfo launcher, boolean optedIn) {
		return switch (policy) {
			case LAUNCHER -> "changed in " + (launcherName(launcher) != null ? launcher.launcher().displayName() : "the launcher");
			case PENDING -> "waiting for the launcher check";
			case RIGTUNE -> optedIn ? "RigTune (opted in)" : null;
		};
	}
}
