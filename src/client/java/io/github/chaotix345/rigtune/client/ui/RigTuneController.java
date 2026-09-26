package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ProfileImport;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import io.github.chaotix345.rigtune.core.report.ShareReport;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
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

	// v0.3 (WS-B): docs/v0.3/SPEC.md item 6 and 3e.

	/** What "Undo this" on one history entry would do; carried out with {@link #undo(UndoPlan)}. Off the render thread. */
	default @Nullable UndoPlan undoPlanFor(String entryId) {
		return null;
	}

	/** The History screen's model, or null when there is none. Off the render thread. */
	default HistoryModel.@Nullable View history() {
		return null;
	}

	// v0.4 (WS-A, docs/v0.4/SPEC.md 2b; coordinator-approved addition to C4)

	/** How settings keys and values are named, as History shows them (the Preview's rows; WS-P's profile import preview). Render thread. */
	default HistoryModel.Labels settingLabels() {
		return HistoryModel.Labels.RAW;
	}

	// v0.3 (WS-F)

	/** The versions for the "Report a problem" issue title (v0.3 item 10); null while the report is being built. */
	default ShareReport.@Nullable Versions reportVersions() {
		return null;
	}

	// v0.3 (WS-P)

	/** What Apply would do for these recommendations, writing and downloading nothing (docs/v0.3/SPEC.md item 13). Off the render thread: it may look files up on Modrinth. */
	default ApplyPreview preview(List<Recommendation> selected) {
		return ApplyPreview.EMPTY;
	}

	// v0.4 contracts (docs/v0.4/SPEC.md C4). Each feature implements its methods in its own service; RealController
	// delegates in one line each. Screens call these on the render thread unless noted.

	// Notice slot (C3; sources filled by items 4, 7, 8, 9).

	/** What the notice line may show, best first (dismissed ones left out). Asked on screen init, never per frame. */
	default List<Notice> notices() {
		return List.of();
	}

	/** One of a notice's actions was pressed. */
	default void noticeAction(String key, String actionId) {
	}

	/** The notice was dismissed; it stays hidden (awareness.json). */
	default void dismissNotice(String key) {
	}

	// Profiles (item 4, WS-P).

	default List<ProfileView> profiles() {
		return List.of();
	}

	/** Switches to a template ("template:<id>") or saved profile: an ordinary Apply, undoable in History. */
	default Component switchProfile(String id) {
		return Component.translatable("rigtune.status.nothing");
	}

	/** What switching would do, writing nothing. Off the render thread. */
	default ApplyPreview previewProfile(String id) {
		return ApplyPreview.EMPTY;
	}

	default Component saveCurrentProfile(String name) {
		return Component.translatable("rigtune.status.nothing");
	}

	/** Decodes a share code (never applies it; Preview comes first). */
	default ProfileImport importProfileCode(String code) {
		return new ProfileImport(null, ApplyPreview.EMPTY, 0, null);
	}

	default @Nullable String exportProfileCode(String id) {
		return null;
	}

	default void renameProfile(String id, String name) {
	}

	default void deleteProfile(String id) {
	}

	// Stutter Doctor (item 5).

	default StutterView stutter() {
		return StutterView.EMPTY;
	}

	/** Turns the opt-in session monitor on or off (settings.json stutterMonitor). */
	default void setStutterMonitor(boolean on) {
	}

	default void pauseStutterMonitor(boolean paused) {
	}

	default void clearStutter() {
	}

	/** The session summary for Copy summary (clipboard only). */
	default String stutterSummary() {
		return "";
	}

	// JVM & memory (item 6).

	default JvmReport jvmReport() {
		return JvmReport.UNAVAILABLE;
	}

	// Benchmark history (item 7). contextKey null: the latest run's context.

	default BenchmarkTrend.View benchmarkTrend(@Nullable String contextKey) {
		return BenchmarkTrend.View.EMPTY;
	}

	// Server-aware advice (item 8).

	/** The connected server's live limits; null when not connected. */
	default @Nullable ServerLimits serverLimits() {
		return null;
	}

	// Startup-time report (item 13).

	default StartupTimes.View startupTimes() {
		return StartupTimes.View.EMPTY;
	}
}
