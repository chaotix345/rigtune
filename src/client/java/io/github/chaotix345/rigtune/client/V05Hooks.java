package io.github.chaotix345.rigtune.client;

import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.client.awareness.OutsideChanges;
import io.github.chaotix345.rigtune.client.undo.RefusedDisables;
import io.github.chaotix345.rigtune.core.model.Action;
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
import java.util.function.UnaryOperator;

// docs/v0.5/PLAN.md contracts item 13a and 13c: the extension points in RealController's shared methods, so each feature
// fills its own stub instead of editing rebuild() or apply(). Each stub lives in its owner's file and is the identity or a
// no-op until filled. A step that throws is logged and can't break what 0.4 did: a post-step leaves the report it was
// given, an after-apply step leaves the status as it is (the next steps still run), and the apply guard fails closed.
// This class is frozen after the contracts commit: a new entry goes through the coordinator.
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
		Report held = postStep("FixHold.apply", report, r -> FixHold.apply(r, controller.v05().stutterFixes().holds()));
		return postStep("LauncherModAdvice.apply", held, r -> LauncherModAdvice.apply(r, controller.modFiles(), controller.launcher()));
	}

	// At the top of RealController.apply(selected, entryId): P0.4's guard (WS-L1) drops what the policy forbids.
	public static List<Recommendation> beforeApply(RealController controller, List<Recommendation> selected) {
		return guarded(selected, s -> LauncherModAdvice.guard(s, controller.modFiles()));
	}

	// Before RealController.apply returns its status, in order: 2V's refused-disable count (WS-H), 4h's Modrinth App sync
	// line (WS-W), then C02's FirstRunService.applied (WS-F), last. Each may add to parts, the status's pieces.
	public static void afterApply(RealController controller, ApplyFacts facts, List<Component> parts) {
		applyStep("RefusedDisables.afterApply", () -> RefusedDisables.afterApply(facts, parts));
		applyStep("OutsideChanges.afterApply", () -> OutsideChanges.afterApply(controller, facts, parts));
		applyStep("FirstRunService.applied", () -> controller.v05().firstRun().applied(facts));
	}

	// A post-step that throws (or answers null) leaves the report it was given, so the rebuild still finishes.
	static Report postStep(String what, Report report, UnaryOperator<Report> step) {
		try {
			Report out = step.apply(report);
			return out != null ? out : report;
		} catch (Throwable t) {
			RigTune.LOGGER.error("RigTune: the report step {} failed; the report goes on without it", what, t);
			return report;
		}
	}

	// The guard fails closed: if it throws (or answers null), only the settings changes go through, never a mod file.
	static List<Recommendation> guarded(List<Recommendation> selected, UnaryOperator<List<Recommendation>> guard) {
		try {
			List<Recommendation> out = guard.apply(selected);
			if (out != null) {
				return out;
			}
			RigTune.LOGGER.error("RigTune: the apply guard answered nothing; applying settings changes only");
		} catch (Throwable t) {
			RigTune.LOGGER.error("RigTune: the apply guard failed; applying settings changes only", t);
		}
		return selected.stream().filter(r -> r.action() instanceof Action.SetSetting).toList();
	}

	// An after-apply step that throws is logged; the status and the steps after it are unaffected.
	static void applyStep(String what, Runnable step) {
		try {
			step.run();
		} catch (Throwable t) {
			RigTune.LOGGER.error("RigTune: the after-apply step {} failed", what, t);
		}
	}
}
