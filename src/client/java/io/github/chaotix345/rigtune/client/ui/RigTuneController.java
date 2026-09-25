package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.report.ShareReport;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.List;

public interface RigTuneController {
	/** Null while the report is still being built. */
	@Nullable Report report();

	Goal goal();

	void setGoal(Goal goal);

	Component apply(List<Recommendation> selected);

	void startBenchmark();

	void rescan();

	/** Latest message from background work (downloads, benchmark), polled by the screen. */
	default @Nullable Component status() {
		return null;
	}

	/** True while staged mod or Sodium changes wait for a restart (config/rigtune/pending.json exists). */
	default boolean hasPendingChanges() {
		return false;
	}

	/** Drops every staged change; returns a status message. */
	default Component discardPending() {
		return Component.translatable("rigtune.status.nothing");
	}

	// v0.2 contracts (docs/v0.2/SPEC.md, docs/v0.2/PLAN.md). Each workstream implements its methods in RealController.

	/** Starts a benchmark of the given mode and scene (item 6). */
	default void startBenchmark(BenchmarkRequest request) {
		startBenchmark();
	}

	/** The latest benchmark run, or null (item 6; used by the share report). */
	default @Nullable BenchmarkSummary latestBenchmark() {
		return null;
	}

	/** What undoing the last apply (all = false) or everything (all = true) would do (item 3). */
	default @Nullable UndoPlan undoPlan(boolean all) {
		return null;
	}

	/** Carries out exactly this plan (from undoPlan), re-checking each item; returns a status message (item 3). */
	default Component undo(UndoPlan plan) {
		return Component.translatable("rigtune.status.nothing");
	}

	/** Settings changed (item 8): reload the rules if needed and rescan. WS-A implements the rules reload. */
	default void settingsChanged() {
		rescan();
	}

	/** The report as Markdown for the clipboard (item 10). */
	default String shareReport() {
		return "";
	}

	// v0.3 (WS-C)

	/** The launcher that started the game (docs/v0.3/SPEC.md item 5); UNKNOWN when it isn't recognised. */
	default LauncherInfo launcher() {
		return LauncherInfo.UNKNOWN;
	}

	// v0.3 (WS-F)

	/** The versions for the "Report a problem" issue title (v0.3 item 10); null while the report is being built. */
	default ShareReport.@Nullable Versions reportVersions() {
		return null;
	}
}
