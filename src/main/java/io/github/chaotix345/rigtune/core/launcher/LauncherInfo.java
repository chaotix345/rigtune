package io.github.chaotix345.rigtune.core.launcher;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

// What RigTune knows about the launcher: which one, and for CurseForge whether this pack overrides the app's memory
// setting (null when that isn't known). Nothing else: no instance name, path or raw property value.
public record LauncherInfo(Launcher launcher, @Nullable Boolean memoryOverride) {
	public static final LauncherInfo UNKNOWN = new LauncherInfo(Launcher.UNKNOWN, null);
	// v0.5 (docs/v0.5/SPEC.md 4b-4c): the mod-file changes a launcher's steps are given for (modStepsKey).
	public static final List<String> MOD_KINDS = List.of("add", "update", "disable", "enable", "self_update");

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

	// v0.4 (docs/v0.4/SPEC.md 6, docs/research/v0.4/launcher-steps.md): where this launcher keeps the Java arguments. The
	// official launcher's labels come from Mojang's help articles; CurseForge's from its support pages (both closed source).
	public @Nullable String jvmStepsKey() {
		return switch (launcher) {
			case PRISM -> "rigtune.launcher.jvm_steps.prism";
			// docs/v0.4/SPEC.md 2g: MultiMC 0.6.16's "Java arguments" box; GDLauncher Carbon's instance and global boxes.
			case MULTIMC -> "rigtune.launcher.jvm_steps.multimc";
			case GDLAUNCHER -> "rigtune.launcher.jvm_steps.gdlauncher";
			case MODRINTH_APP -> "rigtune.launcher.jvm_steps.modrinth_app";
			case ATLAUNCHER -> "rigtune.launcher.jvm_steps.atlauncher";
			case CURSEFORGE -> "rigtune.launcher.jvm_steps.curseforge";
			case OFFICIAL -> "rigtune.launcher.jvm_steps.official";
			case UNKNOWN -> null;
		};
	}

	// v0.5 (docs/v0.5/SPEC.md 4b-4c): where this launcher adds, updates, turns off or back on a mod (kind: one of
	// MOD_KINDS), with the labels from its own source or locale files (docs/v0.5/design/ws-l1.md; CurseForge's from its
	// support pages, UNVERIFIED). RigTune's own update has its own steps only in the Modrinth App; elsewhere it's an update.
	// Null for the official launcher and Unknown, which keep no record of the mods (LauncherModText names neither).
	public @Nullable String modStepsKey(String kind) {
		String name = switch (launcher) {
			case PRISM -> "prism";
			case MULTIMC -> "multimc";
			case GDLAUNCHER -> "gdlauncher";
			case MODRINTH_APP -> "modrinth_app";
			case ATLAUNCHER -> "atlauncher";
			case CURSEFORGE -> "curseforge";
			case OFFICIAL, UNKNOWN -> null;
		};
		if (name == null || !MOD_KINDS.contains(kind)) {
			return null;
		}
		String steps = "self_update".equals(kind) && launcher != Launcher.MODRINTH_APP ? "update" : kind;
		return "rigtune.launcher.mod_steps." + name + "." + steps;
	}

	// A -Xmx typed in the Java arguments overrides the memory slider (Modrinth App: args.rs:162,205; GDLauncher Carbon:
	// minecraft.rs:593-594 then :665, launcher-steps.md). Prism, MultiMC and ATLauncher put their own -Xmx last (or refuse
	// a typed one); the official launcher has no separate memory setting; CurseForge is UNVERIFIED.
	public boolean typedXmxWins() {
		return launcher == Launcher.MODRINTH_APP || launcher == Launcher.GDLAUNCHER;
	}
}
