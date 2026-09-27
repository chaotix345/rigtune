package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import org.jspecify.annotations.Nullable;

// docs/v0.5/PLAN.md contracts item 13i: canned v0.5 views for A11yGameTest's walks. A11yController answers tryIt(),
// serverProfiles(), modFiles(), stutter() and firstApplyPending() from here when one is set (null: not canned, the
// controller's own answer). An owner sets its views inside its own skeleton method and calls clear() before it returns
// (in a finally), so the next walk starts clean.
final class CannedViews {
	private static volatile @Nullable TryItView tryIt;
	private static volatile @Nullable ServerProfilesView serverProfiles;
	private static volatile @Nullable ModFilesPolicy modFiles;
	private static volatile @Nullable StutterView stutter;
	private static volatile @Nullable Boolean firstApplyPending;

	private CannedViews() {
	}

	static @Nullable TryItView tryIt() {
		return tryIt;
	}

	static void tryIt(@Nullable TryItView view) {
		tryIt = view;
	}

	static @Nullable ServerProfilesView serverProfiles() {
		return serverProfiles;
	}

	static void serverProfiles(@Nullable ServerProfilesView view) {
		serverProfiles = view;
	}

	static @Nullable ModFilesPolicy modFiles() {
		return modFiles;
	}

	static void modFiles(@Nullable ModFilesPolicy policy) {
		modFiles = policy;
	}

	static @Nullable StutterView stutter() {
		return stutter;
	}

	static void stutter(@Nullable StutterView view) {
		stutter = view;
	}

	static @Nullable Boolean firstApplyPending() {
		return firstApplyPending;
	}

	static void firstApplyPending(@Nullable Boolean pending) {
		firstApplyPending = pending;
	}

	static void clear() {
		tryIt = null;
		serverProfiles = null;
		modFiles = null;
		stutter = null;
		firstApplyPending = null;
	}
}
