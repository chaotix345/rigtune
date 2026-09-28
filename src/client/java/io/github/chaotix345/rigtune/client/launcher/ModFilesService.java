package io.github.chaotix345.rigtune.client.launcher;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.SettingsSaver;
import io.github.chaotix345.rigtune.client.probe.LauncherProbe;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.launcher.InstanceEvidence;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

// docs/v0.5/SPEC.md 4a-4b, 4e (P0.4): the instance's mod-files policy and the MOD_FILES_NEWS notice. Reached only through
// RealController.v05() (X4); nothing happens in the constructor. The policy is read live from the launcher RealController
// recorded (LauncherProbe.recorded(), the value its launcher() shows), the .index/ listing's answer (both in memory once in)
// and settings.json's opt-in: no I/O, any thread.
public final class ModFilesService {
	// MOD_FILES_NEWS (4b): its key (dismissed in awareness.json like any notice, so once per instance) and its action.
	public static final String NEWS_KEY = "launcher.mod_files_news";
	public static final String NEWS_SETTINGS = "settings";
	private final @Nullable RealController controller;
	private final Supplier<@Nullable LauncherInfo> launcher;
	private final Supplier<@Nullable InstanceEvidence> evidence;
	private final BooleanSupplier optIn;

	public ModFilesService(RealController controller) {
		this(controller, LauncherProbe::recorded, LauncherProbe::evidence, () -> controller.settings().modFilesByRigTune);
	}

	// The policy's inputs: the recorded launcher and the listing's answer (null until in), and the opt-in.
	ModFilesService(@Nullable RealController controller, Supplier<@Nullable LauncherInfo> launcher, Supplier<@Nullable InstanceEvidence> evidence,
			BooleanSupplier optIn) {
		this.controller = controller;
		this.launcher = launcher;
		this.evidence = evidence;
		this.optIn = optIn;
	}

	// Render thread, no I/O (screens and RealController.modFiles()); also the rebuild's worker (the report post-step).
	public ModFilesPolicy policy() {
		return ModFilesPolicy.of(launcher.get(), evidence.get(), optIn.getAsBoolean());
	}

	// What the instance would be without the opt-in: whether a launcher keeps its own record (the Settings row, the
	// opted-in header line).
	public ModFilesPolicy withoutOptIn() {
		return ModFilesPolicy.of(launcher.get(), evidence.get(), false);
	}

	// The opt-in is on where a launcher is known to keep its own record of the mods (the instance would otherwise be
	// LAUNCHER): the opted-in header line, the share line, C02's guide (LauncherModText.guideLine's optedIn).
	public boolean optedIn() {
		return optIn.getAsBoolean() && withoutOptIn() == ModFilesPolicy.LAUNCHER;
	}

	// Turns the per-instance opt-in on or off (also WS-L2's "Let RigTune apply them"): settings.json through SettingsSaver
	// (X8), then one rebuild, which reads the new policy.
	public void setOptIn(boolean on) {
		RealController real = controller;
		if (real == null) {
			return;
		}
		real.settings().modFilesByRigTune = on;
		SettingsSaver.shared().save(real.settings(), real.configDir());
		real.rebuild();
	}

	// MOD_FILES_NEWS (4b): what changed for a player who used RigTune before, under LAUNCHER only (never PENDING: nothing is
	// claimed before detection answers); a new player learns it from C02's guide instead. Null otherwise. status: what
	// FirstRunService.load() read at startup (loadedStatus(), review-11 FEAT-1), never the live status an Apply turns
	// RETURNING. The notice line asks on screen init/rebuild; this reads memory only.
	public @Nullable Notice news(FirstRun.Status status) {
		if (status != FirstRun.Status.RETURNING || policy() != ModFilesPolicy.LAUNCHER) {
			return null;
		}
		return newsNotice(launcher.get());
	}

	// The notice itself (also the A11y walk's canned one). Review L9: where no launcher is named (a packwiz index alone), the
	// detail promises no launcher steps, which only a named launcher gets.
	public static Notice newsNotice(@Nullable LauncherInfo launcher) {
		Text detail = LauncherModText.launcherName(launcher) != null
				? Text.of("rigtune.launcher.mod_files.news.detail",
						"Installing, updating and turning off mods now come with the launcher's own steps. Settings → Mod files lets RigTune change them anyway.")
				: Text.of("rigtune.launcher.mod_files.news.detail.unnamed",
						"Installing, updating and turning off mods are now left to your launcher. Settings → Mod files lets RigTune change them anyway.");
		return new Notice(NEWS_KEY, NoticePriority.MOD_FILES_NEWS,
				Text.of("rigtune.launcher.mod_files.news", "RigTune now leaves this instance's mod files to %s", LauncherModText.nameOrYours(launcher)),
				detail,
				List.of(new NoticeAction(NEWS_SETTINGS, Text.of("rigtune.launcher.mod_files.news.settings", "Settings…"))), true);
	}

	// The share report's "- Mod files:" line, or null (LauncherModText.shareLine).
	public @Nullable String shareLine() {
		return LauncherModText.shareLine(policy(), launcher.get(), optedIn());
	}
}
