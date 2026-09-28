package io.github.chaotix345.rigtune.gametest;

import io.github.chaotix345.rigtune.client.footprint.StartupTimes;
import io.github.chaotix345.rigtune.client.ui.RigTuneController;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkRequest;
import io.github.chaotix345.rigtune.core.benchmark.BenchmarkTrend;
import io.github.chaotix345.rigtune.core.history.HistoryModel;
import io.github.chaotix345.rigtune.core.history.UndoPlan;
import io.github.chaotix345.rigtune.core.jvm.JvmReport;
import io.github.chaotix345.rigtune.core.launcher.LauncherInfo;
import io.github.chaotix345.rigtune.core.launcher.ModFilesPolicy;
import io.github.chaotix345.rigtune.core.model.BenchmarkSummary;
import io.github.chaotix345.rigtune.core.model.Goal;
import io.github.chaotix345.rigtune.core.model.Recommendation;
import io.github.chaotix345.rigtune.core.model.Report;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.preview.ApplyPreview;
import io.github.chaotix345.rigtune.core.profile.ProfileImport;
import io.github.chaotix345.rigtune.core.profile.ProfileView;
import io.github.chaotix345.rigtune.core.profile.ServerProfilesView;
import io.github.chaotix345.rigtune.core.report.ShareReport;
import io.github.chaotix345.rigtune.core.stutter.FixOffer;
import io.github.chaotix345.rigtune.core.stutter.StutterView;
import io.github.chaotix345.rigtune.core.tryit.TryItView;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;

// docs/v0.5/PLAN.md contracts item 13i: a game-test controller wrapper forwards every RigTuneController method, the
// v0.5 defaults included, to its delegate, and overrides only what it fakes, so a wrapper around RealController never
// silently answers an interface default. A new RigTuneController method must be added here too: the class check below
// fails every game test that uses a wrapper until it is (V05GameTestContractsTest checks the source in the unit tests).
// A wrapper that overrides one apply overload must override the other too: apply(selected, entryId) forwards to the
// delegate's own, so a wrapper faking only apply(selected) would still apply through its delegate (a profile switch, a
// stutter fix or Try it call the two-argument one). The constructor refuses such a wrapper.
abstract class ForwardingController implements RigTuneController {
	static {
		for (Method method : RigTuneController.class.getMethods()) {
			try {
				ForwardingController.class.getDeclaredMethod(method.getName(), method.getParameterTypes());
			} catch (NoSuchMethodException e) {
				throw new AssertionError("ForwardingController doesn't forward RigTuneController." + method.getName(), e);
			}
		}
	}

	private final RigTuneController delegate;

	ForwardingController(RigTuneController delegate) {
		this.delegate = delegate;
		boolean one = overrides(getClass(), List.class);
		boolean two = overrides(getClass(), List.class, String.class);
		if (one != two) {
			throw new AssertionError(getClass().getSimpleName() + " overrides apply(" + (one ? "selected" : "selected, entryId")
					+ ") but not apply(" + (one ? "selected, entryId" : "selected") + ")");
		}
	}

	// Whether a wrapper class (or a wrapper between it and this class) declares apply with these parameters.
	private static boolean overrides(Class<?> type, Class<?>... parameters) {
		for (Class<?> c = type; c != null && c != ForwardingController.class; c = c.getSuperclass()) {
			try {
				c.getDeclaredMethod("apply", parameters);
				return true;
			} catch (NoSuchMethodException e) {
				// not in this class
			}
		}
		return false;
	}

	RigTuneController delegate() {
		return delegate;
	}

	@Override
	public @Nullable Report report() {
		return delegate.report();
	}

	@Override
	public Goal goal() {
		return delegate.goal();
	}

	@Override
	public void setGoal(Goal goal) {
		delegate.setGoal(goal);
	}

	@Override
	public Component apply(List<Recommendation> selected) {
		return delegate.apply(selected);
	}

	@Override
	public void startBenchmark() {
		delegate.startBenchmark();
	}

	@Override
	public void rescan() {
		delegate.rescan();
	}

	@Override
	public @Nullable Component status() {
		return delegate.status();
	}

	@Override
	public boolean hasPendingChanges() {
		return delegate.hasPendingChanges();
	}

	@Override
	public Component discardPending() {
		return delegate.discardPending();
	}

	@Override
	public void startBenchmark(BenchmarkRequest request) {
		delegate.startBenchmark(request);
	}

	@Override
	public @Nullable BenchmarkSummary latestBenchmark() {
		return delegate.latestBenchmark();
	}

	@Override
	public @Nullable UndoPlan undoPlan(boolean all) {
		return delegate.undoPlan(all);
	}

	@Override
	public Component undo(UndoPlan plan) {
		return delegate.undo(plan);
	}

	@Override
	public void settingsChanged() {
		delegate.settingsChanged();
	}

	@Override
	public String shareReport() {
		return delegate.shareReport();
	}

	@Override
	public Component applyImportedProfile(ProfileImport imported) {
		return delegate.applyImportedProfile(imported);
	}

	@Override
	public Component saveImportedProfile(ProfileImport imported) {
		return delegate.saveImportedProfile(imported);
	}

	@Override
	public LauncherInfo launcher() {
		return delegate.launcher();
	}

	@Override
	public @Nullable UndoPlan undoPlanFor(String entryId) {
		return delegate.undoPlanFor(entryId);
	}

	@Override
	public HistoryModel.@Nullable View history() {
		return delegate.history();
	}

	@Override
	public HistoryModel.Labels settingLabels() {
		return delegate.settingLabels();
	}

	@Override
	public ShareReport.@Nullable Versions reportVersions() {
		return delegate.reportVersions();
	}

	@Override
	public ApplyPreview preview(List<Recommendation> selected) {
		return delegate.preview(selected);
	}

	@Override
	public List<Notice> notices() {
		return delegate.notices();
	}

	@Override
	public void noticeAction(String key, String actionId) {
		delegate.noticeAction(key, actionId);
	}

	@Override
	public void dismissNotice(String key) {
		delegate.dismissNotice(key);
	}

	@Override
	public List<ProfileView> profiles() {
		return delegate.profiles();
	}

	@Override
	public Component switchProfile(String id) {
		return delegate.switchProfile(id);
	}

	@Override
	public ApplyPreview previewProfile(String id) {
		return delegate.previewProfile(id);
	}

	@Override
	public Component saveCurrentProfile(String name) {
		return delegate.saveCurrentProfile(name);
	}

	@Override
	public ProfileImport importProfileCode(String code) {
		return delegate.importProfileCode(code);
	}

	@Override
	public @Nullable String exportProfileCode(String id) {
		return delegate.exportProfileCode(id);
	}

	@Override
	public int profileCodeLeftOut(String id) {
		return delegate.profileCodeLeftOut(id);
	}

	@Override
	public void renameProfile(String id, String name) {
		delegate.renameProfile(id, name);
	}

	@Override
	public void deleteProfile(String id) {
		delegate.deleteProfile(id);
	}

	@Override
	public StutterView stutter() {
		return delegate.stutter();
	}

	@Override
	public void setStutterMonitor(boolean on) {
		delegate.setStutterMonitor(on);
	}

	@Override
	public void pauseStutterMonitor(boolean paused) {
		delegate.pauseStutterMonitor(paused);
	}

	@Override
	public void clearStutter() {
		delegate.clearStutter();
	}

	@Override
	public String stutterSummary() {
		return delegate.stutterSummary();
	}

	@Override
	public JvmReport jvmReport() {
		return delegate.jvmReport();
	}

	@Override
	public BenchmarkTrend.View benchmarkTrend(@Nullable String contextKey) {
		return delegate.benchmarkTrend(contextKey);
	}

	@Override
	public @Nullable ServerLimits serverLimits() {
		return delegate.serverLimits();
	}

	@Override
	public StartupTimes.View startupTimes() {
		return delegate.startupTimes();
	}

	// v0.5 (docs/v0.5/SPEC.md C4).

	@Override
	public boolean firstApplyPending() {
		return delegate.firstApplyPending();
	}

	@Override
	public Component apply(List<Recommendation> selected, String entryId) {
		return delegate.apply(selected, entryId);
	}

	@Override
	public boolean downloading() {
		return delegate.downloading();
	}

	@Override
	public ModFilesPolicy modFiles() {
		return delegate.modFiles();
	}

	// v0.5 WS-L1 (review M2; approved frozen-file exception).
	@Override
	public boolean modFilesOptedIn() {
		return delegate.modFilesOptedIn();
	}

	@Override
	public ApplyPreview previewStutterFix(FixOffer.Offer offer) {
		return delegate.previewStutterFix(offer);
	}

	@Override
	public Component applyStutterFix(FixOffer.Offer offer) {
		return delegate.applyStutterFix(offer);
	}

	@Override
	public Component startStutterFix(FixOffer.Offer offer) {
		return delegate.startStutterFix(offer);
	}

	@Override
	public void dismissStutterFix(String entryId) {
		delegate.dismissStutterFix(entryId);
	}

	@Override
	public TryItView tryIt() {
		return delegate.tryIt();
	}

	@Override
	public @Nullable Text tryItRefusal(Recommendation rec) {
		return delegate.tryItRefusal(rec);
	}

	@Override
	public Component startTryIt(Recommendation rec, BenchmarkRequest.Scene scene) {
		return delegate.startTryIt(rec, scene);
	}

	@Override
	public void tryItMeasureNow() {
		delegate.tryItMeasureNow();
	}

	@Override
	public Component tryItKeep() {
		return delegate.tryItKeep();
	}

	@Override
	public void tryItCancel() {
		delegate.tryItCancel();
	}

	@Override
	public void tryItRefresh() {
		delegate.tryItRefresh();
	}

	@Override
	public @Nullable Text tryItSettling(BenchmarkRequest.Scene scene) {
		return delegate.tryItSettling(scene);
	}

	@Override
	public ServerProfilesView serverProfiles() {
		return delegate.serverProfiles();
	}

	@Override
	public Component rememberServerProfile(@Nullable String profileId) {
		return delegate.rememberServerProfile(profileId);
	}

	@Override
	public Component forgetServerProfile(String key) {
		return delegate.forgetServerProfile(key);
	}

	@Override
	public Component forgetAllServerProfiles() {
		return delegate.forgetAllServerProfiles();
	}
}
