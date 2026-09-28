package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.ui.HowItWorksScreen;
import io.github.chaotix345.rigtune.core.history.FirstRun;
import io.github.chaotix345.rigtune.core.launcher.LauncherModText;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticeAction;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

// NoticePriority.FIRST_RUN, docs/v0.5/SPEC.md 8 (C02): the first-run guide, before a new player's first Apply. Shown only
// while FirstRunService says NEW and the report has something Apply would change; it goes away with the first Apply by
// any path (the status turns RETURNING) or with Got it, an ordinary dismissal stored in awareness.json. Its detail follows
// who changes this instance's mod files (P0.4's modFiles()) and joins P0.4's own sentence about the launcher, never while
// that's still being checked (PENDING). Constructed by the lazy notice list on the first notices() call, never during
// startup (X4); current() reads in-memory state, and awareness.json's dismissals only for a new player.
public final class FirstRunNoticeSource implements NoticeSource {
	public static final String KEY = "firstrun.guide";
	public static final String HOW = "how";
	public static final String GOT_IT = "got_it";

	private final RealController controller;

	public FirstRunNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		ModFilesPolicy policy = controller.modFiles();
		return current(controller.v05().firstRun().status(), controller.report(), () -> controller.awarenessService().dismissed(), policy,
				LauncherModText.guideLine(policy, controller.launcher(), controller.settings().modFilesByRigTune));
	}

	// The notice line can't hide a notice that isn't dismissible (NoticeBoard), so Got it's stored dismissal is checked
	// here; awareness.json is read only when everything else says the guide would show (a new player, screen init).
	static @Nullable Notice current(FirstRun.Status status, @Nullable Report report, Supplier<Set<String>> dismissed, ModFilesPolicy policy,
			@Nullable Text guideLine) {
		return shows(status, report) && !dismissed.get().contains(KEY) ? notice(policy, guideLine) : null;
	}

	@Override
	public void act(String actionId) {
		if (GOT_IT.equals(actionId)) {
			controller.dismissNotice(KEY);
			return;
		}
		Minecraft minecraft = controller.minecraft();
		if (HOW.equals(actionId) && minecraft != null) {
			minecraft.gui.setScreen(new HowItWorksScreen(minecraft.gui.screen(), controller, controller.settings().modFilesByRigTune));
		}
	}

	// A new player (once the load has answered) whose report has at least one item Apply would change.
	static boolean shows(FirstRun.Status status, @Nullable Report report) {
		return status == FirstRun.Status.NEW && report != null && report.recommendations().stream().anyMatch(Recommendation::appliable);
	}

	// guideLine: P0.4's sentence for this policy (LauncherModText.guideLine), left out under PENDING.
	public static Notice notice(ModFilesPolicy policy, @Nullable Text guideLine) {
		Text base = policy == ModFilesPolicy.RIGTUNE
				? Text.of("rigtune.firstrun.notice.detail", "Only the ticked items change, with any mods they need. Game settings change when you "
						+ "press Apply; Sodium, Distant Horizons and Iris settings and mod changes take effect at the next restart. Preview shows each "
						+ "change first.")
				: Text.of("rigtune.firstrun.notice.detail.settings", "Only the ticked settings change. Game settings change when you press Apply; "
						+ "Sodium, Distant Horizons and Iris settings at the next restart. Preview shows each change first.");
		Text detail = policy == ModFilesPolicy.PENDING || guideLine == null ? base : Text.join(" ", base, guideLine);
		return new Notice(KEY, NoticePriority.FIRST_RUN, Text.of("rigtune.firstrun.notice", "New? History… lets you undo each Apply."), detail,
				List.of(new NoticeAction(HOW, Text.of("rigtune.firstrun.action.how", "How it works")),
						new NoticeAction(GOT_IT, Text.of("rigtune.firstrun.action.got_it", "Got it"))), false);
	}
}
