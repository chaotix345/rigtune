package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.client.awareness.OutsideChanges;
import io.github.chaotix345.rigtune.client.undo.RefusedDisables;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.report.LauncherModAdvice;
import io.github.chaotix345.rigtune.core.rules.RulesDocument;
import io.github.chaotix345.rigtune.core.stutter.FixHold;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

// docs/v0.5/PLAN.md contracts item 13a and 13c: the extension points in RealController's shared methods, so each feature
// fills its own stub instead of editing rebuild() or apply(). Each stub lives in its owner's file and is the identity or a
// no-op until filled. This class is frozen after the contracts commit: a new entry goes through the coordinator.
public final class V05Hooks {
	private V05Hooks() {
	}

	// What a report post-step may read (built on the rebuild's worker): the controller, the rules the report was built
	// from, and the connected server's live limits (null when not connected).
	public record StepContext(RealController controller, RulesDocument rules, @Nullable ServerLimits live) {
	}

	// What an after-apply hook may read. selected: what Apply was given (after beforeApply); disablesAllowed: the ids of
	// the DisableMod items DisableGuard let through; the counts are apply()'s own (settings applied now and failed, ops
	// staged for the restart and whether staging failed, downloads started).
	public record ApplyFacts(String entryId, List<Recommendation> selected, Set<String> disablesAllowed, int settingsOk, int settingsFailed,
			int staged, boolean stageFailed, int downloads) {
		public ApplyFacts {
			selected = List.copyOf(selected);
			disablesAllowed = Set.copyOf(disablesAllowed);
		}
	}

	// The report post-step list, on the rebuild's worker after ServerCap (ModrinthOffAdvice stays last, on the render
	// thread): C20's FixHold (WS-S2), then P0.4's LauncherModAdvice (WS-L1).
	public static Report afterRecommend(Report report, StepContext context) {
		RealController controller = context.controller();
		Report held = FixHold.apply(report, controller.v05().stutterFixes().holds());
		return LauncherModAdvice.apply(held, controller.modFiles(), controller.launcher());
	}

	// At the top of RealController.apply(selected, entryId): P0.4's guard (WS-L1) drops what the policy forbids.
	public static List<Recommendation> beforeApply(RealController controller, List<Recommendation> selected) {
		return LauncherModAdvice.guard(selected, controller.modFiles());
	}

	// Before RealController.apply returns its status, in order: 2V's refused-disable count (WS-H), 4h's Modrinth App sync
	// line (WS-W), then C02's FirstRunService.applied (WS-F), last. Each may add to parts, the status's pieces.
	public static void afterApply(RealController controller, ApplyFacts facts, List<Component> parts) {
		RefusedDisables.afterApply(facts, parts);
		OutsideChanges.afterApply(controller, facts, parts);
		controller.v05().firstRun().applied(facts);
	}
}
